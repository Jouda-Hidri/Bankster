package bankster.client.payments.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import bankster.client.payments.Money;
import bankster.client.payments.TestClock;
import bankster.client.payments.cards.AcquirerProcessor;
import bankster.client.payments.cards.CardDetails;
import bankster.client.payments.cards.CardPaymentService;
import bankster.client.payments.cards.CardPaymentService.PaymentResult;
import bankster.client.payments.cards.CardScheme;
import bankster.client.payments.cards.ChargebackReason;
import bankster.client.payments.cards.ChargebackService;
import bankster.client.payments.cards.BinTable;
import bankster.client.payments.cards.FeeSchedule;
import bankster.client.payments.cards.IssuerSimulator;
import bankster.client.payments.cards.Pan;
import bankster.client.payments.cards.SimulatedAcquirerProcessor;
import bankster.client.payments.cards.ThreeDSecureService;
import bankster.client.payments.cards.TokenVault;
import bankster.client.payments.core.AuditTrail;
import bankster.client.payments.core.IdempotencyStore;
import bankster.client.payments.core.Outbox;
import bankster.client.payments.ledger.ChartOfAccounts;
import bankster.client.payments.ledger.Ledger;
import bankster.client.payments.orchestration.PaymentBookkeeper;
import bankster.client.payments.orchestration.PaymentRepository;
import bankster.client.payments.orchestration.PaymentRouter;
import bankster.client.payments.rails.BankStatement;
import bankster.client.payments.rails.BankStatement.StatementEntry;
import bankster.client.payments.recon.ReconciliationBreak;
import bankster.client.payments.recon.ReconciliationBreak.BreakType;
import bankster.client.payments.recon.ReconciliationRun;
import bankster.client.payments.recon.ReconciliationService;
import bankster.client.payments.risk.FraudEngine;
import bankster.client.payments.settlement.ProcessorSettlementReport.Line;
import bankster.client.payments.settlement.ProcessorSettlementReport.LineType;

/**
 * Batching, netting, funding, payout — and the three-way reconciliation that has to
 * catch anything the happy path missed.
 */
class SettlementAndReconciliationTest {

    private static final String MERCHANT = "merchant-1";
    private static final String CURRENCY = "EUR";
    private static final String GOOD_BIN = "400000";

    private TestClock clock;
    private Ledger ledger;
    private TokenVault vault;
    private BinTable binTable;
    private IssuerSimulator issuer;
    private PaymentRepository payments;
    private PaymentBookkeeper bookkeeper;
    private CardPaymentService cardPayments;
    private ChargebackService chargebacks;
    private SettlementService settlements;
    private ReconciliationService reconciliation;

    @BeforeEach
    void setUp() {
        clock = TestClock.businessDayMorning();
        ledger = new Ledger(clock);
        vault = new TokenVault();
        binTable = new BinTable();
        issuer = new IssuerSimulator(clock);
        payments = new PaymentRepository();
        bookkeeper = new PaymentBookkeeper(ledger);
        AuditTrail auditTrail = new AuditTrail(clock);
        Outbox outbox = new Outbox(clock);

        AcquirerProcessor processor = new SimulatedAcquirerProcessor("primary", "Primary",
                Set.of(CardScheme.VISA, CardScheme.MASTERCARD), Set.of(CURRENCY), issuer,
                FeeSchedule.standardEeaDebit(CURRENCY), Duration.ofDays(1), 0.95);
        PaymentRouter router = new PaymentRouter(List.of(processor));

        cardPayments = new CardPaymentService(clock, vault, binTable, new FraudEngine(clock),
                new ThreeDSecureService(5), router, payments, bookkeeper, issuer, outbox,
                auditTrail, new IdempotencyStore(clock));
        chargebacks = new ChargebackService(clock, payments, bookkeeper, auditTrail, outbox);
        settlements = new SettlementService(clock, payments, router, binTable, bookkeeper,
                chargebacks, auditTrail, outbox);
        reconciliation = new ReconciliationService(clock, payments, chargebacks, settlements,
                ledger, auditTrail, outbox);

        ChartOfAccounts.bootstrap(ledger, CURRENCY, List.of(MERCHANT));
    }

    private Money eur(String amount) {
        return Money.of(CURRENCY, amount);
    }

