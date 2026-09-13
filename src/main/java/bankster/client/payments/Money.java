package bankster.client.payments;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A monetary amount held in the currency's minor units.
 *
 * <p>Money is never represented as a {@code double} anywhere in this codebase:
 * binary floating point cannot express 0.10 exactly, so repeated addition of
 * card amounts drifts and a ledger that must balance to the cent stops
 * balancing. Amounts are therefore carried as a {@code long} count of minor
 * units (cents for EUR, yen for JPY) together with the ISO 4217 code, and every
 * arithmetic operation refuses to mix currencies.
 */
public record Money(String currency, long minorUnits) implements Comparable<Money> {

    /**
     * ISO 4217 minor-unit exponents that differ from the usual 2. Only the
     * currencies this application is likely to touch are listed; anything else
     * is assumed to have two decimals.
     */
    private static final Map<String, Integer> EXPONENTS = Map.of(
            "JPY", 0,
            "KRW", 0,
            "ISK", 0,
            "CLP", 0,
            "BHD", 3,
            "KWD", 3,
            "TND", 3);

    public Money {
        if (currency == null || currency.length() != 3) {
            throw new IllegalArgumentException("Currency must be a 3-letter ISO 4217 code, got: " + currency);
        }
        currency = currency.toUpperCase();
    }

    public static Money ofMinor(String currency, long minorUnits) {
        return new Money(currency, minorUnits);
    }

    /** Parses a decimal amount such as {@code "12.34"} into minor units. */
    public static Money of(String currency, String decimalAmount) {
        return of(currency, new BigDecimal(decimalAmount));
    }

    public static Money of(String currency, BigDecimal decimalAmount) {
        int exponent = exponentOf(currency);
        BigDecimal scaled = decimalAmount.setScale(exponent, RoundingMode.UNNECESSARY);
        return new Money(currency, scaled.movePointRight(exponent).longValueExact());
    }

    public static Money zero(String currency) {
        return new Money(currency, 0);
    }

    public static int exponentOf(String currency) {
        return EXPONENTS.getOrDefault(currency.toUpperCase(), 2);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(currency, Math.addExact(minorUnits, other.minorUnits));
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(currency, Math.subtractExact(minorUnits, other.minorUnits));
    }

    public Money negate() {
        return new Money(currency, Math.negateExact(minorUnits));
    }

    public Money abs() {
        return minorUnits < 0 ? negate() : this;
    }

    /**
     * Applies a rate expressed in basis points, rounding half-up — the
     * convention schemes and acquirers use for percentage fees.
     */
    public Money percentageBasisPoints(int basisPoints) {
        BigDecimal result = BigDecimal.valueOf(minorUnits)
                .multiply(BigDecimal.valueOf(basisPoints))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP);
        return new Money(currency, result.longValueExact());
    }

    /**
     * Splits the amount into {@code parts} whole minor units, handing the
     * remainder out one unit at a time so that the parts always sum back to the
     * original. Dropping the remainder is how split-settlement schemes silently
     * lose money.
     */
    public List<Money> allocate(int parts) {
        if (parts <= 0) {
            throw new IllegalArgumentException("Cannot allocate into " + parts + " parts");
        }
        long base = minorUnits / parts;
        long remainder = minorUnits - base * parts;
        List<Money> allocation = new ArrayList<>(parts);
        for (int i = 0; i < parts; i++) {
            long extra = i < Math.abs(remainder) ? Long.signum(remainder) : 0;
            allocation.add(new Money(currency, base + extra));
        }
        return allocation;
    }

    public boolean isZero() {
        return minorUnits == 0;
    }

    public boolean isPositive() {
        return minorUnits > 0;
    }

    public boolean isNegative() {
        return minorUnits < 0;
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return minorUnits > other.minorUnits;
    }

    public boolean isLessThan(Money other) {
        requireSameCurrency(other);
        return minorUnits < other.minorUnits;
    }

    public BigDecimal toDecimal() {
        return BigDecimal.valueOf(minorUnits).movePointLeft(exponentOf(currency));
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minorUnits, other.minorUnits);
    }

    @Override
    public String toString() {
        return toDecimal().toPlainString() + " " + currency;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + currency + " and " + other.currency);
        }
    }
}
