package bankster.client.payments.rails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import bankster.client.payments.Money;
import bankster.client.payments.TestClock;
import bankster.client.payments.rails.SepaRouter.RailRequest;
import bankster.client.payments.rails.SepaRouter.Urgency;

/**
 * Smart rail routing: what happens when a payer asks for instant and instant is not
 * possible. The requirement is that the transfer is downgraded deliberately, with a
 * recorded reason — not rejected, and not silently changed.
 */
class SepaRouterTest {

    private static final String DEBTOR = "EE717700771001735865";
    /** Deutsche Bank — reachable on instant. */
    private static final String REACHABLE = "DE04500700100532013000";
    /** Nordea Sweden — SEPA, but not on the instant scheme. */
    private static final String NOT_ON_INSTANT = "SE2930000000000540398031";

    private TestClock clock;
    private SepaRouter router;

    @BeforeEach
    void setUp() {
        // Tuesday 09:00 UTC, which is 11:00 in Brussels — a TARGET settlement day,
        // before both the SCT and the TARGET2 cut-offs.
        clock = TestClock.at("2026-09-15T09:00:00Z");
        router = new SepaRouter(clock, new ReachabilityDirectory(), new IbanBicDirectory());
    }

    private RailDecision route(String creditorIban, String amount, Urgency urgency) {
        return router.route(RailRequest.of(new Iban(DEBTOR), new Iban(creditorIban),
                Money.of("EUR", amount), urgency));
    }

    // --- Instant when it is possible --------------------------------------

    @Test
    void anInstantRequestWithinLimitToAReachableBankGoesInstant() {
        RailDecision decision = route(REACHABLE, "2450.00", Urgency.INSTANT);

        assertEquals(PaymentRail.SEPA_INST, decision.rail());
        assertFalse(decision.downgraded());
        assertTrue(decision.rejections().isEmpty());
        assertEquals("Sending by SEPA Instant Credit Transfer.", decision.customerFacingSummary());
    }

    @Test
    void instantSettlesInSecondsWhateverTheDayOfTheWeek() {
        clock.set("2026-09-13T22:00:00Z");
        RailDecision decision = route(REACHABLE, "100.00", Urgency.INSTANT);

        assertEquals(PaymentRail.SEPA_INST, decision.rail());
        assertTrue(decision.expectedSettlement().isBefore(clock.instant().plusSeconds(60)),
                "the instant scheme runs 24/7/365");
    }

    // --- The three reasons instant is refused ------------------------------

    @Test
    void anAmountOverTheInstitutionsLimitIsDowngradedAndSaysSo() {
        RailDecision decision = route(REACHABLE, "150000.00", Urgency.INSTANT);

        assertTrue(decision.downgraded());
        assertEquals(PaymentRail.SEPA_INST, decision.requestedRail());
        assertEquals(1, decision.rejections().size());
        assertEquals(PaymentRail.SEPA_INST, decision.rejections().get(0).rail());
        assertTrue(decision.rejections().get(0).reason().contains("instant transfer limit"),
                decision.rejections().get(0).reason());
        assertTrue(decision.customerFacingSummary().contains("instead of"));
    }

    @Test
    void anUnreachableBeneficiaryBankIsDowngradedAndNamed() {
        RailDecision decision = route(NOT_ON_INSTANT, "320.00", Urgency.INSTANT);

        assertEquals(PaymentRail.SEPA_SCT, decision.rail());
        assertTrue(decision.downgraded());
        String reason = decision.rejections().get(0).reason();
        assertTrue(reason.contains("Nordea Sweden"), reason);
        assertTrue(reason.contains("not reachable on SEPA Instant"), reason);
    }

    @Test
    void anInstantRailOutageIsDowngradedAndSaysThat() {
        router.setInstantRailAvailable(false);

        RailDecision decision = route(REACHABLE, "75.00", Urgency.INSTANT);

        assertEquals(PaymentRail.SEPA_SCT, decision.rail());
        assertTrue(decision.downgraded());
        assertTrue(decision.rejections().get(0).reason().contains("currently unavailable"),
                decision.rejections().get(0).reason());
    }