    /** A distinct Luhn-valid card with an issuer account, from its own address. */
    private CardDetails card(int index) {
        String prefix = GOOD_BIN + String.format(java.util.Locale.ROOT, "%09d", index);
        CardDetails details = new CardDetails(new Pan(prefix + Pan.luhnCheckDigit(prefix)),
                YearMonth.from(clock.instant().atZone(ZoneOffset.UTC)).plusYears(3),
                "TEST HOLDER", "123");
        String par = vault.tokenize(details, "fixture").par();
        if (issuer.account(par).isEmpty()) {
            issuer.openAccount(par, "EE", eur("5000.00"));
        }
        return details;
    }

    private PaymentResult capture(int index, String amount) {
        return cardPayments.authorize(new CardPaymentService.AuthorizeCommand(
                MERCHANT, "order-" + index, card(index), eur(amount), "5411",
                false, false, false, false, false,
                "198.51.100." + index, "EE", "EE", "key-" + index + "-" + amount, true));
    }

    // --- Batching and netting ---------------------------------------------

    @Test
    void theCutOffSweepsCapturesIntoABatchAndDecomposesTheTotals() {
        capture(1, "100.00");
        capture(2, "200.00");

        List<SettlementBatch> batches = settlements.runCutOff();

        assertEquals(1, batches.size());
        SettlementBatch batch = batches.get(0);
        assertEquals(SettlementBatch.Status.CLOSED, batch.status());
        assertEquals(2, batch.transactionCount());
        assertEquals(eur("300.00"), batch.grossSales());
        // 0.9% + 0.05 per transaction charged to the merchant.
        assertEquals(eur("2.80"), batch.merchantDiscount());
        // 0.2% interchange + 0.02 scheme fee per transaction, deducted at source.
        assertEquals(eur("0.64"), batch.interchangeAndSchemeFees());
    }

    @Test
    void fundingAndPayoutDifferByTheMargin() {
        capture(1, "100.00");
        SettlementBatch batch = settlements.runCutOff().get(0);

        // What arrives from the acquirer is gross less what they deduct at source.
        assertEquals(eur("99.78"), batch.acquirerFunding());
        // What the merchant is paid is gross less what we charge them.
        assertEquals(eur("99.05"), batch.netPayout());
        assertEquals(batch.acquirerFunding().minus(batch.netPayout()), batch.margin());
        assertEquals(eur("0.73"), batch.margin());
    }

    @Test
    void refundsAndDisputesReduceTheBatch() {
        PaymentResult payment = capture(1, "300.00");
        cardPayments.refund(payment.payment().paymentId(), eur("50.00"), "returned", "refund-key");
        settlements.runCutOff();

        PaymentResult second = capture(2, "200.00");
        chargebacks.receive(second.payment().paymentId(),
                ChargebackReason.GOODS_NOT_RECEIVED, eur("100.00"));

        SettlementBatch batch = settlements.runCutOff().get(0);

        assertEquals(eur("200.00"), batch.grossSales());
        assertEquals(eur("100.00"), batch.chargebacks());
        assertTrue(batch.chargebackFees().isPositive());
    }

    @Test
    void aBatchCanBeNegativeAndIsNotPaidOut() {
        PaymentResult payment = capture(1, "100.00");
        settlements.runCutOff();
        settlements.markFunded(settlements.all().get(0).batchId());
        settlements.payOut(settlements.all().get(0).batchId());

        // Now refund with nothing left on the merchant's balance.
        cardPayments.refund(payment.payment().paymentId(), eur("100.00"), "returned", "refund-key");
        SettlementBatch negative = settlements.runCutOff().get(0);

        assertTrue(negative.isNegative());
        settlements.markFunded(negative.batchId(), negative.acquirerFunding());
        settlements.payOut(negative.batchId());

        assertEquals(SettlementBatch.Status.PAID_OUT, negative.status());
        // Nothing was paid; the balance carries against the next batch.
        assertTrue(bookkeeper.merchantBalance(MERCHANT, CURRENCY).isNegative());
    }

    @Test
    void asecondCutOffDoesNotSweepTheSameCaptureTwice() {
        capture(1, "100.00");
        settlements.runCutOff();

        assertTrue(settlements.runCutOff().isEmpty(), "nothing new to sweep");
        assertEquals(1, settlements.all().size());
    }

