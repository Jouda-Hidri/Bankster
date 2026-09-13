package bankster.client.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The arithmetic every amount in the system goes through. Worth testing in detail
 * because a rounding or allocation defect here is invisible per transaction and
 * shows up months later as a ledger that will not balance.
 */
class MoneyTest {

    @Test
    void parsesDecimalAmountsIntoMinorUnits() {
        assertEquals(1234, Money.of("EUR", "12.34").minorUnits());
        assertEquals(0, Money.of("EUR", "0.00").minorUnits());
        assertEquals(-550, Money.of("EUR", "-5.50").minorUnits());
    }

    @Test
    void respectsCurrenciesWithoutMinorUnits() {
        // JPY has no subdivision, so 1000 yen is 1000 minor units, not 100000.
        assertEquals(1000, Money.of("JPY", "1000").minorUnits());
        assertEquals(0, Money.exponentOf("JPY"));
        assertEquals(3, Money.exponentOf("KWD"));
        assertEquals(2, Money.exponentOf("ZAR"), "unlisted currencies default to two decimals");
    }

    @Test
    void refusesAmountsWithMorePrecisionThanTheCurrencyHas() {
        // Silently rounding would lose a tenth of a cent per transaction.
        assertThrows(ArithmeticException.class, () -> Money.of("EUR", "12.345"));
    }

    @Test
    void refusesToMixCurrencies() {
        Money euros = Money.of("EUR", "10.00");
        Money dollars = Money.of("USD", "10.00");

        assertThrows(IllegalArgumentException.class, () -> euros.plus(dollars));
        assertThrows(IllegalArgumentException.class, () -> euros.minus(dollars));
        assertThrows(IllegalArgumentException.class, () -> euros.isGreaterThan(dollars));
    }

    @Test
    void requiresAThreeLetterCurrencyCode() {
        assertThrows(IllegalArgumentException.class, () -> Money.ofMinor("EURO", 1));
        assertThrows(IllegalArgumentException.class, () -> Money.ofMinor(null, 1));
    }

    @Test
    void addsAndSubtractsExactly() {
        Money total = Money.of("EUR", "0.10")
                .plus(Money.of("EUR", "0.20"))
                .plus(Money.of("EUR", "0.30"));

        // The case binary floating point gets wrong: 0.1 + 0.2 + 0.3 != 0.6 in doubles.
        assertEquals(Money.of("EUR", "0.60"), total);
        assertEquals(60, total.minorUnits());
    }

    @Test
    void appliesBasisPointRatesWithHalfUpRounding() {
        // 0.9% of 18.50 is 0.1665, which rounds to 0.17 the way schemes round fees.
        assertEquals(Money.of("EUR", "0.17"), Money.of("EUR", "18.50").percentageBasisPoints(90));
        // 0.2% of 100.00 is exactly 0.20.
        assertEquals(Money.of("EUR", "0.20"), Money.of("EUR", "100.00").percentageBasisPoints(20));
    }

    @Test
    void allocationAlwaysSumsBackToTheOriginal() {
        // 10.00 split three ways cannot divide evenly; the remainder must not vanish.
        List<Money> parts = Money.of("EUR", "10.00").allocate(3);

        assertEquals(3, parts.size());
        assertEquals(List.of(Money.of("EUR", "3.34"), Money.of("EUR", "3.33"), Money.of("EUR", "3.33")),
                parts);
        assertEquals(Money.of("EUR", "10.00"),
                parts.stream().reduce(Money.zero("EUR"), Money::plus));
    }

    @Test
    void allocationOfNegativeAmountsAlsoSumsBack() {
        List<Money> parts = Money.of("EUR", "-10.00").allocate(3);

        assertEquals(Money.of("EUR", "-10.00"),
                parts.stream().reduce(Money.zero("EUR"), Money::plus));
    }

    @Test
    void allocationRejectsNonPositivePartCounts() {
        assertThrows(IllegalArgumentException.class, () -> Money.of("EUR", "1.00").allocate(0));
        assertThrows(IllegalArgumentException.class, () -> Money.of("EUR", "1.00").allocate(-2));
    }

    @Test
    void comparisonsAndSignChecks() {
        Money ten = Money.of("EUR", "10.00");
        Money twenty = Money.of("EUR", "20.00");

        assertTrue(twenty.isGreaterThan(ten));
        assertTrue(ten.isLessThan(twenty));
        assertFalse(ten.isGreaterThan(ten));
        assertTrue(Money.zero("EUR").isZero());
        assertTrue(ten.negate().isNegative());
        assertEquals(ten, ten.negate().abs());
    }

    @Test
    void formatsWithTheCurrencyAndNoLocaleSurprises() {
        assertEquals("12.34 EUR", Money.of("EUR", "12.34").toString());
        assertEquals("1000 JPY", Money.of("JPY", "1000").toString());
    }

    @Test
    void overflowIsRaisedRatherThanWrappingSilently() {
        Money huge = Money.ofMinor("EUR", Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> huge.plus(Money.ofMinor("EUR", 1)));
    }
}
