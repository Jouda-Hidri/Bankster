package bankster.client.payments.rails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import bankster.client.payments.Money;
import bankster.client.payments.rails.AspspLimitDirectory.Bound;
import bankster.client.payments.rails.AspspLimitDirectory.EffectiveLimit;

/**
 * Per-institution SEPA Instant limits: what the file says, and how the binding limit is
 * chosen from it.
 *
 * <p>The directory is read once and immutable, so there is nothing here about updating an
 * entry — changing a limit means editing the file and restarting. What is worth asserting
 * instead is that a bad line in the file cannot take the good ones down with it.
 */
class AspspLimitDirectoryTest {

    private static final Bic DEUTSCHE = new Bic("DEUTDEFF");   // 100,000
    private static final Bic ING = new Bic("INGBNL2A");        // 25,000
    private static final Bic BNP = new Bic("BNPAFRPP");        // no ceiling declared
    private static final Bic UNKNOWN = new Bic("XXXXDE22");

    private AspspLimitDirectory directory;

    @BeforeEach
    void setUp() {
        directory = new AspspLimitDirectory("test-aspsp-limits.json");
    }

    private Money eur(String amount) {
        return Money.of("EUR", amount);
    }

    // --- Loading ------------------------------------------------------------

    @Test
    void limitsAreReadFromTheFile() {
        assertEquals(eur("100000.00"), directory.forBic(DEUTSCHE).orElseThrow().maximum().orElseThrow());
        assertEquals(eur("25000.00"), directory.forBic(ING).orElseThrow().maximum().orElseThrow());
        assertEquals("ING Bank", directory.forBic(ING).orElseThrow().institutionName());
        assertEquals("NL", directory.forBic(ING).orElseThrow().country());
    }

    @Test
    void theShippedFileLoadsAndCoversTheInstantReachableInstitutions() {
        AspspLimitDirectory shipped = new AspspLimitDirectory();

        assertEquals(5, shipped.size());
        assertEquals(eur("15000.00"),
                shipped.forBic(new Bic("REVOLT21")).orElseThrow().maximum().orElseThrow());
        assertEquals(eur("50000.00"),
                shipped.forBic(new Bic("LHVBEE22")).orElseThrow().maximum().orElseThrow());
    }

    @Test
    void oneBadEntryDoesNotDiscardTheGoodOnes() {
        // The fixture contains an unusable BIC and an unparseable amount. Failing the whole
        // file over either would take every institution's limit down with it.
        assertEquals(4, directory.size());
        assertTrue(directory.forBic(new Bic("CRESCHZZ")).isEmpty(), "unparseable amount skipped");
        assertTrue(directory.forBic(DEUTSCHE).isPresent(), "and the rest still loaded");
    }

    @Test
    void aMissingFileLeavesTheDirectoryEmptyRatherThanFailingToStart() {
        // Every transfer then falls back to our own limit, which is conservative and still
        // correct. Refusing to start would take payments down over a data file.
        AspspLimitDirectory absent = new AspspLimitDirectory("no-such-file.json");

        assertEquals(0, absent.size());
        assertEquals(eur("100000.00"),
                absent.effectiveLimit(Optional.of(ING), eur("100000.00")).amount());
    }

    @Test
    void anInstitutionWithNoDeclaredCeilingIsNotTheSameAsOneWithNoEntry() {
        AspspInstantLimit bnp = directory.forBic(BNP).orElseThrow();

        assertTrue(bnp.isUndeclared(), "BNP is on record, having declared no ceiling");
        assertTrue(bnp.accepts(eur("999999.00")), "no ceiling means it constrains nothing");
        assertEquals("not declared", bnp.describeMaximum());

        assertTrue(directory.forBic(UNKNOWN).isEmpty(), "whereas this one is not on record at all");
    }

    @Test
    void anElevenCharacterBranchBicResolvesToItsInstitution() {
        // Limits are an institution-level policy, not a branch-level one.
        assertTrue(directory.forBic(new Bic("DEUTDEFFXXX")).isPresent());
        assertEquals(eur("100000.00"),
                directory.forBic(new Bic("DEUTDEFF500")).orElseThrow().maximum().orElseThrow());
    }

    // --- Choosing the binding limit ----------------------------------------

    @Test
    void theLowerOfTheTwoLimitsBinds() {
        EffectiveLimit againstIng = directory.effectiveLimit(Optional.of(ING), eur("100000.00"));

        assertEquals(eur("25000.00"), againstIng.amount());
        assertEquals(Bound.BENEFICIARY_INSTITUTION, againstIng.bound());
        assertTrue(againstIng.describeBound().contains("ING Bank"),
                "the explanation has to name the bank whose policy bit");
    }

    @Test
    void ourOwnLimitBindsWhenItIsTheTighterOne() {
        EffectiveLimit againstDeutsche = directory.effectiveLimit(Optional.of(DEUTSCHE), eur("5000.00"));

        assertEquals(eur("5000.00"), againstDeutsche.amount());
        assertEquals(Bound.SENDING_INSTITUTION, againstDeutsche.bound());
        assertEquals("this institution's instant transfer limit", againstDeutsche.describeBound());
    }

    @Test
    void equalLimitsAreAttributedToUsRatherThanArbitrarily() {
        EffectiveLimit equal = directory.effectiveLimit(Optional.of(DEUTSCHE), eur("100000.00"));

        assertEquals(eur("100000.00"), equal.amount());
        assertEquals(Bound.SENDING_INSTITUTION, equal.bound(),
                "nothing of theirs was breached, so it is not their refusal to explain");
    }

    @Test
    void anUndeclaredOrUnknownBeneficiaryContributesNoConstraint() {
        assertEquals(eur("100000.00"),
                directory.effectiveLimit(Optional.of(BNP), eur("100000.00")).amount());
        assertEquals(eur("100000.00"),
                directory.effectiveLimit(Optional.of(UNKNOWN), eur("100000.00")).amount());
        assertEquals(eur("100000.00"),
                directory.effectiveLimit(Optional.empty(), eur("100000.00")).amount());
    }

    @Test
    void acceptsAnswersWhetherAnAmountFitsUnderTheDeclaredCeiling() {
        AspspInstantLimit ing = directory.forBic(ING).orElseThrow();

        assertTrue(ing.accepts(eur("24999.99")));
        assertTrue(ing.accepts(eur("25000.00")), "the ceiling is inclusive");
        assertFalse(ing.accepts(eur("25000.01")));
    }

    @Test
    void aNonPositiveCeilingIsRefusedAsAReachabilityStatementInDisguise() {
        // "Accepts nothing" is a reachability fact, not a limit, and conflating them would
        // hide a bank leaving the scheme behind a number.
        assertThrows(IllegalArgumentException.class, () -> new AspspInstantLimit(
                ING, "ING Bank", "NL", Optional.of(eur("0.00"))));
        assertThrows(IllegalArgumentException.class, () -> new AspspInstantLimit(
                ING, "ING Bank", "NL", Optional.of(eur("-1.00"))));
    }
}
