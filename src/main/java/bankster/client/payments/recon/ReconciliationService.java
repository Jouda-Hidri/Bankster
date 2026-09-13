package bankster.client.payments.recon;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import bankster.client.payments.Money;
import bankster.client.payments.cards.Capture;
import bankster.client.payments.cards.Chargeback;
import bankster.client.payments.cards.ChargebackService;
import bankster.client.payments.cards.Payment;
import bankster.client.payments.cards.Refund;
import bankster.client.payments.core.AuditTrail;
import bankster.client.payments.core.Outbox;
import bankster.client.payments.ledger.ChartOfAccounts;
import bankster.client.payments.ledger.JournalEntryDraft;
import bankster.client.payments.ledger.Ledger;
import bankster.client.payments.orchestration.PaymentRepository;
import bankster.client.payments.rails.BankStatement;
import bankster.client.payments.rails.BankStatement.StatementEntry;
import bankster.client.payments.recon.ReconciliationBreak.BreakType;
import bankster.client.payments.recon.ReconciliationBreak.Severity;
import bankster.client.payments.settlement.ProcessorSettlementReport;
import bankster.client.payments.settlement.ProcessorSettlementReport.Line;
import bankster.client.payments.settlement.ProcessorSettlementReport.LineType;
import bankster.client.payments.settlement.SettlementBatch;
import bankster.client.payments.settlement.SettlementService;

