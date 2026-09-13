package bankster.client.payments.cards;

import java.time.Instant;

import bankster.client.payments.Money;

/**
 * A claim against an authorization.
 *
 * <p>There can be several per payment. Split shipments are the usual reason: a
 * retailer authorizes the basket, then captures each parcel as it leaves the
 * warehouse, because money may only be taken for goods actually dispatched.
 *
 * <p>{@code settlementBatchId} is filled in later, when the capture is swept
 * into a batch. Until then it is null, and that is precisely the set a
 * reconciliation run looks at: captured, not yet settled.
 */
public final class Capture {

    private final String captureId;
    private final String paymentId;
    private final Money amount;
    private final Instant capturedAt;
    private String settlementBatchId;

    public Capture(String captureId, String paymentId, Money amount, Instant capturedAt) {
        this.captureId = captureId;
        this.paymentId = paymentId;
        this.amount = amount;
        this.capturedAt = capturedAt;
    }

    public String captureId() {
        return captureId;
    }

    public String paymentId() {
        return paymentId;
    }

    public Money amount() {
        return amount;
    }

    public Instant capturedAt() {
        return capturedAt;
    }

    public String settlementBatchId() {
        return settlementBatchId;
    }

    public boolean isSettled() {
        return settlementBatchId != null;
    }

    /** Called by the settlement sweep when this capture is swept into a batch. */
    public void assignToBatch(String settlementBatchId) {
        this.settlementBatchId = settlementBatchId;
    }
}
