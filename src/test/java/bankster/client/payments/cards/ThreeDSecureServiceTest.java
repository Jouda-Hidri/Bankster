package bankster.client.payments.cards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import bankster.client.payments.Money;
import bankster.client.payments.cards.ThreeDSecureResult.Outcome;
import bankster.client.payments.risk.RiskAssessment;
import bankster.client.payments.risk.RiskBand;
import bankster.client.payments.risk.RiskDecision;

/**
 * The PSD2 exemption logic. The thing being tested is the trade-off: regulation
 * requires SCA, conversion argues against it, and liability decides which wins.
 */
class ThreeDSecureServiceTest {

    private ThreeDSecureService lowFraudAcquirer;
    private CardToken card;

    @BeforeEach
    void setUp() {
        // 1 bp measured fraud, which earns the highest TRA ceiling.
        lowFraudAcquirer = new ThreeDSecureService(1);
        card = new CardToken("4111111111111111", "BNK00000000000000000000000001",
                CardScheme.VISA, "411111", "1111", "merchant-1", YearMonth.of(2030, 1));
    }

    private static RiskAssessment risk(int score, RiskDecision decision) {
        return new RiskAssessment(score, RiskBand.forScore(score), decision, List.of());
    }

    private ThreeDSecureService.AuthenticationRequest request(String amount, RiskAssessment risk) {
        return new ThreeDSecureService.AuthenticationRequest("pay-1", card,
                Money.of("EUR", amount), risk, false, false, false, false, true);
    }

    // --- Exemptions -------------------------------------------------------

    @Test
    void aLowValuePaymentIsExemptedAndKeepsLiabilityWithUs() {
        ThreeDSecureResult result = lowFraudAcquirer.authenticate(
                request("18.50", risk(5, RiskDecision.APPROVE)));

        assertEquals(Outcome.EXEMPTED, result.outcome());
        assertEquals(ScaExemption.LOW_VALUE, result.exemption());
        assertEquals("07", result.eci(), "not authenticated");
        assertFalse(result.liabilityShift(), "the price of skipping the challenge");
        assertTrue(result.canProceedToAuthorization());
    }

    @Test
    void theLowValueExemptionLapsesAfterFiveConsecutiveUses() {
        for (int use = 1; use <= 5; use++) {
            ThreeDSecureResult result = lowFraudAcquirer.authenticate(
                    request("10.00", risk(5, RiskDecision.APPROVE)));
            assertEquals(ScaExemption.LOW_VALUE, result.exemption(), "use " + use);
        }

        // The counter is what stops the exemption being used to split a large fraud
        // into small pieces.
        ThreeDSecureResult sixth = lowFraudAcquirer.authenticate(
                request("10.00", risk(5, RiskDecision.APPROVE)));
        assertEquals(ScaExemption.TRANSACTION_RISK_ANALYSIS, sixth.exemption(),
                "low-value is exhausted; TRA is still available at this amount");
    }

    @Test
    void theLowValueExemptionAlsoLapsesOnCumulativeSpend() {
        // Four uses of 28.00 reaches 112.00, past the 100.00 cumulative ceiling.
        lowFraudAcquirer.authenticate(request("28.00", risk(5, RiskDecision.APPROVE)));
        lowFraudAcquirer.authenticate(request("28.00", risk(5, RiskDecision.APPROVE)));
        lowFraudAcquirer.authenticate(request("28.00", risk(5, RiskDecision.APPROVE)));

        ThreeDSecureResult fourth = lowFraudAcquirer.authenticate(
                request("28.00", risk(5, RiskDecision.APPROVE)));

        assertFalse(fourth.exemption() == ScaExemption.LOW_VALUE,
                "84.00 already used; a fourth 28.00 would breach the 100.00 ceiling");
    }

    @Test
    void passingAChallengeResetsTheLowValueCounters() {
        for (int use = 0; use < 5; use++) {
            lowFraudAcquirer.authenticate(request("10.00", risk(5, RiskDecision.APPROVE)));
        }

        // A successful SCA is the event the cumulative limits are measured from.
        ThreeDSecureResult challenge = lowFraudAcquirer.authenticate(
                request("900.00", risk(5, RiskDecision.APPROVE)));
        assertEquals(Outcome.CHALLENGE_REQUIRED, challenge.outcome());
        lowFraudAcquirer.completeChallenge(challenge.dsTransactionId(), true);

        ThreeDSecureResult afterReset = lowFraudAcquirer.authenticate(
                request("10.00", risk(5, RiskDecision.APPROVE)));
        assertEquals(ScaExemption.LOW_VALUE, afterReset.exemption());
    }