    @Test
    void anUnidentifiableBeneficiaryInstitutionIsNotAssumedReachable() {
        // Guessing instant reachability produces a rejection at the clearing
        // mechanism, which is worse than routing standard.
        Iban unknownBank = Iban.build("EE", "9900771001735865");
        RailDecision decision = router.route(RailRequest.of(new Iban(DEBTOR), unknownBank,
                Money.of("EUR", "50.00"), Urgency.INSTANT));

        assertEquals(PaymentRail.SEPA_SCT, decision.rail());
        assertTrue(decision.rejections().get(0).reason().contains("could not be identified"),
                decision.rejections().get(0).reason());
    }

    @Test
    void theMostUsefulReasonIsReportedFirst() {
        // Both the limit and reachability would block this. A payer whose amount is
        // over the limit wants to hear about the limit.
        RailDecision decision = route(NOT_ON_INSTANT, "150000.00", Urgency.INSTANT);

        assertTrue(decision.rejections().get(0).reason().contains("instant transfer limit"),
                decision.rejections().get(0).reason());
    }

    // --- Where an over-limit urgent transfer goes -------------------------

    @Test
    void anUrgentTransferTooLargeForInstantGoesToRtgsRatherThanWaiting() {
        RailDecision decision = route(REACHABLE, "150000.00", Urgency.INSTANT);

        assertEquals(PaymentRail.TARGET2, decision.rail(),
                "still urgent; RTGS settles today and has no upper limit");
        assertTrue(decision.downgraded());
    }

    @Test
    void anOverLimitTransferToANonTarget2ParticipantFallsAllTheWayToTheBatchRail() {
        // Nordea Sweden in this directory is on SCT only.
        RailDecision decision = route(NOT_ON_INSTANT, "150000.00", Urgency.INSTANT);

        assertEquals(PaymentRail.SEPA_SCT, decision.rail());
        assertTrue(decision.rejections().size() >= 2,
                "both the instant and the RTGS refusal are recorded");
    }

    @Test
    void pastTheTarget2CutOffAnUrgentLargeTransferWaitsForTheBatchRail() {
        // 18:00 Brussels is after the 17:00 CET customer-payment cut-off.
        clock.set("2026-09-15T16:00:00Z");

        RailDecision decision = route(REACHABLE, "150000.00", Urgency.INSTANT);

        assertEquals(PaymentRail.SEPA_SCT, decision.rail());
        assertTrue(decision.rejections().stream()
                        .anyMatch(rejection -> rejection.reason().contains("17:00 CET")),
                decision.rejections().toString());
    }

    @Test
    void onANonSettlementDayRtgsIsNotAvailableAtAll() {
        clock.set("2026-09-13T09:00:00Z"); // a Sunday

        RailDecision decision = route(REACHABLE, "150000.00", Urgency.INSTANT);

        assertEquals(PaymentRail.SEPA_SCT, decision.rail());
        assertTrue(decision.rejections().stream()
                        .anyMatch(rejection -> rejection.reason().contains("closed today")),
                decision.rejections().toString());
    }

    // --- Explicit urgencies -----------------------------------------------

    @Test
    void aStandardRequestGoesOnTheBatchRailWithoutBeingCalledADowngrade() {
        RailDecision decision = route(REACHABLE, "320.00", Urgency.STANDARD);

        assertEquals(PaymentRail.SEPA_SCT, decision.rail());
        assertFalse(decision.downgraded());
        assertTrue(decision.rejections().isEmpty());
    }

    @Test
    void aStandardRequestIsNotPromotedToRtgsJustForBeingLarge() {
        RailDecision decision = route(REACHABLE, "900000.00", Urgency.STANDARD);

        assertEquals(PaymentRail.SEPA_SCT, decision.rail(),
                "the payer asked for cheap, not fast");
    }

    @Test
    void aSameDayRequestGoesToRtgs() {
        RailDecision decision = route(REACHABLE, "5000.00", Urgency.SAME_DAY);

        assertEquals(PaymentRail.TARGET2, decision.rail());
        assertFalse(decision.downgraded());
    }

    // --- Leaving SEPA ------------------------------------------------------

    @Test
    void aNonEuroTransferLeavesSepaForCorrespondentBanking() {
        RailDecision decision = router.route(RailRequest.of(new Iban(DEBTOR), new Iban(REACHABLE),
                Money.of("USD", "1000.00"), Urgency.INSTANT));

        assertEquals(PaymentRail.SWIFT, decision.rail());
        assertTrue(decision.rejections().get(0).reason().contains("euro only"),
                decision.rejections().get(0).reason());
    }

    // --- Value dates -------------------------------------------------------

