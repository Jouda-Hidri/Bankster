package bankster.client.payments.rails;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import bankster.client.payments.Money;

/**
 * Foreign exchange for cross-border payments.
 *
 * <p>The distinction that matters is between the <b>mid rate</b> — the midpoint
 * of the interbank market, the number a customer sees when they look up "the
 * exchange rate" — and the <b>client rate</b> they are actually given, which is
 * the mid rate moved against them by a spread. The spread, not an explicit fee,
 * is where most of the revenue on a currency conversion sits, which is why a
 * transfer advertised as "no fees" can still be expensive.
 *
 * <p>Conversion is done in minor units with explicit rounding, and the rounding
 * direction is chosen deliberately: the amount the customer receives is rounded
 * down rather than to nearest, so that rounding can never create money the
 * institution has not got. Across millions of conversions, rounding to nearest
 * leaks a real balance-sheet hole.
 */
@Component
public class FxRates {

    /** Indicative mid rates against the euro. */
    private final Map<String, BigDecimal> midRatesAgainstEur = new ConcurrentHashMap<>(Map.of(
            "EUR", BigDecimal.ONE,
            "USD", new BigDecimal("1.0850"),
            "GBP", new BigDecimal("0.8420"),
            "CHF", new BigDecimal("0.9380"),
            "JPY", new BigDecimal("162.40"),
            "SEK", new BigDecimal("11.2100"),
            "NOK", new BigDecimal("11.7400"),
            "DKK", new BigDecimal("7.4590")));

    /** The spread applied to the mid rate, in basis points. */
    private volatile int spreadBasisPoints = 50;

    /**
     * @param midRate     the interbank midpoint
     * @param clientRate  the rate actually applied, mid less the spread
     * @param converted   what the beneficiary receives
     * @param spreadCost  what the spread cost the customer, in the source currency
     */
    public record FxQuote(
            String fromCurrency,
            String toCurrency,
            BigDecimal midRate,
            BigDecimal clientRate,
            int spreadBasisPoints,
            Money source,
            Money converted,
            Money spreadCost) {

        public boolean isConversion() {
            return !fromCurrency.equals(toCurrency);
        }
    }

    public boolean supports(String currency) {
        return midRatesAgainstEur.containsKey(currency.toUpperCase());
    }

    /** The mid rate from one currency to another, crossed through the euro. */
    public BigDecimal midRate(String from, String to) {
        BigDecimal fromRate = require(from);
        BigDecimal toRate = require(to);
        return toRate.divide(fromRate, 8, RoundingMode.HALF_UP);
    }

    /**
     * Quotes a conversion.
     *
     * <p>The spread always moves the rate against the customer, whichever
     * direction the conversion runs — which is why it is subtracted from the
     * rate rather than added to the fee.
     */
    public FxQuote quote(Money amount, String toCurrency) {
        String from = amount.currency();
        String to = toCurrency.toUpperCase();

        if (from.equals(to)) {
            return new FxQuote(from, to, BigDecimal.ONE, BigDecimal.ONE, 0,
                    amount, amount, Money.zero(from));
        }

        BigDecimal mid = midRate(from, to);
        BigDecimal spreadFactor = BigDecimal.ONE.subtract(
                BigDecimal.valueOf(spreadBasisPoints).divide(BigDecimal.valueOf(10_000), 8, RoundingMode.HALF_UP));
        BigDecimal clientRate = mid.multiply(spreadFactor).setScale(8, RoundingMode.HALF_UP);

        Money convertedAtClientRate = convert(amount, to, clientRate);
        Money convertedAtMid = convert(amount, to, mid);

        // What the spread cost, expressed back in the source currency so it is
        // comparable with the explicit fees.
        Money spreadInTarget = convertedAtMid.minus(convertedAtClientRate);
        Money spreadCost = convert(spreadInTarget, from, midRate(to, from));

        return new FxQuote(from, to, mid, clientRate, spreadBasisPoints,
                amount, convertedAtClientRate, spreadCost);
    }

    /**
     * Applies a rate, rounding the result down.
     *
     * <p>Rounding down rather than half-up is deliberate: the institution must
     * never pay out more than the conversion produced.
     */
    private Money convert(Money amount, String toCurrency, BigDecimal rate) {
        int fromExponent = Money.exponentOf(amount.currency());
        int toExponent = Money.exponentOf(toCurrency);

        BigDecimal sourceDecimal = BigDecimal.valueOf(amount.minorUnits()).movePointLeft(fromExponent);
        BigDecimal targetDecimal = sourceDecimal.multiply(rate);
        long minorUnits = targetDecimal.movePointRight(toExponent)
                .setScale(0, RoundingMode.DOWN)
                .longValueExact();
        return Money.ofMinor(toCurrency, minorUnits);
    }

    public int spreadBasisPoints() {
        return spreadBasisPoints;
    }

    public void setSpreadBasisPoints(int spreadBasisPoints) {
        this.spreadBasisPoints = spreadBasisPoints;
    }

    public void setMidRate(String currency, BigDecimal rateAgainstEur) {
        midRatesAgainstEur.put(currency.toUpperCase(), rateAgainstEur);
    }

    private BigDecimal require(String currency) {
        BigDecimal rate = midRatesAgainstEur.get(currency.toUpperCase());
        if (rate == null) {
            throw new IllegalArgumentException("No FX rate available for " + currency);
        }
        return rate;
    }
}
