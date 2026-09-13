package bankster.client.payments.cards;

import java.time.YearMonth;

/**
 * Raw card credentials as they arrive from a checkout form.
 *
 * <p>This type is deliberately short-lived. It is accepted at the edge, handed
 * to the token vault, and dropped; nothing downstream holds one. The CVV in
 * particular may never be stored after authorization — that is not a guideline
 * but PCI DSS requirement 3.2, and storing it is the single fastest way to fail
 * an audit.
 */
public record CardDetails(Pan pan, YearMonth expiry, String holderName, String cvv) {

    public CardDetails {
        if (pan == null) {
            throw new IllegalArgumentException("Card details need a PAN");
        }
        if (expiry == null) {
            throw new IllegalArgumentException("Card details need an expiry");
        }
    }

    /** A card is usable through the last day of its expiry month. */
    public boolean isExpiredAt(YearMonth now) {
        return expiry.isBefore(now);
    }

    @Override
    public String toString() {
        return "CardDetails[" + pan.masked() + ", expires " + expiry + "]";
    }
}
