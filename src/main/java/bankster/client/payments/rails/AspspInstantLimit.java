package bankster.client.payments.rails;

import java.util.Optional;

import bankster.client.payments.Money;

/**
 * What one ASPSP will accept on SEPA Instant.
 *
 * <p>Since the EPC removed the scheme-level ceiling, the maximum that applies to an
 * instant transfer is no longer a rule anyone can hard-code — it is the sending
 * institution's own policy on one side and the beneficiary institution's on the other,
 * and the binding constraint is whichever is lower. This record is the second of those.
 *
 * <p>{@code maximum} is deliberately optional. "This bank has not declared a limit" and
 * "this bank's limit is zero" are completely different statements, and a plain
 * {@link Money} field cannot tell them apart — a bank that accepts any amount would be
 * indistinguishable from one that accepts none.
 *
 * <p>{@code institutionName} is carried so that a refused transfer can say whose limit
 * stopped it. A BIC in that message would be useless to the payer.
 */
public record AspspInstantLimit(
        Bic bic,
        String institutionName,
        String country,
        Optional<Money> maximum) {

    public AspspInstantLimit {
        if (bic == null) {
            throw new IllegalArgumentException("An ASPSP limit needs a BIC");
        }
        if (maximum != null && maximum.isPresent() && !maximum.get().isPositive()) {
            // A declared ceiling of zero or less would mean "accepts nothing", which is a
            // reachability statement, not a limit. Refusing it here keeps the two apart.
            throw new IllegalArgumentException(
                    "A declared instant limit must be positive; use reachability to say a bank "
                            + "is not on the scheme at all");
        }
    }

    /** Whether {@code amount} is within this institution's declared ceiling. */
    public boolean accepts(Money amount) {
        return maximum.map(limit -> !amount.isGreaterThan(limit)).orElse(true);
    }

    /** True when the bank has told us nothing, so only our own limit constrains the transfer. */
    public boolean isUndeclared() {
        return maximum.isEmpty();
    }

    public String describeMaximum() {
        return maximum.map(Money::toString).orElse("not declared");
    }
}