    @Test
    void theRiskAnalysisCeilingIsEarnedByTheFraudRate() {
        assertEquals(Money.of("EUR", "500.00"), new ThreeDSecureService(1).riskAnalysisCeiling());
        assertEquals(Money.of("EUR", "250.00"), new ThreeDSecureService(6).riskAnalysisCeiling());
        assertEquals(Money.of("EUR", "100.00"), new ThreeDSecureService(13).riskAnalysisCeiling());
        assertEquals(Money.zero("EUR"), new ThreeDSecureService(20).riskAnalysisCeiling(),
                "above 13 bps the exemption is not available at all");
    }

    @Test
    void aHighFraudAcquirerCannotClaimRiskAnalysisAtAll() {
        ThreeDSecureService highFraud = new ThreeDSecureService(40);

        ThreeDSecureResult result = highFraud.authenticate(new ThreeDSecureService.AuthenticationRequest(
                "pay-1", card, Money.of("EUR", "200.00"), risk(5, RiskDecision.APPROVE),
                false, false, false, false, true));

        assertEquals(Outcome.FRICTIONLESS, result.outcome(),
                "no exemption available, so it is authenticated instead");
        assertEquals(ScaExemption.NONE, result.exemption());
        assertTrue(result.liabilityShift());
    }

    @Test
    void aMerchantInitiatedPaymentIsExemptedEvenWhenRiskWantsAuthentication() {
        // There is no cardholder present to authenticate, so SCA is impossible; the
        // mandate was authenticated when it was set up.
        ThreeDSecureResult result = lowFraudAcquirer.authenticate(
                new ThreeDSecureService.AuthenticationRequest("pay-1", card,
                        Money.of("EUR", "900.00"), risk(60, RiskDecision.CHALLENGE),
                        false, true, false, false, true));

        assertEquals(ScaExemption.MERCHANT_INITIATED, result.exemption());
    }

    @Test
    void riskVetoesTheDiscretionaryExemptions() {
        // Low value and TRA are ours to claim, so a risky transaction loses them —
        // on a risky payment the liability shift is worth the conversion cost.
        ThreeDSecureResult result = lowFraudAcquirer.authenticate(
                request("15.00", risk(60, RiskDecision.CHALLENGE)));

        assertEquals(Outcome.CHALLENGE_REQUIRED, result.outcome());
        assertEquals(ScaExemption.NONE, result.exemption());
    }

    @Test
    void aRecurringPaymentIsExemptedRegardlessOfRisk() {
        ThreeDSecureResult result = lowFraudAcquirer.authenticate(
                new ThreeDSecureService.AuthenticationRequest("pay-1", card,
                        Money.of("EUR", "900.00"), risk(60, RiskDecision.CHALLENGE),
                        true, false, false, false, true));

        assertEquals(ScaExemption.RECURRING, result.exemption());
    }

    @Test
    void aTrustedBeneficiaryIsExemptedButOnlyWhenRiskAgrees() {
        ThreeDSecureResult trusted = lowFraudAcquirer.authenticate(
                new ThreeDSecureService.AuthenticationRequest("pay-1", card,
                        Money.of("EUR", "900.00"), risk(5, RiskDecision.APPROVE),
                        false, false, true, false, true));
        assertEquals(ScaExemption.TRUSTED_BENEFICIARY, trusted.exemption());

        ThreeDSecureResult risky = lowFraudAcquirer.authenticate(
                new ThreeDSecureService.AuthenticationRequest("pay-2", card,
                        Money.of("EUR", "900.00"), risk(60, RiskDecision.CHALLENGE),
                        false, false, true, false, true));
        assertEquals(ScaExemption.NONE, risky.exemption());
    }

    @Test
    void everyExemptionRetainsLiability() {
        for (ScaExemption exemption : ScaExemption.values()) {
            if (exemption == ScaExemption.NONE) {
                assertFalse(exemption.retainsLiability());
            } else {
                assertTrue(exemption.retainsLiability(), exemption + " should retain liability");
            }
        }
    }

    // --- Authentication outcomes -----------------------------------------