/**
 * Three-way reconciliation: internal ledger against the processor's settlement
 * report against the bank statement.
 *
 * <p>Reconciliation is the control that catches everything the happy path missed.
 * Every other component in this system can be individually correct and the money
 * still be wrong — a capture that succeeded at the processor and failed to book, a
 * refund the processor applied twice, a chargeback the acquirer deducted and never
 * reported, fees priced differently from the contract. None of those is visible
 * from inside the component that caused it. They are visible only by comparing
 * independent records of the same money.
 *
 * <p>Three legs, and each catches something the others cannot:
 *
 * <ol>
 *   <li><b>Internal against processor report</b> — catches transactions recorded
 *       on one side only, and amounts or fees that differ.</li>
 *   <li><b>Processor report against bank statement</b> — catches money that was
 *       reported as settled but did not arrive, which is the failure the first leg
 *       is structurally blind to, since both its inputs are claims rather than
 *       cash.</li>
 *   <li><b>Ledger self-check</b> — catches our own books being internally wrong:
 *       debits not equal to credits, or a journal whose hash chain no longer
 *       verifies. No external comparison can detect either.</li>
 * </ol>
 *
 * <p>Two practices stop reconciliation degenerating into a list nobody reads.
 * Sub-cent differences are matched <em>within tolerance</em> and counted rather
 * than raised, because a rounding difference on a percentage fee is expected and
 * burying real breaks among thousands of one-cent items is how real breaks get
 * missed. And unexplained differences can be written off to a suspense account —
 * which keeps the ledger balanced without pretending the difference never
 * happened, since the suspense balance itself is then a reported number somebody
 * has to explain.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    /** Differences at or below this are treated as rounding, not as breaks. */
    public static final long TOLERANCE_MINOR_UNITS = 1;

    private final Clock clock;
    private final PaymentRepository payments;
    private final ChargebackService chargebacks;
    private final SettlementService settlements;
    private final Ledger ledger;
    private final AuditTrail auditTrail;
    private final Outbox outbox;

    /** Open breaks, keyed so that the same break found again ages rather than duplicates. */
    private final Map<String, ReconciliationBreak> openBreaks = new ConcurrentHashMap<>();

    private final List<ReconciliationRun> runs = new ArrayList<>();

    public ReconciliationService(Clock clock, PaymentRepository payments, ChargebackService chargebacks,
                                 SettlementService settlements, Ledger ledger,
                                 AuditTrail auditTrail, Outbox outbox) {
        this.clock = clock;
        this.payments = payments;
        this.chargebacks = chargebacks;
        this.settlements = settlements;
        this.ledger = ledger;
        this.auditTrail = auditTrail;
        this.outbox = outbox;
    }

    /**
     * An expected movement, derived from our own records.
     *
     * <p>Keyed on the individual capture, refund or dispute rather than on the
     * payment, because one payment legitimately produces several lines.
     */
    private record InternalItem(String key, String paymentId, LineType type, Money gross, Money fee) {
    }

    /**
     * Reconciles a settlement batch.
     *
     * @param statement the bank statement covering the funding, or {@code null}
     *                  when the batch has not been funded yet — in which case the
     *                  second leg is skipped rather than reported as a break
     */
    public ReconciliationRun reconcile(String batchId, ProcessorSettlementReport report,
                                       BankStatement statement) {
        Instant now = clock.instant();
        SettlementBatch batch = settlements.require(batchId);
        List<ReconciliationBreak> found = new ArrayList<>();

        // Leg 0: is the external file even usable? Reconciling against a truncated
        // report manufactures breaks that do not exist.
        if (!report.isSelfConsistent()) {
            found.add(raise(BreakType.SELF_INCONSISTENT_FILE, report.reportId(), null,
                    report.computedNet(), report.netAmount(),
                    "the report's header net of " + report.netAmount()
                            + " does not match the sum of its lines, " + report.computedNet(),
                    now));
        }
        if (statement != null && !statement.isSelfConsistent()) {
            found.add(raise(BreakType.SELF_INCONSISTENT_FILE, statement.statementId(), null,
                    statement.computedClosingBalance(), statement.closingBalance(),
                    "the statement's closing balance does not match its opening balance plus entries",
                    now));
        }

        // Leg 1: our records against the processor's.
        MatchResult lineMatch = matchLines(batch, report, now);
        found.addAll(lineMatch.breaks());

        // Leg 2: the processor's report against the money that arrived.
        Money bankTotal = Money.zero(batch.currency());
        if (statement != null) {
            BankMatch bankMatch = matchAgainstBank(batch, report, statement, now);
            bankTotal = bankMatch.credited();
            found.addAll(bankMatch.breaks());
        }

        // Leg 3: are our own books internally sound?
        found.addAll(checkLedger(now));

        ReconciliationRun run = new ReconciliationRun(
                "rec_" + UUID.randomUUID().toString().substring(0, 12), now, batchId,
                lineMatch.matched(), lineMatch.withinTolerance(), found,
                batch.acquirerFunding(), report.netAmount(), bankTotal);
        runs.add(run);

        auditTrail.record("reconciliation", "reconciliation.run", batchId, Map.of(
                "runId", run.runId(),
                "matched", String.valueOf(run.matchedCount()),
                "withinTolerance", String.valueOf(run.withinToleranceCount()),
                "breaks", String.valueOf(found.size()),
                "internalTotal", run.internalTotal().toString(),
                "externalTotal", run.externalTotal().toString(),
                "bankTotal", run.bankTotal().toString(),
                "summary", run.summary()));

        if (run.hasBlockingBreaks()) {
            // Do not pay a merchant out of a batch we cannot explain.
            settlements.hold(batchId, "reconciliation found "
                    + run.breaks().size() + " break(s): " + run.summary());
            outbox.append("reconciliation.breaks_found", batchId, Map.of(
                    "runId", run.runId(),
                    "breaks", String.valueOf(found.size()),
                    "summary", run.summary()));
            log.warn("Batch {} held after reconciliation: {}", batchId, run.summary());
        }

        return run;
    }

    // --- Leg 1: internal against the processor report ---------------------

    private record MatchResult(int matched, int withinTolerance, List<ReconciliationBreak> breaks) {
    }

    private MatchResult matchLines(SettlementBatch batch, ProcessorSettlementReport report, Instant now) {
        Map<String, InternalItem> internal = internalItemsFor(batch);
        Map<String, List<Line>> external = new LinkedHashMap<>();
        List<ReconciliationBreak> breaks = new ArrayList<>();

        for (Line line : report.lines()) {
            external.computeIfAbsent(keyOf(line), ignored -> new ArrayList<>()).add(line);
        }

        int matched = 0;
        int withinTolerance = 0;
        Set<String> seen = new LinkedHashSet<>();

        for (Map.Entry<String, InternalItem> entry : internal.entrySet()) {
            String key = entry.getKey();
            InternalItem item = entry.getValue();
            seen.add(key);
            List<Line> candidates = external.get(key);

            if (candidates == null || candidates.isEmpty()) {
                breaks.add(raise(BreakType.MISSING_EXTERNALLY, key, item.paymentId(),
                        item.gross(), Money.zero(item.gross().currency()),
                        item.type() + " of " + item.gross() + " on " + item.paymentId()
                                + " is in our records but not in the processor's report",
                        now));
                continue;
            }
            if (candidates.size() > 1) {
                // The same transaction reported twice would be funded twice.
                Money duplicated = candidates.get(1).gross();
                breaks.add(raise(BreakType.DUPLICATE_EXTERNAL, key, item.paymentId(),
                        item.gross(), duplicated,
                        item.type() + " on " + item.paymentId() + " appears "
                                + candidates.size() + " times in the processor's report",
                        now));
                continue;
            }

            Line line = candidates.get(0);
            long grossDifference = item.gross().minus(line.gross()).minorUnits();
            long feeDifference = item.fee().minus(line.fee()).minorUnits();

            if (Math.abs(grossDifference) > TOLERANCE_MINOR_UNITS) {
                breaks.add(raise(BreakType.AMOUNT_MISMATCH, key, item.paymentId(),
                        item.gross(), line.gross(),
                        item.type() + " on " + item.paymentId() + ": we have " + item.gross()
                                + ", the processor reports " + line.gross(),
                        now));
                continue;
            }
            if (Math.abs(feeDifference) > TOLERANCE_MINOR_UNITS) {
                // Not a lost transaction, but money: a fee priced differently from
                // the contract, applied across every transaction, is a large number.
                breaks.add(raise(BreakType.FEE_MISMATCH, key, item.paymentId(),
                        item.fee(), line.fee(),
                        "fee on " + item.paymentId() + ": we expected " + item.fee()
                                + ", the processor charged " + line.fee(),
                        now));
                continue;
            }

            matched++;
            if (grossDifference != 0 || feeDifference != 0) {
                withinTolerance++;
            }
        }

        // Anything in the external file we have no record of at all.
        for (Map.Entry<String, List<Line>> entry : external.entrySet()) {
            if (seen.contains(entry.getKey())) {
                continue;
            }
            for (Line line : entry.getValue()) {
                breaks.add(raise(BreakType.MISSING_INTERNALLY, entry.getKey(), line.paymentId(),
                        Money.zero(line.gross().currency()), line.gross(),
                        line.type() + " of " + line.gross() + " on " + line.paymentId()
                                + " is in the processor's report but not in our records",
                        now));
            }
        }

        return new MatchResult(matched, withinTolerance, breaks);
    }

    /** What our own records say the batch contains. */
    private Map<String, InternalItem> internalItemsFor(SettlementBatch batch) {
        Map<String, InternalItem> items = new LinkedHashMap<>();
        ProcessorSettlementReport expected = settlements.reportFor(batch.batchId());

        // The expected report is derived from the same internal records a real
        // processor file would be compared against, line for line.
        for (Line line : expected.lines()) {
            String key = keyOf(line);
            items.put(key, new InternalItem(key, line.paymentId(), line.type(),
                    line.gross(), line.fee()));
        }
        return items;
    }

    // --- Leg 2: the report against the bank statement ---------------------

    private record BankMatch(Money credited, List<ReconciliationBreak> breaks) {
    }

    /**
     * Finds the funding credit on the statement and compares it with the report.
     *
     * <p>Matched by reference first and by amount only as a fallback, and the
     * fallback is deliberately narrow: matching purely on amount goes wrong as soon
     * as two batches settle for the same figure, which for a single merchant on
     * consecutive quiet days is entirely ordinary.
     */
    private BankMatch matchAgainstBank(SettlementBatch batch, ProcessorSettlementReport report,
                                       BankStatement statement, Instant now) {
        List<ReconciliationBreak> breaks = new ArrayList<>();

        Optional<StatementEntry> byReference = statement.credits().stream()
                .filter(entry -> referencesBatch(entry, batch.batchId(), report.reportId()))
                .findFirst();

        Optional<StatementEntry> funding = byReference.or(() -> statement.credits().stream()
                .filter(entry -> entry.amount().equals(report.netAmount()))
                .findFirst());

        if (funding.isEmpty()) {
            breaks.add(raise(BreakType.FUNDING_MISMATCH, batch.batchId(), null,
                    report.netAmount(), Money.zero(batch.currency()),
                    "the processor reports " + report.netAmount() + " settled for batch "
                            + batch.batchId() + " but no matching credit appears on the statement",
                    now));
            return new BankMatch(Money.zero(batch.currency()), breaks);
        }

        StatementEntry entry = funding.get();
        long difference = report.netAmount().minus(entry.amount()).minorUnits();
        if (Math.abs(difference) > TOLERANCE_MINOR_UNITS) {
            breaks.add(raise(BreakType.FUNDING_MISMATCH, batch.batchId(), null,
                    report.netAmount(), entry.amount(),
                    "the processor reports " + report.netAmount() + " settled but "
                            + entry.amount() + " arrived in the bank account",
                    now));
        }
        return new BankMatch(entry.amount(), breaks);
    }

    private boolean referencesBatch(StatementEntry entry, String batchId, String reportId) {
        return containsIgnoreCase(entry.endToEndId(), batchId)
                || containsIgnoreCase(entry.remittanceInformation(), batchId)
                || containsIgnoreCase(entry.remittanceInformation(), reportId)
                || containsIgnoreCase(entry.entryReference(), batchId);
    }

    private boolean containsIgnoreCase(String haystack, String needle) {
        return haystack != null && needle != null
                && haystack.toLowerCase().contains(needle.toLowerCase());
    }

    // --- Leg 3: the ledger's own integrity -------------------------------

    /**
     * Checks the books against themselves.
     *
     * <p>Cheap, and it runs every time, because an imbalanced or altered ledger
     * invalidates every other number in the reconciliation. There is no point
     * explaining a difference against an external file if the internal figure it is
     * being compared with is itself unsound.
     */
    public List<ReconciliationBreak> checkLedger(Instant now) {
        List<ReconciliationBreak> breaks = new ArrayList<>();

        Ledger.TrialBalance trialBalance = ledger.trialBalance();
        if (!trialBalance.balances()) {
            for (Map.Entry<String, Money> debits : trialBalance.totalDebits().entrySet()) {
                Money credits = trialBalance.totalCredits()
                        .getOrDefault(debits.getKey(), Money.zero(debits.getKey()));
                if (!credits.equals(debits.getValue())) {
                    breaks.add(raise(BreakType.LEDGER_IMBALANCE, "trial-balance-" + debits.getKey(), null,
                            debits.getValue(), credits,
                            "the trial balance does not balance in " + debits.getKey()
                                    + ": debits " + debits.getValue() + ", credits " + credits,
                            now));
                }
            }
        }

        Ledger.IntegrityReport integrity = ledger.verifyIntegrity();
        if (!integrity.intact()) {
            breaks.add(raise(BreakType.LEDGER_TAMPERED, integrity.failedEntryId(), null,
                    Money.zero("EUR"), Money.zero("EUR"),
                    "the journal's hash chain failed at entry " + integrity.failedEntryId()
                            + ": " + integrity.reason(),
                    now));
        }
        return breaks;
    }

    // --- Break management -------------------------------------------------

    /**
     * Records a break, or ages the existing one if it has been seen before.
     *
     * <p>Keying on the content rather than generating a new identity each run is
     * what makes ageing possible. A run that creates fresh breaks every time cannot
     * distinguish a new problem from an old one, which is the distinction the whole
     * report exists to surface.
     */
    private ReconciliationBreak raise(BreakType type, String reference, String paymentId,
                                      Money internal, Money external, String description,
                                      Instant now) {
        String key = type + "|" + reference + "|" + paymentId;
        ReconciliationBreak existing = openBreaks.get(key);
        if (existing != null) {
            ReconciliationBreak aged = existing.seenAgain(now);
            openBreaks.put(key, aged);
            return aged;
        }
        ReconciliationBreak raised = new ReconciliationBreak(
                key, type, reference, paymentId, internal, external,
                internal.currency().equals(external.currency())
                        ? internal.minus(external)
                        : internal,
                description, now, now, false);
        openBreaks.put(key, raised);
        return raised;
    }

    /**
     * Moves an unexplained difference to suspense and closes the break.
     *
     * <p>This is what a controller actually does with a difference that cannot be
     * identified: the ledger must balance, and leaving it out of balance is not an
     * option, but neither is quietly absorbing the difference into a revenue or
     * expense line where it disappears. Suspense keeps it visible — the balance on
     * that account is itself a reported figure somebody has to justify, which is
     * what stops write-off becoming a habit.
     */
    public ReconciliationBreak writeOffToSuspense(String breakKey, String reason) {
        ReconciliationBreak item = openBreaks.get(breakKey);
        if (item == null) {
            throw new IllegalArgumentException("Unknown reconciliation break: " + breakKey);
        }
        Money difference = item.difference();
        if (difference.isZero()) {
            throw new IllegalStateException("Break " + breakKey + " has no difference to write off");
        }

        String currency = difference.currency();
        Money amount = difference.abs();
        ChartOfAccounts.bootstrap(ledger, currency, List.of());

        // A positive difference means we expected more than the external record
        // shows, so the shortfall is parked as an asset pending investigation; a
        // negative one means unexplained money arrived.
        JournalEntryDraft draft = JournalEntryDraft.of(
                        "Reconciliation write-off to suspense: " + reason)
                .reference(item.breakKey());
        if (difference.isPositive()) {
            draft.debit(ChartOfAccounts.in(ChartOfAccounts.SUSPENSE, currency), amount,
                            "unexplained shortfall against " + item.reference())
                    .credit(ChartOfAccounts.in(ChartOfAccounts.SCHEME_RECEIVABLE, currency), amount,
                            "receivable written down pending investigation");
        } else {
            draft.debit(ChartOfAccounts.in(ChartOfAccounts.SCHEME_RECEIVABLE, currency), amount,
                            "unexplained receipt against " + item.reference())
                    .credit(ChartOfAccounts.in(ChartOfAccounts.SUSPENSE, currency), amount,
                            "held in suspense pending investigation");
        }
        ledger.post(draft);

        ReconciliationBreak closed = item.markWrittenOff();
        openBreaks.put(breakKey, closed);

        auditTrail.record("reconciliation", "reconciliation.written_off", item.reference(), Map.of(
                "breakKey", breakKey,
                "type", item.type().name(),
                "amount", amount.toString(),
                "reason", reason));
        return closed;
    }

    /** Clears a break that has been explained without a posting — usually timing. */
    public boolean resolve(String breakKey, String explanation) {
        ReconciliationBreak removed = openBreaks.remove(breakKey);
        if (removed == null) {
            return false;
        }
        auditTrail.record("reconciliation", "reconciliation.resolved", removed.reference(), Map.of(
                "breakKey", breakKey,
                "type", removed.type().name(),
                "explanation", explanation,
                "ageInDays", String.valueOf(removed.ageInDays(clock.instant()))));
        return true;
    }

    // --- Reporting --------------------------------------------------------

    public List<ReconciliationBreak> openBreaks() {
        Instant now = clock.instant();
        return openBreaks.values().stream()
                .filter(item -> !item.writtenOff())
                .sorted((a, b) -> b.severity(now).compareTo(a.severity(now)))
                .toList();
    }

    /** Breaks grouped into ageing buckets — how the report is normally read. */
    public Map<String, List<ReconciliationBreak>> agedBreaks() {
        Instant now = clock.instant();
        Map<String, List<ReconciliationBreak>> buckets = new LinkedHashMap<>();
        buckets.put("same day", new ArrayList<>());
        buckets.put("2–7 days", new ArrayList<>());
        buckets.put("8–30 days", new ArrayList<>());
        buckets.put("over 30 days", new ArrayList<>());
        for (ReconciliationBreak item : openBreaks()) {
            buckets.get(item.ageBucket(now)).add(item);
        }
        buckets.values().removeIf(List::isEmpty);
        return buckets;
    }

    public List<ReconciliationRun> runs() {
        return List.copyOf(runs);
    }

    public Optional<ReconciliationRun> lastRun() {
        return runs.isEmpty() ? Optional.empty() : Optional.of(runs.get(runs.size() - 1));
    }

    /** Breaks serious enough to stop a payout — MEDIUM and above. */
    public List<ReconciliationBreak> blockingBreaks() {
        Instant now = clock.instant();
        return openBreaks().stream()
                .filter(item -> item.severity(now) == Severity.CRITICAL
                        || item.severity(now) == Severity.HIGH
                        || item.severity(now) == Severity.MEDIUM)
                .toList();
    }

    /**
     * Cross-checks the unsettled transfer population against the in-transit
     * balance.
     *
     * <p>A second, independent reconciliation on the banking side: the sum of
     * transfers instructed but not yet settled must equal the ledger's in-transit
     * liability. A difference means a transfer was booked and never sent, or sent
     * and never booked.
     */
    public Optional<ReconciliationBreak> reconcileInTransit(Money expectedFromTransfers,
                                                            Money ledgerInTransit) {
        long difference = expectedFromTransfers.minus(ledgerInTransit).minorUnits();
        if (Math.abs(difference) <= TOLERANCE_MINOR_UNITS) {
            return Optional.empty();
        }
        return Optional.of(raise(BreakType.AMOUNT_MISMATCH, "payments-in-transit", null,
                expectedFromTransfers, ledgerInTransit,
                "unsettled transfers total " + expectedFromTransfers
                        + " but the in-transit ledger balance is " + ledgerInTransit,
                clock.instant()));
    }

    /**
     * The matching key: the individual item, not the payment.
     *
     * <p>Keying on the payment would make a split shipment's second capture look
     * like a duplicate presentment of its first — a break that is not a break, which
     * is worse than a missed one because it trains people to ignore the report.
     */
    private String keyOf(Line line) {
        return line.type() + "|" + line.itemId();
    }

    /** Exposed so the console can show which payments are captured but unsettled. */
    public List<Payment> capturedNotSettled() {
        List<Payment> result = new ArrayList<>();
        for (Payment payment : payments.all()) {
            boolean hasUnsettled = payment.captures().stream().anyMatch(capture -> !capture.isSettled());
            if (hasUnsettled) {
                result.add(payment);
            }
        }
        return result;
    }

    /** Total value captured and not yet swept into a batch. */
    public Money unsettledCaptureValue(String currency) {
        Money total = Money.zero(currency);
        for (Payment payment : payments.all()) {
            for (Capture capture : payment.captures()) {
                if (!capture.isSettled() && capture.amount().currency().equals(currency)) {
                    total = total.plus(capture.amount());
                }
            }
        }
        return total;
    }

    /** Disputes raised but not yet resolved — a liability that is easy to forget. */
    public List<Chargeback> openDisputes() {
        return chargebacks.all().stream()
                .filter(chargeback -> !chargeback.status().isResolved())
                .toList();
    }

    /** Refunds issued in the period, for the console's summary. */
    public Money refundedValue(String currency) {
        Money total = Money.zero(currency);
        for (Payment payment : payments.all()) {
            for (Refund refund : payment.refunds()) {
                if (refund.amount().currency().equals(currency)) {
                    total = total.plus(refund.amount());
                }
            }
        }
        return total;
    }
}
