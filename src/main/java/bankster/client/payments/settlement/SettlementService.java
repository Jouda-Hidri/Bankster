package bankster.client.payments.settlement;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import bankster.client.payments.cards.AcquirerProcessor;
import bankster.client.payments.cards.BinTable;
import bankster.client.payments.cards.Capture;
import bankster.client.payments.cards.Chargeback;
import bankster.client.payments.cards.ChargebackService;
import bankster.client.payments.cards.FeeSchedule;
import bankster.client.payments.cards.Payment;
import bankster.client.payments.cards.Refund;
import bankster.client.payments.core.AuditTrail;
import bankster.client.payments.core.Outbox;
import bankster.client.payments.orchestration.PaymentBookkeeper;
import bankster.client.payments.orchestration.PaymentRepository;
import bankster.client.payments.orchestration.PaymentRouter;
import bankster.client.payments.settlement.ProcessorSettlementReport.Line;
import bankster.client.payments.settlement.ProcessorSettlementReport.LineType;

/**
 * Sweeps captured transactions into settlement batches, books the funds when they
 * arrive, and pays merchants out.
 *
 * <p>Settlement is where a payments system stops being about messages and starts
 * being about money, and it runs on three separate clocks that are easy to
 * conflate:
 *
 * <ol>
 *   <li>The <b>cut-off</b> decides which transactions belong to which batch.
 *       Everything captured before it settles together; a capture a minute later
 *       waits a whole cycle.</li>
 *   <li><b>Funding</b> is when the acquirer's money reaches our account — one to
 *       three days after the cut-off, depending on the processor. Until then the
 *       captures are a receivable, not cash.</li>
 *   <li><b>Payout</b> is when we pay the merchant, which is our decision and may
 *       deliberately lag funding: holding a rolling reserve against a new or
 *       high-dispute merchant is how an acquirer manages the risk that disputes
 *       arrive after the merchant has been paid and disappeared.</li>
 * </ol>
 *
 * <p>Keeping the three distinct is what makes the ledger reconcile. Collapsing
 * them — treating capture as cash, or payout as automatic on funding — produces a
 * balance sheet that cannot be tied to a bank statement.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final Clock clock;
    private final PaymentRepository payments;
    private final PaymentRouter router;
    private final BinTable binTable;
    private final PaymentBookkeeper bookkeeper;
    private final ChargebackService chargebacks;
    private final AuditTrail auditTrail;
    private final Outbox outbox;

    private final Map<String, SettlementBatch> batches = new ConcurrentHashMap<>();

    /** Refunds and disputes already swept, so a second run does not double-count. */
    private final Set<String> sweptRefundIds = ConcurrentHashMap.newKeySet();
    private final Set<String> sweptChargebackIds = ConcurrentHashMap.newKeySet();

    public SettlementService(Clock clock, PaymentRepository payments, PaymentRouter router,
                             BinTable binTable, PaymentBookkeeper bookkeeper,
                             ChargebackService chargebacks, AuditTrail auditTrail, Outbox outbox) {
        this.clock = clock;
        this.payments = payments;
        this.router = router;
        this.binTable = binTable;
        this.bookkeeper = bookkeeper;
        this.chargebacks = chargebacks;
        this.auditTrail = auditTrail;
        this.outbox = outbox;
    }

    /**
     * Runs a cut-off: everything captured and not yet batched is swept into
     * batches, one per merchant, currency and processor.
     *
     * <p>Grouped by processor as well as merchant because each processor funds on
     * its own schedule and sends its own settlement report, so a batch spanning two
     * of them could not be reconciled against either.
     */
    public List<SettlementBatch> runCutOff() {
        Instant cutOffAt = clock.instant();
        Map<String, SettlementBatch> created = new LinkedHashMap<>();

        for (Payment payment : payments.all()) {
            String processorId = payment.processorId();
            if (processorId == null) {
                continue;
            }

            FeeSchedule fees = binTable.feeScheduleFor(
                    binTable.lookup(payment.card()), payment.requestedAmount().currency());

            for (Capture capture : payment.captures()) {
                if (capture.isSettled()) {
                    continue;
                }
                SettlementBatch batch = created.computeIfAbsent(
                        key(payment.merchantId(), capture.amount().currency(), processorId),
                        ignored -> openBatch(payment.merchantId(), processorId,
                                capture.amount().currency(), cutOffAt));

                Money acquirerDeduction = fees.interchange(capture.amount()).plus(fees.schemeFee());
                batch.addSale(capture.captureId(), capture.amount(),
                        fees.merchantDiscount(capture.amount()), acquirerDeduction);
                capture.assignToBatch(batch.batchId());
            }

            for (Refund refund : payment.refunds()) {
                if (!sweptRefundIds.add(refund.refundId())) {
                    continue;
                }
                SettlementBatch batch = created.computeIfAbsent(
                        key(payment.merchantId(), refund.amount().currency(), processorId),
                        ignored -> openBatch(payment.merchantId(), processorId,
                                refund.amount().currency(), cutOffAt));
                batch.addRefund(refund.refundId(), refund.amount());
            }
        }

        for (Chargeback chargeback : chargebacks.all()) {
            if (!sweptChargebackIds.add(chargeback.caseId())) {
                continue;
            }
            Optional<Payment> payment = payments.find(chargeback.paymentId());
            String processorId = payment.map(Payment::processorId).orElse("unknown");
            SettlementBatch batch = created.computeIfAbsent(
                    key(chargeback.merchantId(), chargeback.disputedAmount().currency(), processorId),
                    ignored -> openBatch(chargeback.merchantId(), processorId,
                            chargeback.disputedAmount().currency(), cutOffAt));
            batch.addChargeback(chargeback.caseId(), chargeback.disputedAmount(), chargeback.fee());
        }

        List<SettlementBatch> closed = new ArrayList<>();
        for (SettlementBatch batch : created.values()) {
            if (batch.transactionCount() == 0) {
                continue;
            }
            batch.close();
            batches.put(batch.batchId(), batch);
            closed.add(batch);

            auditTrail.record("system", "settlement.batch_closed", batch.batchId(), Map.of(
                    "merchantId", batch.merchantId(),
                    "processor", batch.processorId(),
                    "transactions", String.valueOf(batch.transactionCount()),
                    "gross", batch.grossSales().toString(),
                    "refunds", batch.refunds().toString(),
                    "chargebacks", batch.chargebacks().toString(),
                    "acquirerFunding", batch.acquirerFunding().toString(),
                    "netPayout", batch.netPayout().toString(),
                    "margin", batch.margin().toString()));
            outbox.append("settlement.batch_closed", batch.batchId(), Map.of(
                    "merchantId", batch.merchantId(),
                    "netPayout", batch.netPayout().toString(),
                    "expectedFunding", batch.expectedFundingDate().toString()));

            if (batch.isNegative()) {
                log.info("Batch {} for {} is negative ({}); the merchant owes us",
                        batch.batchId(), batch.merchantId(), batch.netPayout());
            }
        }
        return closed;
    }

    /**
     * Records the arrival of the acquirer's money: the receivable becomes cash.
     *
     * <p>Deliberately takes the amount that actually arrived rather than assuming
     * the expected one. Where they differ, that difference is the reconciliation
     * break — and a method that assumed the expected amount would hide it.
     */
    public SettlementBatch markFunded(String batchId, Money amountReceived) {
        SettlementBatch batch = require(batchId);
        Instant now = clock.instant();

        bookkeeper.recordSettlementReceipt(batchId, amountReceived);
        batch.markFunded(now);

        boolean asExpected = amountReceived.equals(batch.acquirerFunding());
        auditTrail.record("acquirer", "settlement.funded", batchId, Map.of(
                "expected", batch.acquirerFunding().toString(),
                "received", amountReceived.toString(),
                "matchesExpected", String.valueOf(asExpected)));
        outbox.append("settlement.funded", batchId, Map.of(
                "received", amountReceived.toString(),
                "matchesExpected", String.valueOf(asExpected)));

        if (!asExpected) {
            log.warn("Batch {} funded with {} but expected {}",
                    batchId, amountReceived, batch.acquirerFunding());
        }
        return batch;
    }

    /** Funds the batch with exactly the expected amount — the happy path. */
    public SettlementBatch markFunded(String batchId) {
        return markFunded(batchId, require(batchId).acquirerFunding());
    }

    /**
     * Pays the merchant.
     *
     * <p>Refuses while the batch is held, which is the point of holding it: a batch
     * with an unexplained discrepancy must not be paid out, because paying out is
     * the step that cannot be undone.
     */
    public SettlementBatch payOut(String batchId) {
        SettlementBatch batch = require(batchId);
        Instant now = clock.instant();

        if (batch.status() == SettlementBatch.Status.HELD) {
            throw new IllegalStateException(
                    "Batch " + batchId + " is held: " + batch.holdReason());
        }
        if (batch.status() != SettlementBatch.Status.FUNDED) {
            throw new IllegalStateException(
                    "Batch " + batchId + " is " + batch.status() + "; funds must arrive before payout");
        }
        if (!batch.netPayout().isPositive()) {
            // Nothing to pay. The negative balance stays on the merchant's payable
            // account and is recovered from the next batch.
            batch.markPaidOut(now);
            auditTrail.record("system", "settlement.payout_skipped", batchId, Map.of(
                    "netPayout", batch.netPayout().toString(),
                    "reason", "balance is not positive; carried against the next batch"));
            return batch;
        }

        bookkeeper.recordMerchantPayout(batchId, batch.merchantId(), batch.netPayout());
        batch.markPaidOut(now);

        auditTrail.record("system", "settlement.paid_out", batchId, Map.of(
                "merchantId", batch.merchantId(),
                "amount", batch.netPayout().toString()));
        outbox.append("settlement.paid_out", batchId, Map.of(
                "merchantId", batch.merchantId(),
                "amount", batch.netPayout().toString()));
        return batch;
    }

    /** Stops a batch being paid out while a discrepancy is investigated. */
    public SettlementBatch hold(String batchId, String reason) {
        SettlementBatch batch = require(batchId);
        batch.hold(reason);
        auditTrail.record("reconciliation", "settlement.held", batchId, Map.of("reason", reason));
        outbox.append("settlement.held", batchId, Map.of("reason", reason));
        return batch;
    }

    public SettlementBatch release(String batchId) {
        SettlementBatch batch = require(batchId);
        batch.release();
        auditTrail.record("reconciliation", "settlement.released", batchId, Map.of());
        return batch;
    }

    /**
     * Produces the settlement report the processor would send for a batch.
     *
     * <p>Generated from our own records, so on its own it proves nothing — its
     * purpose is to give reconciliation a well-formed external file to work
     * against, including when a test deliberately perturbs it to create a break.
     */
    public ProcessorSettlementReport reportFor(String batchId) {
        SettlementBatch batch = require(batchId);
        List<Line> lines = new ArrayList<>();

        for (Payment payment : payments.forMerchant(batch.merchantId())) {
            FeeSchedule fees = binTable.feeScheduleFor(
                    binTable.lookup(payment.card()), batch.currency());

            for (Capture capture : payment.captures()) {
                if (!batch.batchId().equals(capture.settlementBatchId())) {
                    continue;
                }
                lines.add(new Line(capture.captureId(), payment.processorReference(),
                        payment.paymentId(), LineType.SALE,
                        capture.amount(),
                        fees.interchange(capture.amount()).plus(fees.schemeFee()),
                        LocalDate.ofInstant(capture.capturedAt(), java.time.ZoneOffset.UTC)));
            }
            for (Refund refund : payment.refunds()) {
                if (!batch.refundIds().contains(refund.refundId())) {
                    continue;
                }
                lines.add(new Line(refund.refundId(), payment.processorReference(),
                        payment.paymentId(), LineType.REFUND,
                        refund.amount(), Money.zero(batch.currency()),
                        LocalDate.ofInstant(refund.refundedAt(), java.time.ZoneOffset.UTC)));
            }
        }

        for (String caseId : batch.chargebackIds()) {
            chargebacks.find(caseId).ifPresent(chargeback -> lines.add(new Line(
                    caseId, caseId, chargeback.paymentId(), LineType.CHARGEBACK,
                    chargeback.disputedAmount(), Money.zero(batch.currency()),
                    LocalDate.ofInstant(chargeback.receivedAt(), java.time.ZoneOffset.UTC))));
        }

        return new ProcessorSettlementReport(
                "RPT-" + batch.batchId(),
                batch.processorId(),
                batch.merchantId(),
                batch.expectedFundingDate(),
                batch.currency(),
                batch.grossSales(),
                batch.interchangeAndSchemeFees(),
                batch.acquirerFunding(),
                lines);
    }

    // --- Queries ----------------------------------------------------------

    public Optional<SettlementBatch> find(String batchId) {
        return Optional.ofNullable(batches.get(batchId));
    }

    public SettlementBatch require(String batchId) {
        return find(batchId).orElseThrow(
                () -> new IllegalArgumentException("Unknown settlement batch: " + batchId));
    }

    public List<SettlementBatch> all() {
        return batches.values().stream()
                .sorted((a, b) -> b.cutOffAt().compareTo(a.cutOffAt()))
                .toList();
    }

    public List<SettlementBatch> withStatus(SettlementBatch.Status status) {
        return all().stream().filter(batch -> batch.status() == status).toList();
    }

    public List<SettlementBatch> forMerchant(String merchantId) {
        return all().stream().filter(batch -> batch.merchantId().equals(merchantId)).toList();
    }

    // --- Helpers ----------------------------------------------------------

    private SettlementBatch openBatch(String merchantId, String processorId, String currency,
                                      Instant cutOffAt) {
        LocalDate fundingDate = LocalDate.ofInstant(cutOffAt, java.time.ZoneOffset.UTC)
                .plusDays(settlementDelayDays(processorId));
        return new SettlementBatch(
                "bat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                merchantId, processorId, currency, cutOffAt, fundingDate);
    }

    /** How long this processor takes to fund — a real difference between them. */
    private long settlementDelayDays(String processorId) {
        return router.processors().stream()
                .filter(processor -> processor.id().equals(processorId))
                .findFirst()
                .map(AcquirerProcessor::settlementDelay)
                .map(duration -> Math.max(1, duration.toDays()))
                .orElse(2L);
    }

    private String key(String merchantId, String currency, String processorId) {
        return merchantId + "|" + currency + "|" + processorId;
    }
}
