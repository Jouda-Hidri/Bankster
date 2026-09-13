package bankster.client.payments.regression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import bankster.client.payments.Money;
import bankster.client.payments.cards.CardDetails;
import bankster.client.payments.cards.CardPaymentService.PaymentResult;
import bankster.client.payments.cards.Chargeback;
import bankster.client.payments.cards.ChargebackReason;
import bankster.client.payments.cards.ChargebackStatus;
import bankster.client.payments.core.IdempotencyStore;
import bankster.client.payments.ledger.ChartOfAccounts;
import bankster.client.payments.rails.PaymentRail;
import bankster.client.payments.rails.RejectionReason;
import bankster.client.payments.rails.SepaPaymentService.InitiateTransferCommand;
import bankster.client.payments.rails.SepaPaymentService.TransferResult;
import bankster.client.payments.rails.SwiftService;
import bankster.client.payments.rails.PartyDetails;
import bankster.client.payments.rails.Iban;
import bankster.client.payments.rails.CreditTransfer;
import bankster.client.payments.recon.ReconciliationBreak;
import bankster.client.payments.recon.ReconciliationBreak.BreakType;
import bankster.client.payments.recon.ReconciliationBreak.Severity;
import bankster.client.payments.recon.ReconciliationRun;
import bankster.client.payments.risk.FraudEngine;
import bankster.client.payments.risk.RiskContext;
import bankster.client.payments.risk.RiskDecision;
import bankster.client.payments.settlement.ProcessorSettlementReport;
import bankster.client.payments.settlement.ProcessorSettlementReport.Line;
import bankster.client.payments.settlement.SettlementBatch;

/**
 * Regression tests for ten defects found while building and exercising this system.
 *
 * <p>These are organised by defect rather than by feature, and each one names what
 * reverting its fix would do. That is the property that makes a regression test worth
 * keeping: it is pinned to a specific mistake, so if someone reintroduces the mistake
 * the failure says which mistake it was rather than just that something broke.
 *
 * <p>Several overlap with the feature tests elsewhere, on purpose. The feature tests
 * describe how the system is meant to behave; these describe eight specific ways it
 * once did not, and they are the ones to read when deciding whether a refactor is
 * safe.
 *
 * <p>Every one of these was found by running the system rather than by reading it —
 * six by the start-up demo scenario producing a number that could not be right, two by
 * a test written for something else failing for an unrelated reason. That is worth
 * recording, because it is an argument for keeping the demo scenario in the build.
 */
class PaymentRegressionTest {

