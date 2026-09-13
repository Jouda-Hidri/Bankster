package bankster.client.payments.rails;

import java.time.Instant;
import java.util.List;

import bankster.client.payments.Money;

/**
 * Which rail a transfer was routed to, and what happened to the rails that were
 * preferred over it.
 *
 * <p>{@code rejections} is the part worth keeping. When a customer who asked for
 * an instant payment is told it will arrive tomorrow, the reason has to be
 * specific — "the beneficiary's bank is not reachable on SEPA Instant", not
 * "instant was unavailable" — both because the customer is entitled to know and
 * because a silent downgrade is indistinguishable from a bug.
 *
 * @param downgraded true when the transfer could not go on the rail that was
 *                   asked for
 */
public record RailDecision(
        PaymentRail rail,
        PaymentRail requestedRail,
        boolean downgraded,
        List<Rejection> rejections,
        Instant expectedSettlement,
        Money fee,
        String explanation) {

    public RailDecision {
        rejections = List.copyOf(rejections);
    }

    public record Rejection(PaymentRail rail, String reason) {
    }

    /** A one-line summary suitable for showing to the payer. */
    public String customerFacingSummary() {
        if (!downgraded) {
            return "Sending by " + rail.displayName() + ".";
        }
        String reason = rejections.isEmpty() ? "it was unavailable" : rejections.get(0).reason();
        return "Sending by " + rail.displayName() + " instead of "
                + requestedRail.displayName() + " because " + reason + ".";
    }
}