    @Test
    void capturesAreStampedWithTheirBatch() {
        PaymentResult payment = capture(1, "100.00");
        assertFalse(payment.payment().captures().get(0).isSettled());

        SettlementBatch batch = settlements.runCutOff().get(0);

        assertTrue(payment.payment().captures().get(0).isSettled());
        assertEquals(batch.batchId(), payment.payment().captures().get(0).settlementBatchId());
    }

    @Test
    void theFundingDateReflectsTheProcessorsSchedule() {
        capture(1, "100.00");
        SettlementBatch batch = settlements.runCutOff().get(0);

        assertEquals(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(1),
                batch.expectedFundingDate(), "this processor settles in a day");
    }

    // --- Funding and payout -----------------------------------------------

    @Test
    void fundingTurnsTheReceivableIntoCash() {
        capture(1, "100.00");
        SettlementBatch batch = settlements.runCutOff().get(0);

        assertEquals(eur("99.78"),
                ledger.balanceOf(ChartOfAccounts.in(ChartOfAccounts.SCHEME_RECEIVABLE, CURRENCY)));
        assertEquals(eur("0.00"),
                ledger.balanceOf(ChartOfAccounts.in(ChartOfAccounts.BANK_OPERATING, CURRENCY)));

        settlements.markFunded(batch.batchId());

        assertEquals(eur("0.00"),
                ledger.balanceOf(ChartOfAccounts.in(ChartOfAccounts.SCHEME_RECEIVABLE, CURRENCY)));
        assertEquals(eur("99.78"),
                ledger.balanceOf(ChartOfAccounts.in(ChartOfAccounts.BANK_OPERATING, CURRENCY)));
        assertEquals(SettlementBatch.Status.FUNDED, batch.status());
        assertTrue(ledger.trialBalance().balances());
    }

    @Test
    void fundingRecordsTheAmountThatActuallyArrivedNotTheExpectedOne() {
        capture(1, "100.00");
        SettlementBatch batch = settlements.runCutOff().get(0);

        // Short-funded by a euro. Assuming the expected amount would hide the break.
        settlements.markFunded(batch.batchId(), eur("98.78"));

        assertEquals(eur("98.78"),
                ledger.balanceOf(ChartOfAccounts.in(ChartOfAccounts.BANK_OPERATING, CURRENCY)));
        assertEquals(eur("1.00"),
                ledger.balanceOf(ChartOfAccounts.in(ChartOfAccounts.SCHEME_RECEIVABLE, CURRENCY)),
                "the unfunded remainder stays visible as a receivable");
    }

    @Test
    void payoutDischargesTheMerchantLiabilityWithCash() {
        capture(1, "100.00");
        SettlementBatch batch = settlements.runCutOff().get(0);
        settlements.markFunded(batch.batchId());

        settlements.payOut(batch.batchId());

        assertEquals(SettlementBatch.Status.PAID_OUT, batch.status());
        assertEquals(eur("0.00"), bookkeeper.merchantBalance(MERCHANT, CURRENCY));
        // What is left in the bank is the margin.
        assertEquals(eur("0.73"),
                ledger.balanceOf(ChartOfAccounts.in(ChartOfAccounts.BANK_OPERATING, CURRENCY)));
        assertTrue(ledger.trialBalance().balances());
    }

    @Test
    void payoutBeforeFundingIsRefused() {
        capture(1, "100.00");
        SettlementBatch batch = settlements.runCutOff().get(0);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> settlements.payOut(batch.batchId()));