    private RegressionFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new RegressionFixture();
    }

    // =====================================================================
    @Nested
    @DisplayName("1. A challenged attempt was not recorded, so card testing was invisible")
    class ChallengedAttemptsCountTowardsVelocity {

        /**
         * The defect: {@code CardPaymentService} recorded an attempt with the fraud
         * engine when it was approved or declined, but not when it was challenged. A
         * card-testing burst scores into the CHALLENGE band on its first few attempts,
         * so every attempt was challenged, so nothing was ever recorded, so velocity
         * never accumulated. The attack suppressed exactly the evidence it generated.
         *
         * <p>Reverting: drop the {@code record(riskContext, CHALLENGED)} call from the
         * challenge branch of {@code doAuthorize}, or make {@code Outcome.CHALLENGED}
         * not count in the velocity predicates.
         */
        /**
         * The minimal statement: issuing a challenge must itself register the attempt.
         *
         * <p>This is the assertion that pins the defect down directly. Everything else in
         * this group is downstream of it.
         */
        @Test
        void issuingAChallengeRecordsTheAttempt() {
            assertEquals(0, fixture.fraudEngine.trackedAttempts());

            // Above the issuer's frictionless ceiling, so it is challenged and left
            // unanswered — exactly the state a card-testing probe is abandoned in.
            PaymentResult result = fixture.cardPayments.authorize(
                    fixture.auth(fixture.card(1, "5000.00"), "900.00", "198.51.100.1", false));

            assertTrue(result.requiresAuthentication(), result.message());
            assertEquals(1, fixture.fraudEngine.trackedAttempts(),
                    "an attempt that was challenged and never answered still happened");
        }

        /**
         * The behavioural consequence, on traffic that is genuinely challenged rather
         * than approved.
         *
         * <p>The geography mismatch matters: it puts the first probes in the middle band,
         * so they are stepped up instead of sailing through. Without it the probes are
         * approved, history accumulates through the approval path, and the test would
         * pass whether or not challenges are recorded — which is how this test failed to
         * catch its own bug on the first attempt.
         */
        @Test
        void aBurstOfChallengedProbesIsEventuallyRefusedOutright() {
            String attackerIp = "203.0.113.77";
            int declined = 0;
            int challenged = 0;
            int approved = 0;

            for (int i = 0; i < 8; i++) {
                CardDetails probe = fixture.card(500_000 + i, "3000.00");
                PaymentResult result = fixture.cardPayments.authorize(
                        fixture.authFrom(probe, "1.00", attackerIp, "NG", "EE", true));
                if (result.requiresAuthentication()) {
                    challenged++;
                } else if (result.approved()) {
                    approved++;
                } else {
                    declined++;
                }
            }

            assertEquals(0, approved, "a probe from a mismatched geography is never waved through");
            assertTrue(challenged > 0, "the first probes are stepped up before the score climbs");
            // Before the fix this was 8 challenged and 0 declined, for ever.
            assertTrue(declined > 0,
                    "velocity must accumulate across challenged attempts, or an attack that "
                            + "trips the challenge rule is never refused");
        }

        /**
         * The engine-level statement of the same rule.
         *
         * <p>Note what is and is not asserted. A history of purely <em>challenged</em>
         * attempts fires the card-testing rule and lands in the CHALLENGE band; it does
         * not reach DECLINE, because {@code repeated-declines} correctly stays silent —
         * a challenge is not a refusal. Before the fix the rule did not fire at all,
         * which is the difference this pins down.
         */
        @Test
        void challengedAttemptsFeedTheCardTestingRule() {
            String ip = "203.0.113.79";
            assertEquals(RiskDecision.APPROVE,
                    fixture.fraudEngine.evaluate(context("baseline", "PAR-X", ip, "1.00")).decision(),
                    "with no history this attempt is unremarkable");

            for (int i = 0; i < 5; i++) {
                fixture.fraudEngine.record(context("p" + i, "PAR-" + i, ip, "1.00"),
                        FraudEngine.Outcome.CHALLENGED);
            }

            var assessment = fixture.fraudEngine.evaluate(context("p9", "PAR-9", ip, "1.00"));
            assertTrue(assessment.reasons().stream()
                            .anyMatch(reason -> reason.rule().equals("card-testing")),
                    assessment.explain());
            assertTrue(assessment.score() >= 45, assessment.explain());
            assertEquals(RiskDecision.CHALLENGE, assessment.decision());
        }

        /**
         * The same property for the windowed per-card counter.
         *
         * <p>Separate from the card-testing assertion above because the two rules read
         * the history by different routes — card testing walks it directly, while the
         * per-card and decline-probing rules go through the windowed {@code countWhere}
         * helper. A change that excluded challenged attempts from that helper alone would
         * leave card testing working and silently break this, which is why both are
         * asserted.
         */
        @Test
        void challengedAttemptsFeedThePerCardVelocityRule() {
            for (int i = 0; i < 5; i++) {
                fixture.fraudEngine.record(context("p" + i, "PAR-1", "1.2.3." + i, "45.00"),
                        FraudEngine.Outcome.CHALLENGED);
            }

            var assessment = fixture.fraudEngine.evaluate(context("p9", "PAR-1", "1.2.3.9", "45.00"));

            assertTrue(assessment.reasons().stream()
                            .anyMatch(reason -> reason.rule().equals("card-velocity")),
                    assessment.explain());
        }

        /**
         * The counterpart constraint. Recording a challenge must not also double-count
         * it when the challenge is later resolved, or an ordinary shopper who passes one
         * is pushed towards the velocity thresholds.
         */
        @Test
        void resolvingAChallengeUpdatesTheAttemptRatherThanAddingASecond() {
            RiskContext attempt = context("p1", "PAR-1", "1.2.3.4", "45.00");

            fixture.fraudEngine.record(attempt, FraudEngine.Outcome.CHALLENGED);
            fixture.fraudEngine.record(attempt, FraudEngine.Outcome.APPROVED);

            assertEquals(1, fixture.fraudEngine.trackedAttempts(),
                    "one attempt with a final outcome, not two");
        }

        /** A challenge is not a refusal, so it must not feed the decline-probing rule. */
        @Test
        void challengedAttemptsAreNotCountedAsDeclines() {
            String ip = "203.0.113.80";
            for (int i = 0; i < 4; i++) {
                fixture.fraudEngine.record(context("p" + i, "PAR-1", ip, "45.00"),
                        FraudEngine.Outcome.CHALLENGED);
            }

            assertTrue(fixture.fraudEngine.evaluate(context("p9", "PAR-1", ip, "45.00"))
                            .reasons().stream()
                            .noneMatch(reason -> reason.rule().equals("repeated-declines")),
                    "a challenge is not a refusal");
        }

        private RiskContext context(String paymentId, String par, String ip, String amount) {
            return new RiskContext(paymentId, RegressionFixture.MERCHANT, par, "411111",
                    fixture.eur(amount), "EE", "EE", "EE", ip, "5411",
                    false, false, fixture.clock.instant());
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("2. Sanctions screening ran after Confirmation of Payee, masking hits")
    class SanctionsScreeningRunsBeforePayeeConfirmation {

        /**
         * The defect: {@code SepaPaymentService} checked the beneficiary name against the
         * account before screening against sanctions. A payment to a listed party whose
         * name also failed the payee check was rejected as {@code BE01} — a name mismatch
         * — and the sanctions hit was never screened for, so no report was filed. A match
         * masked by an earlier rejection is a match that was never reported, and the
         * reporting is the obligation.
         *
         * <p>Reverting: move the Confirmation of Payee block back above the AML block in
         * {@code doInitiate}.
         */
        @Test
        void aListedBeneficiaryIsBlockedForSanctionsNotForANameMismatch() {
            fixture.onboardAndFund("50000.00");
            // The account is held by someone else, so the payee check would also fail.
            fixture.sepa.registerAccountName(RegressionFixture.ING_IBAN, "Pieter de Vries");
            int reportsBefore = fixture.aml.reports().size();

            TransferResult result = fixture.sepa.initiate(InitiateTransferCommand.instant(
                    RegressionFixture.CUSTOMER, "Liis-Mari Männik", RegressionFixture.DEBTOR_IBAN,
                    "Ivan Petrov", RegressionFixture.ING_IBAN,
                    fixture.eur("5000.00"), "Consultancy", fixture.key()));

            assertFalse(result.accepted());
            assertEquals(RejectionReason.RR04.code(), result.transfer().rejectionCode(),
                    "a sanctions hit must not be reported as a name mismatch");
            assertNotEquals(RejectionReason.BE01.code(), result.transfer().rejectionCode());
            assertEquals(reportsBefore + 1, fixture.aml.reports().size(),
                    "the hit has to be screened for and filed even though the payment would "
                            + "have failed anyway");
        }

        /** The payee check still works when there is no sanctions hit to find first. */
        @Test
        void aGenuineNameMismatchIsStillReportedAsOne() {
            fixture.onboardAndFund("50000.00");
            fixture.sepa.registerAccountName(RegressionFixture.ING_IBAN, "Pieter de Vries");

            TransferResult result = fixture.sepa.initiate(InitiateTransferCommand.instant(
                    RegressionFixture.CUSTOMER, "Liis-Mari Männik", RegressionFixture.DEBTOR_IBAN,
                    "Somebody Else Entirely", RegressionFixture.ING_IBAN,
                    fixture.eur("100.00"), "Test", fixture.key()));

            assertFalse(result.accepted());
            assertEquals(RejectionReason.BE01.code(), result.transfer().rejectionCode());
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("3. Reconciliation keyed lines by payment, so split captures looked duplicated")
    class ReconciliationKeysOnTheIndividualItem {

        /**
         * The defect: {@code ReconciliationService} keyed both sides of the match on
         * {@code paymentId|type}. A payment captured in two shipments legitimately
         * produces two sale lines, which collapsed to one internal key and two external
         * ones — reported as a duplicate presentment. A break that is not a break is
         * worse than a missed one, because it trains people to ignore the report.
         *
         * <p>Reverting: change {@code keyOf(Line)} back to {@code paymentId + "|" + type}
         * and drop {@code itemId} from {@code ProcessorSettlementReport.Line}.
         */
        @Test
        void aPaymentCapturedTwiceReconcilesCleanly() {
            PaymentResult payment = fixture.cardPayments.authorize(
                    fixture.auth(fixture.card(1, "5000.00"), "300.00", "198.51.100.1", false));
            fixture.cardPayments.capture(payment.payment().paymentId(),
                    fixture.eur("120.00"), fixture.key());
            fixture.cardPayments.capture(payment.payment().paymentId(),
                    fixture.eur("180.00"), fixture.key());

            SettlementBatch batch = fixture.settlements.runCutOff().get(0);
            ProcessorSettlementReport report = fixture.settlements.reportFor(batch.batchId());

            ReconciliationRun run = fixture.reconciliation.reconcile(batch.batchId(), report, null);

            assertTrue(run.isClean(), run.summary());
            assertEquals(2, run.matchedCount(), "two captures, two matched lines");
            assertTrue(run.breaksOfType(BreakType.DUPLICATE_EXTERNAL).isEmpty(),
                    "a second shipment is not a second presentment");
            assertEquals(SettlementBatch.Status.CLOSED, batch.status(), "and so not held");
        }

        /**
         * The counterpart constraint: a genuine duplicate must still be caught. A key
         * that is simply unique per line would make this test pass while losing the
         * detection.
         */
        @Test
        void aGenuineDuplicateLineIsStillDetected() {
            fixture.cardPayments.authorize(
                    fixture.auth(fixture.card(1, "5000.00"), "100.00", "198.51.100.1", true));
            SettlementBatch batch = fixture.settlements.runCutOff().get(0);
            ProcessorSettlementReport original = fixture.settlements.reportFor(batch.batchId());

            List<Line> doubled = new ArrayList<>(original.lines());
            doubled.add(original.lines().get(0));
            ProcessorSettlementReport duplicated = new ProcessorSettlementReport(
                    original.reportId(), original.processorId(), original.merchantId(),
                    original.settlementDate(), original.currency(), original.grossAmount(),
                    original.feeAmount(), original.netAmount(), doubled);

            ReconciliationRun run =
                    fixture.reconciliation.reconcile(batch.batchId(), duplicated, null);

            assertEquals(1, run.breaksOfType(BreakType.DUPLICATE_EXTERNAL).size(),
                    "reported twice would be funded twice");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("4. A short-paid batch was not held, because amount mismatches rated LOW")
    class AmountMismatchesBlockPayoutOnDayOne {

        /**
         * The defect: {@code ReconciliationBreak#severity} put AMOUNT_MISMATCH on the
         * generic age ladder, so it was LOW on the day it was found, and
         * {@code hasBlockingBreaks} only counted HIGH and CRITICAL. A processor that
         * short-paid a batch produced a break that held nothing, and the batch was paid
         * out before anyone looked at it — the one outcome reconciliation exists to
         * prevent.
         *
         * <p>Reverting: remove the AMOUNT_MISMATCH/FEE_MISMATCH floor from
         * {@code severity}, or drop MEDIUM from {@code hasBlockingBreaks}.
         */
        @Test
        void aShortPaidLineHoldsTheBatchImmediately() {
            fixture.cardPayments.authorize(
                    fixture.auth(fixture.card(1, "5000.00"), "100.00", "198.51.100.1", true));
            SettlementBatch batch = fixture.settlements.runCutOff().get(0);
            ProcessorSettlementReport shortPaid = shortPayFirstLine(
                    fixture.settlements.reportFor(batch.batchId()), fixture.eur("5.00"));

            ReconciliationRun run =
                    fixture.reconciliation.reconcile(batch.batchId(), shortPaid, null);

            ReconciliationBreak mismatch = run.breaksOfType(BreakType.AMOUNT_MISMATCH).get(0);
            assertEquals(Severity.MEDIUM, mismatch.severity(fixture.clock.instant()),
                    "money that does not agree is serious on day one");
            assertTrue(run.hasBlockingBreaks());
            assertEquals(SettlementBatch.Status.HELD, batch.status(),
                    "a payout is irreversible, so the asymmetry favours holding");
        }

        /** And it still escalates with age rather than being capped at the floor. */
        @Test
        void anAgeingAmountMismatchEscalatesToCritical() {
            fixture.cardPayments.authorize(
                    fixture.auth(fixture.card(1, "5000.00"), "100.00", "198.51.100.1", true));
            SettlementBatch batch = fixture.settlements.runCutOff().get(0);
            ProcessorSettlementReport shortPaid = shortPayFirstLine(
                    fixture.settlements.reportFor(batch.batchId()), fixture.eur("5.00"));
            fixture.reconciliation.reconcile(batch.batchId(), shortPaid, null);

            ReconciliationBreak open = fixture.reconciliation.openBreaks().get(0);
            assertEquals(Severity.MEDIUM, open.severity(fixture.clock.instant()));

            fixture.clock.advanceDays(10);
            assertEquals(Severity.HIGH, open.severity(fixture.clock.instant()));

            fixture.clock.advanceDays(25);
            assertEquals(Severity.CRITICAL, open.severity(fixture.clock.instant()),
                    "a break open for a month is a control failure");
        }

        /**
         * The counterpart constraint: a timing difference must not hold anything, or
         * every batch is held and the control becomes noise.
         */
        @Test
        void aTimingDifferenceDoesNotHoldTheBatch() {
            fixture.cardPayments.authorize(
                    fixture.auth(fixture.card(1, "5000.00"), "100.00", "198.51.100.1", true));
            fixture.cardPayments.authorize(
                    fixture.auth(fixture.card(2, "5000.00"), "50.00", "198.51.100.2", true));
            SettlementBatch batch = fixture.settlements.runCutOff().get(0);
            ProcessorSettlementReport original = fixture.settlements.reportFor(batch.batchId());

            List<Line> onlyOne = List.of(original.lines().get(0));
            ProcessorSettlementReport partial = new ProcessorSettlementReport(
                    original.reportId(), original.processorId(), original.merchantId(),
                    original.settlementDate(), original.currency(),
                    onlyOne.get(0).gross(), onlyOne.get(0).fee(),
                    onlyOne.get(0).signedNet(), onlyOne);

            ReconciliationRun run =
                    fixture.reconciliation.reconcile(batch.batchId(), partial, null);

            assertEquals(Severity.INFORMATIONAL,
                    run.breaksOfType(BreakType.MISSING_EXTERNALLY).get(0)
                            .severity(fixture.clock.instant()));
            assertFalse(run.hasBlockingBreaks());
            assertNotEquals(SettlementBatch.Status.HELD, batch.status());
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("5. Cross-border payments never reached the ledger")
    class CrossBorderPaymentsAreBooked {

        /**
         * The defect: {@code SwiftService#send} debited the correspondent nostro and
         * emitted an MT103, but booked nothing. The payer's balance was never reduced, so
         * the console showed a customer's funds as though the payment had not happened,
         * and the money existed in neither the customer's balance nor an in-transit
         * liability.
         *
         * <p>Reverting: remove the {@code bookkeeper.recordInstruction} call from
         * {@code send}.
         */
        @Test
        void sendingDebitsThePayerAndCreatesAnInTransitLiability() {
            fixture.onboardAndFund("100000.00");
            Money fundsBefore = fixture.customerFunds();
            int entriesBefore = fixture.ledger.size();

            SwiftService.CrossBorderResult result = fixture.swift.send(toUnitedStates("5000.00"));

            assertTrue(result.accepted(), result.message());
            assertTrue(fixture.ledger.size() > entriesBefore, "the payment must be booked");
            assertEquals(fundsBefore.minus(result.quote().amountDebited()),
                    fixture.customerFunds(), "the payer's balance has to go down");
            assertEquals(fixture.eur("5000.00"),
                    fixture.transferBookkeeper.paymentsInTransit(RegressionFixture.CURRENCY),
                    "and the principal sits in transit until the far end confirms");
            assertTrue(fixture.ledger.trialBalance().balances());
        }

        /** Confirmation clears the liability, closing the loop. */
        @Test
        void confirmationClearsTheInTransitLiability() {
            fixture.onboardAndFund("100000.00");
            String reference = fixture.swift.send(toUnitedStates("5000.00")).reference();

            fixture.swift.confirmArrival(reference, fixture.eur("5000.00"));

            assertEquals(fixture.eur("0.00"),
                    fixture.transferBookkeeper.paymentsInTransit(RegressionFixture.CURRENCY));
            assertTrue(fixture.ledger.trialBalance().balances());
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("6. A won dispute left a phantom expense and over-credited the merchant")
    class WinningADisputeRestoresExactlyWhatWasTaken {

        /**
         * The defect: when a merchant's balance could not cover a claw-back, the shortfall
         * was booked to chargeback losses. On a successful representment the merchant was
         * credited with the full disputed amount — more than had actually been taken from
         * them — and the absorbed loss was never reversed. The merchant ended up better
         * off than before the dispute and a phantom expense stayed on the books.
         *
         * <p>Reverting: make {@code recordRepresentmentWon} credit the merchant with the
         * disputed amount and drop the loss-reversal and fee-refund postings.
         */
        @Test
        void aWonDisputeAfterAnAbsorbedLossLeavesTheBooksWhereTheyStarted() {
            PaymentResult payment = fixture.authenticatedCapture(1, "900.00");
            // Drain the balance so the claw-back cannot be fully covered.
            fixture.cardPayments.refund(payment.payment().paymentId(),
                    fixture.eur("880.00"), "mostly refunded", fixture.key());

            Money balanceBeforeDispute = fixture.merchantBalance();
            Chargeback dispute = fixture.chargebacks.receive(payment.payment().paymentId(),
                    ChargebackReason.FRAUD_CARD_ABSENT, fixture.eur("20.00"));
            assertTrue(dispute.hasAbsorbedLoss(), "this scenario needs an absorbed shortfall");

            Chargeback won = fixture.chargebacks.represent(dispute.caseId(),
                    List.of("ECI 05 authentication record"));

            assertEquals(ChargebackStatus.WON, won.status());
            assertEquals(balanceBeforeDispute, fixture.merchantBalance(),
                    "restore exactly what was taken, not the headline disputed amount");
            assertEquals(fixture.eur("0.00"),
                    fixture.balanceOf(ChartOfAccounts.EXPENSE_CHARGEBACK_LOSSES),
                    "no phantom expense may survive a defended dispute");
            assertTrue(fixture.ledger.trialBalance().balances());
        }

        /** The fully-covered case has to come out clean too. */
        @Test
        void aWonDisputeWithFullCoverageAlsoNetsToNothing() {
            // Prior volume so the claw-back and its fee are fully covered.
            fixture.cardPayments.authorize(
                    fixture.auth(fixture.card(9, "5000.00"), "400.00", "198.51.100.9", true));
            PaymentResult payment = fixture.authenticatedCapture(1, "900.00");

            Money balanceBefore = fixture.merchantBalance();
            Money receivableBefore = fixture.balanceOf(ChartOfAccounts.SCHEME_RECEIVABLE);
            Chargeback dispute = fixture.chargebacks.receive(payment.payment().paymentId(),
                    ChargebackReason.FRAUD_CARD_ABSENT, fixture.eur("900.00"));
            assertFalse(dispute.hasAbsorbedLoss());

            fixture.chargebacks.represent(dispute.caseId(), List.of("ECI 05 record"));

            assertEquals(balanceBefore, fixture.merchantBalance());
            assertEquals(receivableBefore, fixture.balanceOf(ChartOfAccounts.SCHEME_RECEIVABLE));
            assertEquals(fixture.eur("0.00"),
                    fixture.balanceOf(ChartOfAccounts.REVENUE_CHARGEBACK_FEES),
                    "the handling fee is refunded when the merchant wins");
            assertTrue(fixture.ledger.trialBalance().balances());
        }

        /**
         * The counterpart constraint: recovery is applied to the disputed amount before
         * the fee. We would rather recover the money we owe the issuer than the fee we
         * would like to earn.
         */
        @Test
        void recoveryIsAppliedToTheDisputedAmountBeforeTheFee() {
            PaymentResult payment = fixture.cardPayments.authorize(
                    fixture.auth(fixture.card(1, "5000.00"), "100.00", "198.51.100.1", true));
            Money available = fixture.merchantBalance();
            assertTrue(available.isLessThan(fixture.eur("115.00")),
                    "this scenario needs a balance that cannot cover amount plus fee");

            Chargeback dispute = fixture.chargebacks.receive(payment.payment().paymentId(),
                    ChargebackReason.GOODS_NOT_RECEIVED, fixture.eur("100.00"));

            assertEquals(available, dispute.recoveredFromMerchant());
            assertEquals(fixture.eur("0.00"), dispute.recoveredFee());
            assertEquals(fixture.eur("100.00").minus(available), dispute.lossAbsorbed());
            assertEquals(dispute.lossAbsorbed(),
                    fixture.balanceOf(ChartOfAccounts.EXPENSE_CHARGEBACK_LOSSES));
            assertTrue(fixture.ledger.trialBalance().balances());
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("7. A transfer instructed after the cut-off was given a D+1 value date")
    class PostCutOffTransfersLoseAWholeCycle {

        /**
         * The defect: {@code SepaRouter#expectedSettlement} computed the value date as the
         * next settlement day after <em>today</em> whether or not the cut-off had passed.
         * An instruction at 16:00 misses today's clearing cycle, so it executes tomorrow
         * and settles the day after — D+2, not D+1. Value dates are what interest and
         * liquidity are computed on, so being a day out is not cosmetic.
         *
         * <p>Reverting: replace {@code executionDate()} with
         * {@code nextSettlementDay(thisOrNextSettlementDay(today))}.
         */
        @Test
        void afterTheCutOffTheValueDateIsTwoDaysOut() {
            // Tuesday 16:00 Brussels, past the 15:00 CET cut-off.
            fixture.clock.set("2026-09-15T14:00:00Z");

            assertEquals(LocalDate.of(2026, 9, 16), fixture.sepaRouter.executionDate(),
                    "missing the cut-off costs a whole cycle");
            assertEquals(LocalDate.of(2026, 9, 17), valueDate());
        }

        @Test
        void beforeTheCutOffTheValueDateIsOneDayOut() {
            // Tuesday 11:00 Brussels, inside the cut-off.
            fixture.clock.set("2026-09-15T09:00:00Z");

            assertEquals(LocalDate.of(2026, 9, 15), fixture.sepaRouter.executionDate());
            assertEquals(LocalDate.of(2026, 9, 16), valueDate());
        }

        @Test
        void aFridayAfternoonInstructionWaitsForMondaysCycle() {
            // Friday 16:00 Brussels, past the cut-off.
            fixture.clock.set("2026-09-11T14:00:00Z");

            assertEquals(LocalDate.of(2026, 9, 14), fixture.sepaRouter.executionDate());
            assertEquals(LocalDate.of(2026, 9, 15), valueDate());
        }

        @Test
        void aWeekendInstructionEntersMondaysCycleNotTuesdays() {
            fixture.clock.set("2026-09-12T10:00:00Z"); // Saturday

            assertEquals(LocalDate.of(2026, 9, 14), fixture.sepaRouter.executionDate());
            assertEquals(LocalDate.of(2026, 9, 15), valueDate());
        }

        private LocalDate valueDate() {
            return LocalDate.ofInstant(
                    fixture.sepaRouter.expectedSettlement(PaymentRail.SEPA_SCT),
                    ZoneId.of("Europe/Brussels"));
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("8. SWIFT had no funds check, so a payer could overdraw")
    class CrossBorderPaymentsCheckTheFundsFirst {

        /**
         * The defect: {@code SepaPaymentService} checked the payer's balance before
         * booking; {@code SwiftService} did not. A cross-border payment larger than the
         * balance was accepted and booked, leaving the customer's safeguarded-funds
         * account negative — a liability account showing that the institution owed the
         * customer less than nothing.
         *
         * <p>Reverting: remove the available-funds check from {@code SwiftService#send}.
         */
        @Test
        void aPayerWithoutTheFundsIsRefusedBeforeAnythingIsBooked() {
            fixture.onboardAndFund("1000.00");
            int entriesBefore = fixture.ledger.size();

            SwiftService.CrossBorderResult result = fixture.swift.send(toUnitedStates("5000.00"));

            assertFalse(result.accepted());
            assertTrue(result.message().contains("is short of"), result.message());
            assertEquals(entriesBefore, fixture.ledger.size(), "a refused payment books nothing");
            assertFalse(fixture.customerFunds().isNegative(),
                    "a safeguarded-funds balance must never go negative");
            assertEquals(fixture.eur("1000.00"), fixture.customerFunds());
        }

        /** The same guarantee on the SEPA side, so the two rails agree. */
        @Test
        void theSepaRailRefusesAnUnfundedTransferToo() {
            fixture.onboardAndFund("1000.00");

            TransferResult result = fixture.sepa.initiate(InitiateTransferCommand.instant(
                    RegressionFixture.CUSTOMER, "Liis-Mari Männik", RegressionFixture.DEBTOR_IBAN,
                    "Klaus Weber", RegressionFixture.REACHABLE_IBAN,
                    fixture.eur("5000.00"), "Too much", fixture.key()));

            assertFalse(result.accepted());
            assertEquals(RejectionReason.AM04.code(), result.transfer().rejectionCode());
            assertFalse(fixture.customerFunds().isNegative());
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("9. A negative settlement could not be booked at all")
    class NegativeSettlementsAreBookable {

        /**
         * The defect: {@code recordSettlementReceipt} always debited the bank and credited
         * the receivable. When a batch's refunds exceed its sales the net is negative — we
         * pay the acquirer rather than receiving from them — and the posting was rejected,
         * because a posting amount is always positive. A merchant having a quiet day with
         * a large refund made the settlement run throw.
         *
         * <p>Reverting: remove the sign branch from {@code recordSettlementReceipt}.
         */
        @Test
        void aBatchWhoseRefundsExceedItsSalesStillSettles() {
            PaymentResult payment = fixture.cardPayments.authorize(
                    fixture.auth(fixture.card(1, "5000.00"), "100.00", "198.51.100.1", true));
            SettlementBatch first = fixture.settlements.runCutOff().get(0);
            fixture.settlements.markFunded(first.batchId());
            fixture.settlements.payOut(first.batchId());

            // Refund with nothing left on the merchant's balance.
            fixture.cardPayments.refund(payment.payment().paymentId(),
                    fixture.eur("100.00"), "returned", fixture.key());
            SettlementBatch negative = fixture.settlements.runCutOff().get(0);
            assertTrue(negative.isNegative());

            // Before the fix this threw from the posting validation.
            fixture.settlements.markFunded(negative.batchId(), negative.acquirerFunding());

            assertEquals(SettlementBatch.Status.FUNDED, negative.status());
            assertTrue(fixture.ledger.trialBalance().balances());
        }

        /** Directly, so the intent is unambiguous. */
        @Test
        void theDirectionIsDerivedFromTheSign() {
            fixture.bookkeeper.recordSettlementReceipt("bat-in", fixture.eur("100.00"));
            assertEquals(fixture.eur("100.00"),
                    fixture.balanceOf(ChartOfAccounts.BANK_OPERATING));

            fixture.bookkeeper.recordSettlementReceipt("bat-out", fixture.eur("-30.00"));
            assertEquals(fixture.eur("70.00"),
                    fixture.balanceOf(ChartOfAccounts.BANK_OPERATING));
            assertTrue(fixture.ledger.trialBalance().balances());
        }

        /** A zero net is a caller error rather than an empty entry. */
        @Test
        void aZeroSettlementIsRejectedRatherThanPostedEmpty() {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> fixture.bookkeeper.recordSettlementReceipt("bat-0", fixture.eur("0.00")));
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("10. hasCompleted ignored the retention window execute enforces")
    class IdempotencyQueriesRespectRetention {

        /**
         * The defect: {@code IdempotencyStore#execute} purged expired records before
         * looking, but {@code hasCompleted} read the map directly. The two disagreed: a
         * caller could be told a key was still held while {@code execute} would happily
         * re-run it. A query that contradicts the thing it is querying is worse than no
         * query at all.
         *
         * <p>Reverting: remove the {@code purgeExpired()} call from
         * {@code hasCompleted}.
         */
        @Test
        void anExpiredKeyIsReportedAsAvailableAndIsAvailable() {
            IdempotencyStore store = new IdempotencyStore(fixture.clock);
            store.execute("key-1", "fingerprint", () -> "first");
            assertTrue(store.hasCompleted("key-1"));

            fixture.clock.advance(IdempotencyStore.RETENTION.plus(Duration.ofMinutes(1)));

            assertFalse(store.hasCompleted("key-1"), "the query must agree with execute");
            IdempotencyStore.Outcome<String> afterExpiry =
                    store.execute("key-1", "fingerprint", () -> "second");
            assertEquals("second", afterExpiry.value());
            assertFalse(afterExpiry.replayed());
        }

        /** Inside the window the two still agree the other way. */
        @Test
        void aLiveKeyIsReportedAsHeldAndIsReplayed() {
            IdempotencyStore store = new IdempotencyStore(fixture.clock);
            store.execute("key-1", "fingerprint", () -> "first");

            fixture.clock.advance(Duration.ofHours(1));

            assertTrue(store.hasCompleted("key-1"));
            assertTrue(store.execute("key-1", "fingerprint", () -> "second").replayed());
        }
    }

    // =====================================================================
    // Shared helpers

    /** Copies a report with the first line's gross reduced, as a short-paying processor would. */
    private ProcessorSettlementReport shortPayFirstLine(ProcessorSettlementReport report,
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

    private SwiftService.CrossBorderInstruction toUnitedStates(String amount) {
        return new SwiftService.CrossBorderInstruction(RegressionFixture.CUSTOMER,
                PartyDetails.of("Liis-Mari Männik", new Iban(RegressionFixture.DEBTOR_IBAN)),
                PartyDetails.of("Jane Roe", new Iban(RegressionFixture.REACHABLE_IBAN)),
                "0123456789", "US",
                fixture.eur(amount), "USD", "Invoice US-4471",
                CreditTransfer.ChargeBearer.SHAR);
    }
}
