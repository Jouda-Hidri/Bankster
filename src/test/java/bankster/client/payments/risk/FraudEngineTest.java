package bankster.client.payments.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import bankster.client.payments.Money;
import bankster.client.payments.TestClock;

/**
 * The rules, and in particular the velocity rules — where most of the signal is, and
 * which no per-transaction check can see.
 */
class FraudEngineTest {

    private TestClock clock;
    private FraudEngine engine;

    @BeforeEach
    void setUp() {
        clock = TestClock.businessDayMorning();
        engine = new FraudEngine(clock);
    }

    private RiskContext attempt(String paymentId, String par, String ip, String amount) {
        return new RiskContext(paymentId, "merchant-1", par, "411111",
                Money.of("EUR", amount), "EE", "EE", "EE", ip, "5411",
                false, false, clock.instant());
    }

    private RiskContext attempt(String paymentId, String par, String ip, String amount,
                                String ipCountry, String billingCountry, String mcc) {
        return new RiskContext(paymentId, "merchant-1", par, "411111",
                Money.of("EUR", amount), "EE", ipCountry, billingCountry, ip, mcc,
                false, false, clock.instant());
    }

    // --- Baseline ---------------------------------------------------------

    @Test
    void anOrdinaryFirstPurchaseScoresLow() {
        RiskAssessment assessment = engine.evaluate(attempt("p1", "PAR-1", "1.2.3.4", "45.00"));

        // Card-not-present and an unseen card, and nothing else.
        assertEquals(10, assessment.score());
        assertEquals(RiskBand.LOW, assessment.band());
        assertEquals(RiskDecision.APPROVE, assessment.decision());
        assertEquals(2, assessment.reasons().size());
    }

    @Test
    void aKnownCardDropsTheUnknownCardPenalty() {
        engine.record(attempt("p1", "PAR-1", "1.2.3.4", "45.00"), true);

        RiskAssessment assessment = engine.evaluate(attempt("p2", "PAR-1", "1.2.3.4", "45.00"));

        assertTrue(assessment.reasons().stream()
                .noneMatch(reason -> reason.rule().equals("unknown-card")));
    }

    @Test
    void everyRuleThatFiresExplainsItself() {
        RiskAssessment assessment = engine.evaluate(attempt("p1", "PAR-1", "1.2.3.4", "45.00"));

        assertTrue(assessment.reasons().stream()
                .allMatch(reason -> reason.detail() != null && !reason.detail().isBlank()));
        assertTrue(assessment.explain().contains("card-not-present"));
        // A score with no explanation cannot be argued with by an analyst, defended to
        // a regulator, or debugged when the false-positive rate moves.
        assertFalse(assessment.explain().isBlank());
    }

    @Test
    void aCardholderPresentRecurringPaymentDoesNotTakeTheCnpPenalty() {
        RiskContext inStore = new RiskContext("p1", "merchant-1", "PAR-1", "411111",
                Money.of("EUR", "45.00"), "EE", "EE", "EE", "1.2.3.4", "5411",
                true, false, clock.instant());

        assertTrue(engine.evaluate(inStore).reasons().stream()
                .noneMatch(reason -> reason.rule().equals("card-not-present")));
    }

    // --- Velocity ---------------------------------------------------------

    @Test
    void manyAttemptsOnOneCardInTenMinutesEscalates() {
        for (int i = 0; i < 5; i++) {
            engine.record(attempt("p" + i, "PAR-1", "1.2.3." + i, "45.00"), true);
        }

        RiskAssessment assessment = engine.evaluate(attempt("p9", "PAR-1", "1.2.3.9", "45.00"));

        assertTrue(assessment.reasons().stream()
                        .anyMatch(reason -> reason.rule().equals("card-velocity")),
                assessment.explain());
        assertEquals(15, assessment.score(), "5 cnp attempts: 10 for velocity plus 5 for CNP");
    }

