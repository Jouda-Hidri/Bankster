package bankster.client.payments.cards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import bankster.client.payments.Money;
import bankster.client.payments.cards.CardPaymentService.PaymentResult;
import bankster.client.payments.ledger.ChartOfAccounts;

/** The dispute lifecycle, and the accounting that has to survive it. */
class ChargebackServiceTest {

    private static final String GOOD_BIN = "400000";

    private CardFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new CardFixture();
    }

    /** A captured payment, authenticated or not, to dispute. */
    private PaymentResult capturedPayment(String amount, boolean authenticated) {
        CardDetails card = fixture.cardOn(GOOD_BIN, "5000.00");
        if (authenticated) {
            // Above the frictionless ceiling, so it is challenged and then passed —
            // which is what produces the liability shift.
            PaymentResult initial = fixture.service.authorize(fixture.authOnly(card, amount));
            assertTrue(initial.requiresAuthentication(), "expected a challenge at " + amount);
            PaymentResult authorized = fixture.service.completeAuthentication(
                    initial.payment().paymentId(), true);
            fixture.service.captureAll(authorized.payment().paymentId(), fixture.key());
            return authorized;
        }
        return fixture.service.authorize(fixture.authAndCapture(card, amount));
    }

    // --- Receiving a dispute ---------------------------------------------

    @Test
    void receivingADisputeClawsTheFundsBackImmediately() {
        fixture.warmUpMerchantBalance();
        PaymentResult payment = capturedPayment("100.00", false);
        Money beforeDispute = fixture.merchantBalance();

        Chargeback dispute = fixture.chargebacks.receive(payment.payment().paymentId(),
                ChargebackReason.GOODS_NOT_RECEIVED, fixture.eur("100.00"));

        assertEquals(ChargebackStatus.RECEIVED, dispute.status());
        assertEquals(PaymentStatus.CHARGED_BACK, payment.payment().status());
        // The issuer takes the money first and the argument happens afterwards.
        assertEquals(beforeDispute.minus(fixture.eur("115.00")), fixture.merchantBalance(),
                "100.00 disputed plus the 15.00 handling fee");
        assertEquals(fixture.eur("15.00"), dispute.recoveredFee());
        assertEquals(fixture.eur("0.00"), dispute.lossAbsorbed());
        assertTrue(fixture.ledger.trialBalance().balances());
    }

    @Test
    void theRepresentmentDeadlineIsRecordedOnTheCase() {
        PaymentResult payment = capturedPayment("100.00", false);

        Chargeback dispute = fixture.chargebacks.receive(payment.payment().paymentId(),
                ChargebackReason.GOODS_NOT_RECEIVED, fixture.eur("100.00"));

        assertEquals(fixture.clock.instant().plus(ChargebackService.REPRESENTMENT_WINDOW),
                dispute.representmentDeadline());
    }

    @Test
    void disputingMoreThanWasCapturedIsRefused() {
        PaymentResult payment = capturedPayment("50.00", false);

        assertThrows(IllegalArgumentException.class, () -> fixture.chargebacks.receive(
                payment.payment().paymentId(), ChargebackReason.GOODS_NOT_RECEIVED,
                fixture.eur("80.00")));
    }

    @Test
    void anUncapturedPaymentCannotBeDisputed() {
        CardDetails card = fixture.cardOn(GOOD_BIN, "1000.00");
        PaymentResult authorized = fixture.service.authorize(fixture.authOnly(card, "50.00"));

        assertThrows(IllegalStateException.class, () -> fixture.chargebacks.receive(
                authorized.payment().paymentId(), ChargebackReason.GOODS_NOT_RECEIVED,
                fixture.eur("50.00")));
    }

    @Test
    void theIssuersFilingWindowCloses() {
        PaymentResult payment = capturedPayment("100.00", false);
        fixture.clock.advance(ChargebackService.ISSUER_FILING_WINDOW.plus(Duration.ofDays(1)));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> fixture.chargebacks.receive(payment.payment().paymentId(),
                        ChargebackReason.GOODS_NOT_RECEIVED, fixture.eur("100.00")));

        assertTrue(failure.getMessage().contains("120-day"), failure.getMessage());
    }

    // --- Liability shift --------------------------------------------------

    @Test
    void aFraudDisputeOnAnAuthenticatedPaymentIsWonOutright() {
        PaymentResult payment = capturedPayment("900.00", true);
        assertTrue(payment.payment().authentication().liabilityShift());

        Chargeback dispute = fixture.chargebacks.receive(payment.payment().paymentId(),
                ChargebackReason.FRAUD_CARD_ABSENT, fixture.eur("900.00"));
        Chargeback defended = fixture.chargebacks.represent(dispute.caseId(),
                List.of("ECI 05 authentication record", "DS transaction id"));

        assertEquals(ChargebackStatus.WON, defended.status());
        assertTrue(defended.outcomeReason().contains("liability shift"), defended.outcomeReason());
    }

    @Test
    void aWonDisputeLeavesTheBooksExactlyWhereTheyStarted() {
        PaymentResult payment = capturedPayment("900.00", true);
        Money beforeDispute = fixture.merchantBalance();
        Money receivableBefore = fixture.balanceOf(ChartOfAccounts.SCHEME_RECEIVABLE);

        Chargeback dispute = fixture.chargebacks.receive(payment.payment().paymentId(),
                ChargebackReason.FRAUD_CARD_ABSENT, fixture.eur("900.00"));
        fixture.chargebacks.represent(dispute.caseId(), List.of("ECI 05 authentication record"));

        assertEquals(beforeDispute, fixture.merchantBalance(),
                "the merchant must not end up better or worse off than before the dispute");
        assertEquals(receivableBefore, fixture.balanceOf(ChartOfAccounts.SCHEME_RECEIVABLE));
        assertEquals(fixture.eur("0.00"),
                fixture.balanceOf(ChartOfAccounts.EXPENSE_CHARGEBACK_LOSSES),
                "no phantom expense may survive a defended dispute");
        assertEquals(fixture.eur("0.00"),
                fixture.balanceOf(ChartOfAccounts.REVENUE_CHARGEBACK_FEES),
                "the handling fee is refunded when the merchant wins");
        assertTrue(fixture.ledger.trialBalance().balances());
    }

    @Test
    void authenticationIsNoDefenceAgainstAServiceDispute() {
        PaymentResult payment = capturedPayment("900.00", true);

        Chargeback dispute = fixture.chargebacks.receive(payment.payment().paymentId(),
                ChargebackReason.GOODS_NOT_RECEIVED, fixture.eur("900.00"));
        Chargeback defended = fixture.chargebacks.represent(dispute.caseId(),
                List.of("tracked delivery confirmation"));

        assertEquals(ChargebackStatus.REPRESENTED, defended.status(),
                "no amount of authentication proves a parcel arrived — the issuer decides");
    }

    @Test
    void aFraudDisputeOnAnExemptedPaymentIsNotAutomaticallyWon() {
        // An SCA exemption keeps liability with us, which is its cost.
        PaymentResult payment = capturedPayment("18.50", false);
        assertFalse(payment.payment().authentication().liabilityShift());

        Chargeback dispute = fixture.chargebacks.receive(payment.payment().paymentId(),
                ChargebackReason.FRAUD_CARD_ABSENT, fixture.eur("18.50"));
        Chargeback defended = fixture.chargebacks.represent(dispute.caseId(), List.of("order record"));

        assertEquals(ChargebackStatus.REPRESENTED, defended.status());
    }

    @Test
    void reasonCodesSayWhatEvidenceIsExpected() {
        assertTrue(ChargebackReason.FRAUD_CARD_ABSENT.evidenceExpected().contains("CAVV"));
        assertTrue(ChargebackReason.GOODS_NOT_RECEIVED.evidenceExpected().contains("delivery"));
        assertTrue(ChargebackReason.CREDIT_NOT_PROCESSED.evidenceExpected().contains("refund"));
        assertTrue(ChargebackReason.FRAUD_CARD_ABSENT.isFraud());
        assertFalse(ChargebackReason.GOODS_NOT_RECEIVED.isFraud());
    }

    // --- Escalation and outcomes -----------------------------------------

    @Test
    void acceptingTheDisputeClosesItAsALoss() {
        PaymentResult payment = capturedPayment("100.00", false);
        Chargeback dispute = fixture.chargebacks.receive(payment.payment().paymentId(),
                ChargebackReason.GOODS_NOT_RECEIVED, fixture.eur("100.00"));

        Chargeback accepted = fixture.chargebacks.accept(dispute.caseId());

        assertEquals(ChargebackStatus.LOST, accepted.status());
        assertTrue(accepted.status().isResolved());
    }

    @Test
    void anUndefendedDisputeExpiresIntoALoss() {
        PaymentResult payment = capturedPayment("100.00", false);
        Chargeback dispute = fixture.chargebacks.receive(payment.payment().paymentId(),
                ChargebackReason.GOODS_NOT_RECEIVED, fixture.eur("100.00"));

        fixture.clock.advance(ChargebackService.REPRESENTMENT_WINDOW.plus(Duration.ofDays(1)));
        assertEquals(1, fixture.chargebacks.expireOverdueCases());

        assertEquals(ChargebackStatus.LOST, fixture.chargebacks.find(dispute.caseId())
                .orElseThrow().status());
    }

    @Test
    void defendingAfterTheDeadlineIsTooLate() {
        PaymentResult payment = capturedPayment("100.00", false);
        Chargeback dispute = fixture.chargebacks.receive(payment.payment().paymentId(),
                ChargebackReason.GOODS_NOT_RECEIVED, fixture.eur("100.00"));

        fixture.clock.advance(ChargebackService.REPRESENTMENT_WINDOW.plus(Duration.ofDays(1)));
        Chargeback tooLate = fixture.chargebacks.represent(dispute.caseId(), List.of("evidence"));

        assertEquals(ChargebackStatus.LOST, tooLate.status(),
                "missing the window loses the case regardless of the merits");
    }

    @Test
    void theFullEscalationPathEndsInAnArbitrationRuling() {
        fixture.warmUpMerchantBalance();
        PaymentResult payment = capturedPayment("100.00", false);
        Chargeback dispute = fixture.chargebacks.receive(payment.payment().paymentId(),
                ChargebackReason.GOODS_NOT_RECEIVED, fixture.eur("100.00"));

        fixture.chargebacks.represent(dispute.caseId(), List.of("delivery confirmation"));
        fixture.chargebacks.escalateToPreArbitration(dispute.caseId(), "issuer rejects the evidence");
        fixture.chargebacks.escalateToArbitration(dispute.caseId());
        Chargeback ruled = fixture.chargebacks.ruleArbitration(dispute.caseId(), false,
                "delivery could not be tied to the cardholder's address");

        assertEquals(ChargebackStatus.LOST, ruled.status());
        assertTrue(fixture.ledger.trialBalance().balances());
        // The arbitration fee is the scheme's, not ours: only the handling fee shows
        // up as revenue, however the case ends.
        assertEquals(fixture.eur("15.00"),
                fixture.balanceOf(ChartOfAccounts.REVENUE_CHARGEBACK_FEES));
    }

    @Test
    void losingArbitrationPassesTheSchemeFeeOnToTheMerchant() {
        fixture.warmUpMerchantBalance();
        PaymentResult payment = capturedPayment("100.00", false);
        Chargeback dispute = fixture.chargebacks.receive(payment.payment().paymentId(),
                ChargebackReason.GOODS_NOT_RECEIVED, fixture.eur("100.00"));
        fixture.chargebacks.represent(dispute.caseId(), List.of("delivery confirmation"));
        fixture.chargebacks.escalateToPreArbitration(dispute.caseId(), "rejected");
        fixture.chargebacks.escalateToArbitration(dispute.caseId());

        Money balanceBefore = fixture.merchantBalance();
        fixture.chargebacks.ruleArbitration(dispute.caseId(), false, "merchant at fault");

        assertEquals(balanceBefore.minus(ChargebackService.ARBITRATION_FEE),
                fixture.merchantBalance());
        assertTrue(fixture.ledger.trialBalance().balances());
    }

    @Test
    void winningArbitrationMakesTheSchemeFeeOurExpense() {
        fixture.warmUpMerchantBalance();
        PaymentResult payment = capturedPayment("100.00", false);
        Chargeback dispute = fixture.chargebacks.receive(payment.payment().paymentId(),
                ChargebackReason.GOODS_NOT_RECEIVED, fixture.eur("100.00"));
        fixture.chargebacks.represent(dispute.caseId(), List.of("delivery confirmation"));
        fixture.chargebacks.escalateToPreArbitration(dispute.caseId(), "rejected");
        fixture.chargebacks.escalateToArbitration(dispute.caseId());

        Money schemeFeesBefore = fixture.balanceOf(ChartOfAccounts.EXPENSE_SCHEME_FEES);
        fixture.chargebacks.ruleArbitration(dispute.caseId(), true, "evidence accepted");

        assertEquals(schemeFeesBefore.plus(ChargebackService.ARBITRATION_FEE),
                fixture.balanceOf(ChartOfAccounts.EXPENSE_SCHEME_FEES));
        assertTrue(fixture.ledger.trialBalance().balances());
    }

    @Test
    void illegalStatusTransitionsAreRefused() {
        PaymentResult payment = capturedPayment("100.00", false);
        Chargeback dispute = fixture.chargebacks.receive(payment.payment().paymentId(),
                ChargebackReason.GOODS_NOT_RECEIVED, fixture.eur("100.00"));
        fixture.chargebacks.accept(dispute.caseId());

        assertThrows(IllegalStateException.class,
                () -> fixture.chargebacks.represent(dispute.caseId(), List.of("too late")));
    }

    @Test
    void anUnknownCaseIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> fixture.chargebacks.accept("cb_does-not-exist"));
        assertTrue(fixture.chargebacks.find("cb_does-not-exist").isEmpty());
    }

    // --- Credit risk ------------------------------------------------------

    @Test
    void aShortfallTheMerchantCannotCoverBecomesOurExpense() {
        // Capture, pay nothing out, then dispute more than the merchant's balance can
        // cover once the handling fee is added.
        PaymentResult payment = capturedPayment("100.00", false);
        Money merchantBalance = fixture.merchantBalance();
        assertTrue(merchantBalance.isLessThan(fixture.eur("115.00")));

        Chargeback dispute = fixture.chargebacks.receive(payment.payment().paymentId(),
                ChargebackReason.GOODS_NOT_RECEIVED, fixture.eur("100.00"));

        assertTrue(dispute.hasAbsorbedLoss(), "the acquirer's real credit risk on the merchant");
        assertEquals(merchantBalance, dispute.recoveredFromMerchant());
        // Recovery is applied to the disputed amount before the fee: we would rather
        // recover what we owe the issuer than the fee we would like to earn.
        assertEquals(fixture.eur("0.00"), dispute.recoveredFee());
        assertEquals(fixture.eur("100.00").minus(merchantBalance), dispute.lossAbsorbed());
        assertEquals(dispute.lossAbsorbed(),
                fixture.balanceOf(ChartOfAccounts.EXPENSE_CHARGEBACK_LOSSES));
        assertTrue(fixture.ledger.trialBalance().balances());
    }

    @Test
    void winningAfterAbsorbingALossReversesTheExpenseToo() {
        PaymentResult payment = capturedPayment("900.00", true);
        // Drain the merchant's balance so the claw-back cannot be fully covered.
        fixture.service.refund(payment.payment().paymentId(), fixture.eur("880.00"),
                "mostly refunded", fixture.key());

        Chargeback dispute = fixture.chargebacks.receive(payment.payment().paymentId(),
                ChargebackReason.FRAUD_CARD_ABSENT, fixture.eur("20.00"));
        assertTrue(dispute.hasAbsorbedLoss());

        Money balanceAfterClawBack = fixture.merchantBalance();
        fixture.chargebacks.represent(dispute.caseId(), List.of("ECI 05 authentication record"));

        assertEquals(fixture.eur("0.00"),
                fixture.balanceOf(ChartOfAccounts.EXPENSE_CHARGEBACK_LOSSES));
        assertEquals(balanceAfterClawBack.plus(dispute.recoveredFromMerchant()),
                fixture.merchantBalance());
        assertTrue(fixture.ledger.trialBalance().balances());
    }

    // --- Ratio monitoring -------------------------------------------------

    @Test
    void theDisputeRatioIsMeasuredAgainstCapturedVolume() {
        for (int i = 0; i < 4; i++) {
            fixture.service.authorize(fixture.authAndCaptureFrom(
                    fixture.distinctCardOn(GOOD_BIN, 100 + i, "1000.00"), "10.00",
                    "192.0.2." + i));
        }
        PaymentResult disputed = fixture.service.authorize(fixture.authAndCaptureFrom(
                fixture.distinctCardOn(GOOD_BIN, 200, "1000.00"), "10.00", "192.0.2.200"));
        fixture.chargebacks.receive(disputed.payment().paymentId(),
                ChargebackReason.GOODS_NOT_RECEIVED, fixture.eur("10.00"));

        ChargebackService.ChargebackRatio ratio =
                fixture.chargebacks.ratioFor(CardFixture.MERCHANT);

        assertEquals(1, ratio.disputes());
        assertEquals(5, ratio.capturedTransactions());
        assertEquals("20.00%", ratio.formattedRatio(), "locale-independent formatting");
        assertTrue(ratio.inMonitoringProgramme(), "far above the scheme's 0.9% threshold");
    }

    @Test
    void aMerchantWithNoDisputesIsWithinTheThreshold() {
        fixture.service.authorize(fixture.authAndCapture(
                fixture.cardOn(GOOD_BIN, "1000.00"), "10.00"));

        ChargebackService.ChargebackRatio ratio =
                fixture.chargebacks.ratioFor(CardFixture.MERCHANT);

        assertEquals(0, ratio.disputes());
        assertFalse(ratio.inMonitoringProgramme());
        assertEquals("0.00%", ratio.formattedRatio());
    }

    @Test
    void aMerchantWithNoVolumeAtAllDoesNotDivideByZero() {
        ChargebackService.ChargebackRatio ratio = fixture.chargebacks.ratioFor("nobody");

        assertEquals(0, ratio.capturedTransactions());
        assertEquals("0.00%", ratio.formattedRatio());
        assertFalse(ratio.inMonitoringProgramme());
    }
}