    @Test
    void beforeTheCutOffTheTransferMakesTodaysCycleAndSettlesTomorrow() {
        clock.set("2026-09-15T09:00:00Z"); // 11:00 Brussels, before 15:00

        assertEquals(LocalDate.of(2026, 9, 15), router.executionDate());
        assertEquals(LocalDate.of(2026, 9, 16), valueDate());
    }

    @Test
    void afterTheCutOffTheTransferMissesACycleEntirely() {
        clock.set("2026-09-15T14:00:00Z"); // 16:00 Brussels, after 15:00

        assertEquals(LocalDate.of(2026, 9, 16), router.executionDate(),
                "missing the cut-off costs a whole cycle");
        assertEquals(LocalDate.of(2026, 9, 17), valueDate());
    }

    @Test
    void aWeekendInstructionEntersMondaysCycle() {
        clock.set("2026-09-12T10:00:00Z"); // Saturday

        assertEquals(LocalDate.of(2026, 9, 14), router.executionDate());
        assertEquals(LocalDate.of(2026, 9, 15), valueDate());
    }

    @Test
    void aFridayAfternoonInstructionDoesNotArriveUntilTuesday() {
        clock.set("2026-09-11T14:00:00Z"); // Friday 16:00 Brussels, after the cut-off

        assertEquals(LocalDate.of(2026, 9, 14), router.executionDate());
        assertEquals(LocalDate.of(2026, 9, 15), valueDate());
    }

    private LocalDate valueDate() {
        return LocalDate.ofInstant(router.expectedSettlement(PaymentRail.SEPA_SCT),
                ZoneId.of("Europe/Brussels"));
    }

    // --- Fees and configuration -------------------------------------------

    @Test
    void eachRailIsPricedDifferently() {
        Money amount = Money.of("EUR", "1000.00");

        assertEquals(Money.of("EUR", "0.20"), router.feeFor(PaymentRail.SEPA_SCT, amount));
        assertEquals(Money.of("EUR", "0.50"), router.feeFor(PaymentRail.SEPA_INST, amount));
        assertEquals(Money.of("EUR", "15.00"), router.feeFor(PaymentRail.TARGET2, amount));
        assertTrue(router.feeFor(PaymentRail.SWIFT, amount)
                .isGreaterThan(router.feeFor(PaymentRail.TARGET2, amount)));
    }

    @Test
    void theInstantLimitIsAnInstitutionLevelSetting() {
        router.setInstantTransactionLimit(Money.of("EUR", "500.00"));

        // Over the lowered limit: no longer instant, and still urgent, so it takes
        // the same-day rail rather than waiting for the batch.
        RailDecision overLimit = route(REACHABLE, "600.00", Urgency.INSTANT);
        assertEquals(PaymentRail.TARGET2, overLimit.rail());
        assertTrue(overLimit.downgraded());

        assertEquals(PaymentRail.SEPA_INST, route(REACHABLE, "400.00", Urgency.INSTANT).rail());
    }

    @Test
    void atExactlyTheLimitInstantIsStillAllowed() {
        router.setInstantTransactionLimit(Money.of("EUR", "500.00"));

        assertEquals(PaymentRail.SEPA_INST, route(REACHABLE, "500.00", Urgency.INSTANT).rail(),
                "the limit is a ceiling, not an exclusive bound");
    }

    @Test
    void anExplicitBeneficiaryBicOverridesIbanResolution() {
        RailDecision decision = router.route(new RailRequest(new Iban(DEBTOR),
                new Iban(NOT_ON_INSTANT), Optional.of(new Bic("DEUTDEFF")),
                Money.of("EUR", "100.00"), Urgency.INSTANT));

        assertEquals(PaymentRail.SEPA_INST, decision.rail(),
                "the supplied BIC is reachable, whatever the IBAN resolves to");
    }

    // --- Rail properties ---------------------------------------------------

    @Test
    void onlyTheInstantRailIsIrrevocable() {
        assertTrue(PaymentRail.SEPA_INST.isIrrevocable());
        assertFalse(PaymentRail.SEPA_SCT.isIrrevocable());
        assertTrue(PaymentRail.SEPA_INST.isAlwaysOpen());
        assertFalse(PaymentRail.SEPA_SCT.isAlwaysOpen());
        assertTrue(PaymentRail.SEPA_SCT.isSepa());
        assertFalse(PaymentRail.SWIFT.isSepa());
    }
}