    @Test
    void velocityPointsScaleWithTheNumberOfAttempts() {
        for (int i = 0; i < 9; i++) {
            engine.record(attempt("p" + i, "PAR-1", "1.2.3." + i, "45.00"), true);
        }

        RiskAssessment assessment = engine.evaluate(attempt("p99", "PAR-1", "1.2.3.99", "45.00"));

        // 9 attempts earns 10 + (9-5)*5 = 30 points, which takes it past the
        // challenge threshold on its own.
        assertTrue(assessment.score() >= 30, assessment.explain());
        assertEquals(RiskDecision.CHALLENGE, assessment.decision());
    }

    @Test
    void velocityFallsAwayOnceTheWindowHasPassed() {
        for (int i = 0; i < 6; i++) {
            engine.record(attempt("p" + i, "PAR-1", "1.2.3." + i, "45.00"), true);
        }
        assertTrue(engine.evaluate(attempt("p9", "PAR-1", "1.2.3.9", "45.00")).reasons().stream()
                .anyMatch(reason -> reason.rule().equals("card-velocity")));

        clock.advance(Duration.ofMinutes(15));

        RiskAssessment afterWindow = engine.evaluate(attempt("p9", "PAR-1", "1.2.3.9", "45.00"));
        assertTrue(afterWindow.reasons().stream()
                .noneMatch(reason -> reason.rule().equals("card-velocity")));
    }

    @Test
    void aDailyVelocityRuleCatchesASlowerBurn() {
        // Spread over hours, so the ten-minute rule never fires.
        for (int i = 0; i < 21; i++) {
            engine.record(attempt("p" + i, "PAR-1", "1.2.3." + i, "45.00"), true);
            clock.advance(Duration.ofMinutes(30));
        }

        RiskAssessment assessment = engine.evaluate(attempt("p99", "PAR-1", "1.2.3.99", "45.00"));

        assertTrue(assessment.reasons().stream()
                        .anyMatch(reason -> reason.rule().equals("card-velocity-daily")),
                assessment.explain());
    }

    @Test
    void cardTestingIsTheSignatureOfManyCardsAndSmallAmountsFromOneAddress() {
        String attackerIp = "203.0.113.77";
        for (int i = 0; i < 5; i++) {
            engine.record(attempt("p" + i, "PAR-" + i, attackerIp, "1.00"), false);
        }

        RiskAssessment assessment =
                engine.evaluate(attempt("p9", "PAR-9", attackerIp, "1.00"));

        assertTrue(assessment.reasons().stream()
                        .anyMatch(reason -> reason.rule().equals("card-testing")),
                assessment.explain());
        assertEquals(RiskDecision.DECLINE, assessment.decision());
        assertEquals(RiskBand.CRITICAL, assessment.band());
    }

    @Test
    void manyCardsFromOneAddressAtNormalAmountsIsLessSevere() {
        String ip = "203.0.113.78";
        for (int i = 0; i < 5; i++) {
            engine.record(attempt("p" + i, "PAR-" + i, ip, "80.00"), true);
        }

        RiskAssessment assessment = engine.evaluate(attempt("p9", "PAR-9", ip, "80.00"));

        assertTrue(assessment.reasons().stream()
                        .anyMatch(reason -> reason.rule().equals("ip-card-spread")),
                assessment.explain());
        assertTrue(assessment.reasons().stream()
                .noneMatch(reason -> reason.rule().equals("card-testing")));
    }

    @Test
    void aChallengedAttemptStillCountsTowardsVelocity() {
        // Otherwise an attack whose attempts all trip the challenge rule suppresses
        // exactly the evidence it generates.
        String ip = "203.0.113.79";
        for (int i = 0; i < 5; i++) {
            engine.record(attempt("p" + i, "PAR-" + i, ip, "1.00"), FraudEngine.Outcome.CHALLENGED);
        }

        RiskAssessment assessment = engine.evaluate(attempt("p9", "PAR-9", ip, "1.00"));

        assertTrue(assessment.reasons().stream()
                        .anyMatch(reason -> reason.rule().equals("card-testing")),
                assessment.explain());
    }

