package bankster.client.payments.rails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import bankster.client.payments.Money;
import bankster.client.payments.TestClock;
import bankster.client.payments.rails.SepaRouter.RailRequest;
import bankster.client.payments.rails.SepaRouter.Urgency;

/**
 * Routing when the ceiling depends on who is being paid.
 *
 * <p>The scheme-level maximum is gone, so two transfers of the same amount to two
 * different banks can legitimately take different rails. These tests hold the amount
 * constant and vary the beneficiary, which is the property that did not exist when the
 * limit was a single global value.
 */
class PerBankInstantLimitRoutingTest {

    private static final String DEBTOR = "EE717700771001735865";

    /** Deutsche Bank — seeded at 100,000. */
    private static final String DEUTSCHE_IBAN = "DE04500700100532013000";
    /** ING — seeded at 25,000. */
    private static final String ING_IBAN = "NL86INGB0002445588";
    /** LHV — seeded at 50,000, and a TARGET2 participant. */
    private static final String LHV_IBAN = "EE447700771001735866";

    private TestClock clock;
    private AspspLimitDirectory aspspLimits;
    private SepaRouter router;

    @BeforeEach
    void setUp() {
        clock = TestClock.at("2026-09-15T09:00:00Z");
        aspspLimits = new AspspLimitDirectory();
        router = new SepaRouter(clock, new ReachabilityDirectory(), new IbanBicDirectory(),
                aspspLimits);
    }

    private RailDecision route(String creditorIban, String amount) {
        return router.route(RailRequest.of(new Iban(DEBTOR), new Iban(creditorIban),
                Money.of("EUR", amount), Urgency.INSTANT));
    }

    // --- The same amount, two banks, two answers ---------------------------

    @Test
    void anAmountOneBankAcceptsAndAnotherDoesNotTakesDifferentRails() {
        // 40,000 is under Deutsche Bank's 100,000 and over ING's 25,000.
        assertEquals(PaymentRail.SEPA_INST, route(DEUTSCHE_IBAN, "40000.00").rail());
        assertFalse(route(DEUTSCHE_IBAN, "40000.00").downgraded());

        RailDecision toIng = route(ING_IBAN, "40000.00");
        assertTrue(toIng.downgraded());
        assertFalse(toIng.rail() == PaymentRail.SEPA_INST);
    }

    @Test
    void theReasonNamesTheBankWhoseLimitBitRatherThanOurs() {
        RailDecision decision = route(ING_IBAN, "40000.00");

        String reason = decision.rejections().get(0).reason();
        assertTrue(reason.contains("ING Bank"), reason);
        assertTrue(reason.contains("instant receiving limit"), reason);
        assertTrue(reason.contains("25000.00 EUR"), reason);
        // Quoting our own limit here would send the payer to argue with the wrong bank.
        assertFalse(reason.contains("this institution's instant transfer limit"), reason);
    }

    @Test
    void ourOwnLimitIsStillNamedWhenItIsTheOneThatBit() {
        router.setInstantTransactionLimit(Money.of("EUR", "10000.00"));

        String reason = route(DEUTSCHE_IBAN, "20000.00").rejections().get(0).reason();

        assertTrue(reason.contains("this institution's instant transfer limit"), reason);
        assertTrue(reason.contains("10000.00 EUR"), reason);
    }

    @Test
    void aBankWithNoDeclaredCeilingIsBoundedOnlyByOurs() {
        // BNP Paribas declares none, so our limit is the whole constraint.
        String bnpIban = Iban.build("FR", "30004010050500013M02606").value();

        assertEquals(PaymentRail.SEPA_INST, route(bnpIban, "90000.00").rail());

        router.setInstantTransactionLimit(Money.of("EUR", "50000.00"));
        assertTrue(route(bnpIban, "90000.00").downgraded());
    }

    // --- Interaction with the rest of the routing ---------------------------

    @Test
    void anUrgentTransferOverTheBeneficiarysLimitStillReachesRtgs() {
        // LHV's 50,000 ceiling is the binding one here, not our 100,000, and LHV is a
        // TARGET2 participant — so the transfer is blocked by *their* policy and still
        // reaches the same-day rail.
        RailDecision decision = router.route(RailRequest.of(new Iban(DEBTOR),
                new Iban(LHV_IBAN), Money.of("EUR", "60000.00"), Urgency.INSTANT));

        assertEquals(PaymentRail.TARGET2, decision.rail(),
                "whose limit bit first should not decide whether the payment settles today");
        assertTrue(decision.rejections().get(0).reason().contains("LHV Pank"),
                decision.rejections().get(0).reason());
    }

    @Test
    void aBeneficiaryOffTarget2FallsAllTheWayToTheBatchRail() {
        // ING is on the SEPA rails but not a TARGET2 participant, so there is no same-day
        // option to fall back to however urgent the payer is. Both refusals are recorded.
        RailDecision decision = route(ING_IBAN, "40000.00");

        assertEquals(PaymentRail.SEPA_SCT, decision.rail());
        assertEquals(2, decision.rejections().size());
        assertTrue(decision.rejections().get(0).reason().contains("ING Bank"));
        assertTrue(decision.rejections().get(1).reason().contains("not a TARGET2 participant"),
                decision.rejections().get(1).reason());
    }

    @Test
    void reachabilityIsStillCheckedIndependentlyOfAnyLimit() {
        // Nordea Sweden is not on the instant scheme at all; no limit would change that.
        String nordea = "SE2930000000000540398031";

        String reason = route(nordea, "100.00").rejections().get(0).reason();

        assertTrue(reason.contains("not reachable on SEPA Instant"), reason);
    }

    @Test
    void aStandardRequestIsUnaffectedByAnyonesInstantLimit() {
        RailDecision decision = router.route(RailRequest.of(new Iban(DEBTOR), new Iban(ING_IBAN),
                Money.of("EUR", "40000.00"), Urgency.STANDARD));

        assertEquals(PaymentRail.SEPA_SCT, decision.rail());
        assertFalse(decision.downgraded(), "the payer asked for the batch rail");
        assertTrue(decision.rejections().isEmpty());
    }
}