    @Test
    void aLargeAmountIsChallengedEvenWithALowRiskScore() {
        ThreeDSecureResult result = lowFraudAcquirer.authenticate(
                request("900.00", risk(5, RiskDecision.APPROVE)));

        assertEquals(Outcome.CHALLENGE_REQUIRED, result.outcome());
        assertTrue(result.requiresChallenge());
        assertFalse(result.canProceedToAuthorization(), "not yet — the shopper has to respond");
        assertTrue(lowFraudAcquirer.hasPendingChallenge(result.dsTransactionId()));
    }

    @Test
    void aPassedChallengeShiftsLiabilityAndSuppliesACryptogram() {
        ThreeDSecureResult challenge = lowFraudAcquirer.authenticate(
                request("900.00", risk(5, RiskDecision.APPROVE)));

        ThreeDSecureResult passed =
                lowFraudAcquirer.completeChallenge(challenge.dsTransactionId(), true);

        assertEquals(Outcome.CHALLENGE_PASSED, passed.outcome());
        assertEquals("05", passed.eci(), "fully authenticated");
        assertTrue(passed.liabilityShift());
        assertTrue(passed.cavv() != null && !passed.cavv().isBlank(),
                "an authorization claiming ECI 05 without a cryptogram is refused");
        assertFalse(lowFraudAcquirer.hasPendingChallenge(challenge.dsTransactionId()));
    }

    @Test
    void anAbandonedChallengeCannotProceedToAuthorization() {
        ThreeDSecureResult challenge = lowFraudAcquirer.authenticate(
                request("900.00", risk(5, RiskDecision.APPROVE)));

        ThreeDSecureResult failed =
                lowFraudAcquirer.completeChallenge(challenge.dsTransactionId(), false);

        assertEquals(Outcome.CHALLENGE_FAILED, failed.outcome());
        assertFalse(failed.liabilityShift());
        assertFalse(failed.canProceedToAuthorization());
    }

    @Test
    void completingAnUnknownChallengeIsReportedRatherThanAccepted() {
        ThreeDSecureResult result = lowFraudAcquirer.completeChallenge("not-a-reference", true);

        assertEquals(Outcome.UNAVAILABLE, result.outcome());
        assertFalse(result.canProceedToAuthorization());
    }

    @Test
    void aChallengeCannotBeReplayedToManufactureASecondAuthentication() {
        ThreeDSecureResult challenge = lowFraudAcquirer.authenticate(
                request("900.00", risk(5, RiskDecision.APPROVE)));
        lowFraudAcquirer.completeChallenge(challenge.dsTransactionId(), true);

        ThreeDSecureResult replay =
                lowFraudAcquirer.completeChallenge(challenge.dsTransactionId(), true);

        assertEquals(Outcome.UNAVAILABLE, replay.outcome());
    }

    @Test
    void anIssuerThatIsNotEnrolledStillYieldsAnAttemptedAuthentication() {
        ThreeDSecureResult result = lowFraudAcquirer.authenticate(
                new ThreeDSecureService.AuthenticationRequest("pay-1", card,
                        Money.of("EUR", "900.00"), risk(5, RiskDecision.APPROVE),
                        false, false, false, false, false));

        assertEquals(Outcome.ATTEMPTED, result.outcome());
        assertEquals("06", result.eci());
        assertTrue(result.liabilityShift(),
                "the schemes shift liability for an attempt against a non-participant");
        assertTrue(result.isAuthenticated());
    }

    @Test
    void aTransactionTheFraudEngineWantsRefusedIsNotAuthenticatedAtAll() {
        ThreeDSecureResult result = lowFraudAcquirer.authenticate(
                request("50.00", risk(95, RiskDecision.DECLINE)));

        assertEquals(Outcome.REJECTED, result.outcome());
        assertFalse(result.canProceedToAuthorization());
        assertTrue(result.reason().contains("fraud engine"), result.reason());
    }

    @Test
    void aCorporateCardIsExemptedUnderTheSecureCorporateCarveOut() {
        ThreeDSecureResult result = lowFraudAcquirer.authenticate(
                new ThreeDSecureService.AuthenticationRequest("pay-1", card,
                        Money.of("EUR", "5000.00"), risk(5, RiskDecision.APPROVE),
                        false, false, false, true, true));

        assertEquals(ScaExemption.CORPORATE, result.exemption());
    }
}