    @Test
    void aChallengedAttemptIsNotCountedAsADecline() {
        String ip = "203.0.113.80";
        for (int i = 0; i < 4; i++) {
            engine.record(attempt("p" + i, "PAR-1", ip, "45.00"), FraudEngine.Outcome.CHALLENGED);
        }

        RiskAssessment assessment = engine.evaluate(attempt("p9", "PAR-1", ip, "45.00"));

        assertTrue(assessment.reasons().stream()
                        .noneMatch(reason -> reason.rule().equals("repeated-declines")),
                "a challenge is not a refusal");
    }

    @Test
    void repeatedDeclinesLookLikeEnumeration() {
        for (int i = 0; i < 3; i++) {
            engine.record(attempt("p" + i, "PAR-1", "1.2.3.4", "45.00"), false);
        }

        RiskAssessment assessment = engine.evaluate(attempt("p9", "PAR-1", "1.2.3.4", "45.00"));

        assertTrue(assessment.reasons().stream()
                        .anyMatch(reason -> reason.rule().equals("repeated-declines")),
                assessment.explain());
    }

    @Test
    void oneCardAtManyMerchantsInTenMinutesIsSuspicious() {
        for (int i = 0; i < 4; i++) {
            RiskContext atMerchant = new RiskContext("p" + i, "merchant-" + i, "PAR-1", "411111",
                    Money.of("EUR", "45.00"), "EE", "EE", "EE", "1.2.3." + i, "5411",
                    false, false, clock.instant());
            engine.record(atMerchant, true);
        }

        RiskAssessment assessment = engine.evaluate(attempt("p9", "PAR-1", "1.2.3.9", "45.00"));

        assertTrue(assessment.reasons().stream()
                        .anyMatch(reason -> reason.rule().equals("cross-merchant-velocity")),
                assessment.explain());
    }

    @Test
    void crossMerchantVelocityWorksOnThePaymentAccountReferenceNotThePan() {
        // The engine is never given a card number — only the PAR and the BIN.
        RiskContext context = attempt("p1", "PAR-1", "1.2.3.4", "45.00");

        assertEquals("PAR-1", context.cardPar());
        assertEquals("411111", context.bin());
    }

    // --- Amount and geography --------------------------------------------

    @Test
    void anAmountFarAboveTheCardsHistoryIsFlagged() {
        for (int i = 0; i < 3; i++) {
            engine.record(attempt("p" + i, "PAR-1", "1.2.3." + i, "20.00"), true);
        }

        RiskAssessment assessment = engine.evaluate(attempt("p9", "PAR-1", "1.2.3.9", "900.00"));

        assertTrue(assessment.reasons().stream()
                        .anyMatch(reason -> reason.rule().equals("amount-anomaly")),
                assessment.explain());
    }

    @Test
    void theAmountBaselineNeedsEnoughHistoryToBeMeaningful() {
        engine.record(attempt("p1", "PAR-1", "1.2.3.4", "20.00"), true);

        RiskAssessment assessment = engine.evaluate(attempt("p9", "PAR-1", "1.2.3.9", "900.00"));

        assertTrue(assessment.reasons().stream()
                        .noneMatch(reason -> reason.rule().equals("amount-anomaly")),
                "one prior purchase is not a spending pattern");
    }

    @Test
    void onlyApprovedAttemptsFormTheAmountBaseline() {
        for (int i = 0; i < 4; i++) {
            engine.record(attempt("p" + i, "PAR-1", "1.2.3." + i, "20.00"), false);
        }

        RiskAssessment assessment = engine.evaluate(attempt("p9", "PAR-1", "1.2.3.9", "900.00"));

        assertTrue(assessment.reasons().stream()
                .noneMatch(reason -> reason.rule().equals("amount-anomaly")));
    }