        assertTrue(failure.getMessage().contains("funds must arrive"), failure.getMessage());
    }

    @Test
    void aHeldBatchCannotBePaidOutUntilItIsReleased() {
        capture(1, "100.00");
        SettlementBatch batch = settlements.runCutOff().get(0);
        settlements.markFunded(batch.batchId());
        settlements.hold(batch.batchId(), "unexplained difference");

        assertThrows(IllegalStateException.class, () -> settlements.payOut(batch.batchId()));

        settlements.release(batch.batchId());
        settlements.payOut(batch.batchId());
        assertEquals(SettlementBatch.Status.PAID_OUT, batch.status());
    }

    @Test
    void anUnknownBatchIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> settlements.require("bat_nope"));
        assertTrue(settlements.find("bat_nope").isEmpty());
    }

    // --- Reconciliation: the clean case -----------------------------------

    private BankStatement statementFor(SettlementBatch batch, Money credited) {
        StatementEntry funding = new StatementEntry("NTRY-" + batch.batchId(), credited, true,
                batch.expectedFundingDate(), batch.expectedFundingDate(),
                batch.processorId(), null, "Card settlement " + batch.batchId(),
                batch.batchId(), "ESCT");
        Money opening = eur("10000.00");
        return new BankStatement("STMT-1", "EE717700771001735865",
                batch.expectedFundingDate(), batch.expectedFundingDate(),
                opening, opening.plus(credited), List.of(funding));
    }

    @Test
    void aMatchingBatchReconcilesClean() {
        capture(1, "100.00");
        capture(2, "50.00");
        SettlementBatch batch = settlements.runCutOff().get(0);
        ProcessorSettlementReport report = settlements.reportFor(batch.batchId());

        ReconciliationRun run = reconciliation.reconcile(batch.batchId(), report,
                statementFor(batch, report.netAmount()));

        assertTrue(run.isClean(), run.summary());
        assertEquals(2, run.matchedCount());
        assertEquals(eur("0.00"), run.unexplainedDifference());
        assertFalse(run.hasBlockingBreaks());
        assertEquals(SettlementBatch.Status.CLOSED, batch.status(), "not held");
    }

    @Test
    void aPaymentCapturedTwiceIsNotMistakenForADuplicatePresentment() {
        // A split shipment legitimately produces two sale lines. Keying on the payment
        // instead of the capture would make the second look like a duplicate.
        PaymentResult payment = cardPayments.authorize(new CardPaymentService.AuthorizeCommand(
                MERCHANT, "order-split", card(7), eur("300.00"), "5411",
                false, false, false, false, false,
                "198.51.100.7", "EE", "EE", "split-key", false));
        cardPayments.capture(payment.payment().paymentId(), eur("120.00"), "cap-1");
        cardPayments.capture(payment.payment().paymentId(), eur("180.00"), "cap-2");

        SettlementBatch batch = settlements.runCutOff().get(0);
        ProcessorSettlementReport report = settlements.reportFor(batch.batchId());

        ReconciliationRun run = reconciliation.reconcile(batch.batchId(), report,
                statementFor(batch, report.netAmount()));

        assertTrue(run.isClean(), run.summary());
        assertEquals(2, run.matchedCount(), "two captures, two matched lines");
    }

    @Test
    void aSubCentRoundingDifferenceIsMatchedWithinToleranceRatherThanRaised() {
        capture(1, "100.00");
        SettlementBatch batch = settlements.runCutOff().get(0);
        ProcessorSettlementReport original = settlements.reportFor(batch.batchId());
        ProcessorSettlementReport offByOneCent = adjustFirstLine(original, eur("0.01"));

        ReconciliationRun run = reconciliation.reconcile(batch.batchId(), offByOneCent,
                statementFor(batch, offByOneCent.netAmount()));

        assertTrue(run.isClean(), run.summary());
        assertEquals(1, run.withinToleranceCount(),
                "burying real breaks among one-cent items is how real breaks get missed");
    }

    // --- Reconciliation: each kind of break -------------------------------

    /** Copies a report with the first line's gross reduced. */
    private ProcessorSettlementReport adjustFirstLine(ProcessorSettlementReport report,
                                                      Money shortfall) {
        List<Line> lines = new ArrayList<>(report.lines());
        Line first = lines.get(0);
        lines.set(0, new Line(first.itemId(), first.processorReference(), first.paymentId(),
                first.type(), first.gross().minus(shortfall), first.fee(),
                first.transactionDate()));
        Money net = Money.zero(report.currency());
        for (Line line : lines) {
            net = net.plus(line.signedNet());
        }
        return new ProcessorSettlementReport(report.reportId(), report.processorId(),
                report.merchantId(), report.settlementDate(), report.currency(),
                report.grossAmount().minus(shortfall), report.feeAmount(), net, lines);
    }

    @Test
    void aShortPaidLineIsAnAmountMismatchAndHoldsTheBatch() {
        capture(1, "100.00");
        SettlementBatch batch = settlements.runCutOff().get(0);
        ProcessorSettlementReport shortPaid =
                adjustFirstLine(settlements.reportFor(batch.batchId()), eur("5.00"));

        ReconciliationRun run = reconciliation.reconcile(batch.batchId(), shortPaid, null);

        assertEquals(1, run.breaksOfType(BreakType.AMOUNT_MISMATCH).size());
        assertEquals(eur("5.00"), run.unexplainedDifference());
        assertTrue(run.hasBlockingBreaks());
        // A payout is irreversible, so the asymmetry favours holding.
        assertEquals(SettlementBatch.Status.HELD, batch.status());
        assertTrue(batch.holdReason().contains("break"), batch.holdReason());
    }

    @Test
    void aTransactionMissingFromTheProcessorsFileIsInformationalRatherThanBlocking() {
        capture(1, "100.00");
        capture(2, "50.00");
        SettlementBatch batch = settlements.runCutOff().get(0);
        ProcessorSettlementReport original = settlements.reportFor(batch.batchId());

        List<Line> onlyOne = List.of(original.lines().get(0));
        ProcessorSettlementReport partial = new ProcessorSettlementReport(
                original.reportId(), original.processorId(), original.merchantId(),
                original.settlementDate(), original.currency(),
                onlyOne.get(0).gross(), onlyOne.get(0).fee(), onlyOne.get(0).signedNet(), onlyOne);

        ReconciliationRun run = reconciliation.reconcile(batch.batchId(), partial, null);

        assertEquals(1, run.breaksOfType(BreakType.MISSING_EXTERNALLY).size());
        // Usually a timing difference that clears itself.
        assertEquals(ReconciliationBreak.Severity.INFORMATIONAL,
                run.breaks().get(0).severity(clock.instant()));
        assertFalse(run.hasBlockingBreaks());
    }

    @Test
    void aTransactionWeHaveNoRecordOfIsCriticalOnDayOne() {
        capture(1, "100.00");
        SettlementBatch batch = settlements.runCutOff().get(0);
        ProcessorSettlementReport original = settlements.reportFor(batch.batchId());

        List<Line> withGhost = new ArrayList<>(original.lines());
        withGhost.add(new Line("cap_ghost", "proc-ref-ghost", "pay_unknown", LineType.SALE,
                eur("999.00"), eur("2.00"), LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)));
        ProcessorSettlementReport inflated = new ProcessorSettlementReport(
                original.reportId(), original.processorId(), original.merchantId(),
                original.settlementDate(), original.currency(),
                original.grossAmount(), original.feeAmount(), original.netAmount(), withGhost);

        ReconciliationRun run = reconciliation.reconcile(batch.batchId(), inflated, null);

        // Money moved with nothing on our books to explain it.
        assertEquals(1, run.breaksOfType(BreakType.MISSING_INTERNALLY).size());
        assertEquals(ReconciliationBreak.Severity.CRITICAL,
                run.breaksOfType(BreakType.MISSING_INTERNALLY).get(0).severity(clock.instant()));
    }

    @Test
    void theSameLineTwiceIsADuplicatePresentment() {
        capture(1, "100.00");
        SettlementBatch batch = settlements.runCutOff().get(0);
        ProcessorSettlementReport original = settlements.reportFor(batch.batchId());

        List<Line> doubled = new ArrayList<>(original.lines());
        doubled.add(original.lines().get(0));
        ProcessorSettlementReport duplicated = new ProcessorSettlementReport(
                original.reportId(), original.processorId(), original.merchantId(),
                original.settlementDate(), original.currency(),
                original.grossAmount(), original.feeAmount(), original.netAmount(), doubled);

        ReconciliationRun run = reconciliation.reconcile(batch.batchId(), duplicated, null);

        assertEquals(1, run.breaksOfType(BreakType.DUPLICATE_EXTERNAL).size(),
                "reported twice would be funded twice");
        assertTrue(run.hasBlockingBreaks());
    }

    @Test
    void aFeePricedDifferentlyFromTheContractIsItsOwnBreak() {
        capture(1, "100.00");
        SettlementBatch batch = settlements.runCutOff().get(0);
        ProcessorSettlementReport original = settlements.reportFor(batch.batchId());

        List<Line> overcharged = new ArrayList<>(original.lines());
        Line first = overcharged.get(0);
        overcharged.set(0, new Line(first.itemId(), first.processorReference(), first.paymentId(),
                first.type(), first.gross(), first.fee().plus(eur("1.00")),
                first.transactionDate()));
        ProcessorSettlementReport report = new ProcessorSettlementReport(
                original.reportId(), original.processorId(), original.merchantId(),
                original.settlementDate(), original.currency(),
                original.grossAmount(), original.feeAmount(),
                overcharged.get(0).signedNet(), overcharged);

        ReconciliationRun run = reconciliation.reconcile(batch.batchId(), report, null);

        // Applied across every transaction, a mispriced fee is a large number.
        assertEquals(1, run.breaksOfType(BreakType.FEE_MISMATCH).size());
    }

    @Test
    void moneyReportedButNotArrivingIsAFundingMismatch() {
        capture(1, "100.00");
        SettlementBatch batch = settlements.runCutOff().get(0);
        ProcessorSettlementReport report = settlements.reportFor(batch.batchId());

        // The first leg compares two claims; only the statement proves cash moved.
        ReconciliationRun run = reconciliation.reconcile(batch.batchId(), report,
                statementFor(batch, report.netAmount().minus(eur("20.00"))));

        assertEquals(1, run.breaksOfType(BreakType.FUNDING_MISMATCH).size());
        assertTrue(run.hasBlockingBreaks());
    }

    @Test
    void noMatchingCreditAtAllIsAlsoAFundingMismatch() {
        capture(1, "100.00");
        SettlementBatch batch = settlements.runCutOff().get(0);
        ProcessorSettlementReport report = settlements.reportFor(batch.batchId());
        BankStatement empty = new BankStatement("STMT-EMPTY", "EE717700771001735865",
                batch.expectedFundingDate(), batch.expectedFundingDate(),
                eur("10000.00"), eur("10000.00"), List.of());

        ReconciliationRun run = reconciliation.reconcile(batch.batchId(), report, empty);

        assertEquals(1, run.breaksOfType(BreakType.FUNDING_MISMATCH).size());
        assertTrue(run.breaks().get(0).description().contains("no matching credit"),
                run.breaks().get(0).description());
    }

    @Test
    void aTruncatedProcessorFileIsDetectedBeforeItIsMatchedAgainst() {
        capture(1, "100.00");
        SettlementBatch batch = settlements.runCutOff().get(0);
        ProcessorSettlementReport original = settlements.reportFor(batch.batchId());

        // Header says one thing, the lines say another.
        ProcessorSettlementReport inconsistent = new ProcessorSettlementReport(
                original.reportId(), original.processorId(), original.merchantId(),
                original.settlementDate(), original.currency(),
                original.grossAmount(), original.feeAmount(),
                original.netAmount().plus(eur("100.00")), original.lines());

        assertFalse(inconsistent.isSelfConsistent());
        ReconciliationRun run = reconciliation.reconcile(batch.batchId(), inconsistent, null);

        assertEquals(1, run.breaksOfType(BreakType.SELF_INCONSISTENT_FILE).size(),
                "matching against a partial file manufactures breaks that do not exist");
    }

    @Test
    void anInconsistentBankStatementIsAlsoCaught() {
        capture(1, "100.00");
        SettlementBatch batch = settlements.runCutOff().get(0);
        ProcessorSettlementReport report = settlements.reportFor(batch.batchId());
        BankStatement wrongClosing = new BankStatement("STMT-BAD", "EE717700771001735865",
                batch.expectedFundingDate(), batch.expectedFundingDate(),
                eur("10000.00"), eur("99999.00"),
                statementFor(batch, report.netAmount()).entries());

        ReconciliationRun run = reconciliation.reconcile(batch.batchId(), report, wrongClosing);

        assertEquals(1, run.breaksOfType(BreakType.SELF_INCONSISTENT_FILE).size());
    }

    // --- Ledger self-check -------------------------------------------------

    @Test
    void theLedgerIsCheckedAgainstItselfOnEveryRun() {
        capture(1, "100.00");
        SettlementBatch batch = settlements.runCutOff().get(0);

        ReconciliationRun run = reconciliation.reconcile(batch.batchId(),
                settlements.reportFor(batch.batchId()), null);

        // A sound ledger contributes no breaks of its own.
        assertTrue(run.breaksOfType(BreakType.LEDGER_IMBALANCE).isEmpty());
        assertTrue(run.breaksOfType(BreakType.LEDGER_TAMPERED).isEmpty());
        assertTrue(reconciliation.checkLedger(clock.instant()).isEmpty());
    }

    // --- Break lifecycle ---------------------------------------------------

    @Test
    void theSameBreakFoundAgainAgesRatherThanDuplicating() {
        capture(1, "100.00");
        SettlementBatch batch = settlements.runCutOff().get(0);
        ProcessorSettlementReport shortPaid =
                adjustFirstLine(settlements.reportFor(batch.batchId()), eur("5.00"));

        reconciliation.reconcile(batch.batchId(), shortPaid, null);
        assertEquals(1, reconciliation.openBreaks().size());

        clock.advanceDays(10);
        reconciliation.reconcile(batch.batchId(), shortPaid, null);

        assertEquals(1, reconciliation.openBreaks().size(), "one problem, not two");
        ReconciliationBreak aged = reconciliation.openBreaks().get(0);
        assertEquals(10, aged.ageInDays(clock.instant()));
        assertEquals("8–30 days", aged.ageBucket(clock.instant()));
        assertEquals(ReconciliationBreak.Severity.HIGH, aged.severity(clock.instant()),
                "a break that has survived a week is no longer a timing difference");
    }

    @Test
    void breaksAreGroupedIntoAgeingBuckets() {
        capture(1, "100.00");
        SettlementBatch batch = settlements.runCutOff().get(0);
        reconciliation.reconcile(batch.batchId(),
                adjustFirstLine(settlements.reportFor(batch.batchId()), eur("5.00")), null);

        assertEquals(Set.of("same day"), reconciliation.agedBreaks().keySet());

        clock.advanceDays(40);
        assertEquals(Set.of("over 30 days"), reconciliation.agedBreaks().keySet());
        assertEquals(ReconciliationBreak.Severity.CRITICAL,
                reconciliation.openBreaks().get(0).severity(clock.instant()));
    }

    @Test
    void anUnexplainedDifferenceCanBeParkedInSuspenseSoTheBooksStillBalance() {
        capture(1, "100.00");
        SettlementBatch batch = settlements.runCutOff().get(0);
        reconciliation.reconcile(batch.batchId(),
                adjustFirstLine(settlements.reportFor(batch.batchId()), eur("5.00")), null);
        ReconciliationBreak open = reconciliation.openBreaks().get(0);

        reconciliation.writeOffToSuspense(open.breakKey(), "pending processor query");

        // Visible rather than absorbed: the suspense balance is itself a number
        // somebody has to explain.
        assertEquals(eur("5.00"),
                ledger.balanceOf(ChartOfAccounts.in(ChartOfAccounts.SUSPENSE, CURRENCY)));
        assertTrue(ledger.trialBalance().balances());
        assertTrue(reconciliation.openBreaks().isEmpty(), "closed, not deleted");
    }

    @Test
    void anUnexplainedReceiptIsParkedInSuspenseTheOtherWayRound() {
        capture(1, "100.00");
        SettlementBatch batch = settlements.runCutOff().get(0);
        // Over-paid rather than short-paid.
        reconciliation.reconcile(batch.batchId(),
                adjustFirstLine(settlements.reportFor(batch.batchId()), eur("-5.00")), null);
        ReconciliationBreak open = reconciliation.openBreaks().get(0);

        reconciliation.writeOffToSuspense(open.breakKey(), "unexplained receipt");

        assertEquals(eur("-5.00"),
                ledger.balanceOf(ChartOfAccounts.in(ChartOfAccounts.SUSPENSE, CURRENCY)));
        assertTrue(ledger.trialBalance().balances());
    }

    @Test
    void aBreakWithNoDifferenceCannotBeWrittenOff() {
        capture(1, "100.00");
        capture(2, "50.00");
        SettlementBatch batch = settlements.runCutOff().get(0);
        ProcessorSettlementReport original = settlements.reportFor(batch.batchId());
        List<Line> doubled = new ArrayList<>(original.lines());
        doubled.add(original.lines().get(0));
        reconciliation.reconcile(batch.batchId(), new ProcessorSettlementReport(
                original.reportId(), original.processorId(), original.merchantId(),
                original.settlementDate(), original.currency(), original.grossAmount(),
                original.feeAmount(), original.netAmount(), doubled), null);

        ReconciliationBreak duplicate = reconciliation.openBreaks().stream()
                .filter(item -> item.type() == BreakType.DUPLICATE_EXTERNAL)
                .findFirst().orElseThrow();

        assertThrows(IllegalStateException.class,
                () -> reconciliation.writeOffToSuspense(duplicate.breakKey(), "no"));
    }

    @Test
    void aBreakExplainedWithoutAPostingCanSimplyBeResolved() {
        capture(1, "100.00");
        capture(2, "50.00");
        SettlementBatch batch = settlements.runCutOff().get(0);
        ProcessorSettlementReport original = settlements.reportFor(batch.batchId());
        List<Line> onlyOne = List.of(original.lines().get(0));
        reconciliation.reconcile(batch.batchId(), new ProcessorSettlementReport(
                original.reportId(), original.processorId(), original.merchantId(),
                original.settlementDate(), original.currency(),
                onlyOne.get(0).gross(), onlyOne.get(0).fee(),
                onlyOne.get(0).signedNet(), onlyOne), null);

        ReconciliationBreak timing = reconciliation.openBreaks().get(0);
        assertTrue(reconciliation.resolve(timing.breakKey(), "arrived in the next day's file"));

        assertTrue(reconciliation.openBreaks().isEmpty());
        assertFalse(reconciliation.resolve("no-such-break", "x"));
    }

    @Test
    void writingOffAnUnknownBreakIsAnError() {
        assertThrows(IllegalArgumentException.class,
                () -> reconciliation.writeOffToSuspense("no-such-break", "x"));
    }

    @Test
    void runsAreRetainedSoTheHistoryCanBeInspected() {
        capture(1, "100.00");
        SettlementBatch batch = settlements.runCutOff().get(0);
        ProcessorSettlementReport report = settlements.reportFor(batch.batchId());

        reconciliation.reconcile(batch.batchId(), report, statementFor(batch, report.netAmount()));
        reconciliation.reconcile(batch.batchId(), report, statementFor(batch, report.netAmount()));

        assertEquals(2, reconciliation.runs().size());
        assertTrue(reconciliation.lastRun().isPresent());
        assertTrue(reconciliation.lastRun().orElseThrow().isClean());
    }

    // --- In-transit cross-check -------------------------------------------

    @Test
    void theInTransitBalanceIsCrossCheckedAgainstUnsettledTransfers() {
        assertTrue(reconciliation.reconcileInTransit(eur("100.00"), eur("100.00")).isEmpty());
        assertTrue(reconciliation.reconcileInTransit(eur("100.00"), eur("100.01")).isEmpty(),
                "within tolerance");

        ReconciliationBreak mismatch = reconciliation
                .reconcileInTransit(eur("100.00"), eur("90.00")).orElseThrow();

        assertEquals(BreakType.AMOUNT_MISMATCH, mismatch.type());
        assertEquals(eur("10.00"), mismatch.difference());
    }

    // --- Console helpers ---------------------------------------------------

    @Test
    void unsettledCaptureValueTracksWhatHasNotBeenSweptYet() {
        capture(1, "100.00");
        capture(2, "50.00");

        assertEquals(eur("150.00"), reconciliation.unsettledCaptureValue(CURRENCY));
        assertEquals(2, reconciliation.capturedNotSettled().size());

        settlements.runCutOff();

        assertEquals(eur("0.00"), reconciliation.unsettledCaptureValue(CURRENCY));
        assertTrue(reconciliation.capturedNotSettled().isEmpty());
    }

    @Test
    void openDisputesAndRefundedValueAreReported() {
        PaymentResult payment = capture(1, "200.00");
        cardPayments.refund(payment.payment().paymentId(), eur("30.00"), "returned", "r-1");
        chargebacks.receive(payment.payment().paymentId(),
                ChargebackReason.GOODS_NOT_RECEIVED, eur("50.00"));

        assertEquals(eur("30.00"), reconciliation.refundedValue(CURRENCY));
        assertEquals(1, reconciliation.openDisputes().size());
    }
}