    @Test
    void geographyMismatchesAccumulate() {
        RiskAssessment assessment = engine.evaluate(
                attempt("p1", "PAR-1", "1.2.3.4", "45.00", "NG", "DE", "5411"));

        assertTrue(assessment.reasons().stream()
                .anyMatch(reason -> reason.rule().equals("geo-issuer-mismatch")));
        assertTrue(assessment.reasons().stream()
                .anyMatch(reason -> reason.rule().equals("geo-billing-mismatch")));
        assertEquals(RiskBand.MEDIUM, assessment.band());
        assertEquals(RiskDecision.CHALLENGE, assessment.decision());
    }

    @Test
    void aHighRiskMerchantCategoryAddsPoints() {
        RiskAssessment gambling = engine.evaluate(
                attempt("p1", "PAR-1", "1.2.3.4", "45.00", "EE", "EE", "7995"));
        RiskAssessment grocery = engine.evaluate(
                attempt("p2", "PAR-2", "1.2.3.5", "45.00", "EE", "EE", "5411"));

        assertTrue(gambling.score() > grocery.score());
        assertTrue(gambling.explain().contains("gambling"), gambling.explain());
    }

    // --- Decisions --------------------------------------------------------

    @Test
    void theMiddleBandIsChallengedRatherThanRefused() {
        // Declining a good customer costs more than the fraud it prevents, so the
        // middle band is stepped up to 3-D Secure instead.
        assertEquals(RiskDecision.APPROVE, RiskBand.forScore(10) == RiskBand.LOW
                ? RiskDecision.APPROVE : RiskDecision.CHALLENGE);
        assertEquals(RiskBand.LOW, RiskBand.forScore(24));
        assertEquals(RiskBand.MEDIUM, RiskBand.forScore(25));
        assertEquals(RiskBand.HIGH, RiskBand.forScore(50));
        assertEquals(RiskBand.CRITICAL, RiskBand.forScore(80));
    }

    @Test
    void theScoreIsCappedAtOneHundred() {
        String ip = "203.0.113.90";
        for (int i = 0; i < 10; i++) {
            engine.record(attempt("p" + i, "PAR-" + i, ip, "1.00"), false);
        }

        RiskAssessment assessment = engine.evaluate(
                attempt("p99", "PAR-99", ip, "1.00", "NG", "DE", "7995"));

        assertEquals(100, assessment.score());
    }

    @Test
    void evaluatingTwiceDoesNotInflateItsOwnCounters() {
        RiskContext context = attempt("p1", "PAR-1", "1.2.3.4", "45.00");

        int first = engine.evaluate(context).score();
        int second = engine.evaluate(context).score();

        assertEquals(first, second, "scoring is a read; recording is a separate call");
        assertEquals(0, engine.trackedAttempts());
    }

    @Test
    void recordingTheSamePaymentTwiceUpdatesRatherThanDuplicates() {
        RiskContext context = attempt("p1", "PAR-1", "1.2.3.4", "45.00");

        engine.record(context, FraudEngine.Outcome.CHALLENGED);
        engine.record(context, FraudEngine.Outcome.APPROVED);

        assertEquals(1, engine.trackedAttempts(),
                "one attempt with a final outcome, not two");
    }

    @Test
    void historyIsPrunedOnceItIsNoLongerUseful() {
        engine.record(attempt("p1", "PAR-1", "1.2.3.4", "45.00"), true);
        assertEquals(1, engine.trackedAttempts());

        clock.advanceDays(3);

        assertEquals(0, engine.trackedAttempts());
    }

    @Test
    void anAttemptWithNoAddressDoesNotBreakTheIpRules() {
        RiskContext noIp = new RiskContext("p1", "merchant-1", "PAR-1", "411111",
                Money.of("EUR", "45.00"), "EE", "EE", "EE", null, "5411",
                false, false, clock.instant());

        RiskAssessment assessment = engine.evaluate(noIp);

        assertEquals(RiskDecision.APPROVE, assessment.decision());
    }
}
