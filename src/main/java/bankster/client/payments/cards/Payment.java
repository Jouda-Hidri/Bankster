package bankster.client.payments.cards;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import bankster.client.payments.Money;
import bankster.client.payments.risk.RiskAssessment;

/**
 * A card payment and everything that has happened to it.
 *
 * <p>The amounts are kept as three running totals — authorized, captured,
 * refunded — rather than being recomputed from a list on demand, and every
 * operation is checked against them. That is what makes over-capture and
 * over-refund impossible here rather than discovered later by the scheme.
 *
 * <p>{@code history} records every state change with its reason. Payments are
 * the most frequently disputed objects in any system that has them, and "why is
 * this payment in this state" is asked constantly — by support, by the merchant,
 * by an auditor reviewing a dispute. Reconstructing it from logs after the fact
 * is guesswork; recording it as it happens is not.
 */
public final class Payment {

    private final String paymentId;
    private final String merchantId;
    private final String orderReference;
    private final CardToken card;
    private final Money requestedAmount;
    private final String mcc;
    private final Instant createdAt;
    private final boolean cardholderPresent;
    private final boolean recurring;

    private PaymentStatus status = PaymentStatus.CREATED;
    private Money authorizedAmount;
    private Money capturedAmount;
    private Money refundedAmount;

    private String processorId;
    private String processorReference;
    private String authorizationCode;
    private Instant authorizedAt;
    private Instant authorizationExpiresAt;

    private ThreeDSecureResult authentication;
    private RiskAssessment risk;
    private DeclineCode declineCode;
    private String routingExplanation;

    private final List<Capture> captures = new ArrayList<>();
    private final List<Refund> refunds = new ArrayList<>();
    private final List<StatusTransition> history = new ArrayList<>();

    public Payment(String paymentId, String merchantId, String orderReference, CardToken card,
                   Money requestedAmount, String mcc, boolean cardholderPresent, boolean recurring,
                   Instant createdAt) {
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.orderReference = orderReference;
        this.card = card;
        this.requestedAmount = requestedAmount;
        this.mcc = mcc;
        this.cardholderPresent = cardholderPresent;
        this.recurring = recurring;
        this.createdAt = createdAt;
        this.authorizedAmount = Money.zero(requestedAmount.currency());
        this.capturedAmount = Money.zero(requestedAmount.currency());
        this.refundedAmount = Money.zero(requestedAmount.currency());
        this.history.add(new StatusTransition(null, PaymentStatus.CREATED, "payment intent created", createdAt));
    }

    /** One step in the payment's life, with the reason it was taken. */
    public record StatusTransition(PaymentStatus from, PaymentStatus to, String reason, Instant at) {
    }

    /**
     * Moves the payment to a new state, refusing transitions the lifecycle does
     * not allow.
     */
    public void transitionTo(PaymentStatus next, String reason, Instant at) {
        if (status == next && next != PaymentStatus.PARTIALLY_CAPTURED
                && next != PaymentStatus.PARTIALLY_REFUNDED) {
            return;
        }
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Payment " + paymentId + " cannot go from " + status + " to " + next);
        }
        history.add(new StatusTransition(status, next, reason, at));
        status = next;
    }

    // --- Amount bookkeeping ----------------------------------------------

    void recordAuthorization(ProcessorResponse response, Instant at, Instant expiresAt) {
        this.processorId = response.processorId();
        this.processorReference = response.processorReference();
        this.authorizationCode = response.authorizationCode();
        this.authorizedAmount = response.amount();
        this.authorizedAt = at;
        this.authorizationExpiresAt = expiresAt;
    }

    void recordCapture(Capture capture) {
        captures.add(capture);
        capturedAmount = capturedAmount.plus(capture.amount());
    }

    void recordRefund(Refund refund) {
        refunds.add(refund);
        refundedAmount = refundedAmount.plus(refund.amount());
    }

    /** Authorized but not yet claimed — the part that would lapse on expiry. */
    public Money uncapturedAmount() {
        return authorizedAmount.minus(capturedAmount);
    }

    /** Captured and not yet returned — the most the merchant could still refund. */
    public Money refundableAmount() {
        return capturedAmount.minus(refundedAmount);
    }

    public boolean isFullyCaptured() {
        return !authorizedAmount.isZero() && capturedAmount.equals(authorizedAmount);
    }

    public boolean isFullyRefunded() {
        return !capturedAmount.isZero() && refundedAmount.equals(capturedAmount);
    }

    public boolean isAuthorizationExpired(Instant now) {
        return authorizationExpiresAt != null && now.isAfter(authorizationExpiresAt);
    }

    // --- Accessors --------------------------------------------------------

    public String paymentId() {
        return paymentId;
    }

    public String merchantId() {
        return merchantId;
    }

    public String orderReference() {
        return orderReference;
    }

    public CardToken card() {
        return card;
    }

    public Money requestedAmount() {
        return requestedAmount;
    }

    public String mcc() {
        return mcc;
    }

    public boolean cardholderPresent() {
        return cardholderPresent;
    }

    public boolean recurring() {
        return recurring;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public PaymentStatus status() {
        return status;
    }

    public Money authorizedAmount() {
        return authorizedAmount;
    }

    public Money capturedAmount() {
        return capturedAmount;
    }

    public Money refundedAmount() {
        return refundedAmount;
    }

    public String processorId() {
        return processorId;
    }

    public String processorReference() {
        return processorReference;
    }

    public String authorizationCode() {
        return authorizationCode;
    }

    public Instant authorizedAt() {
        return authorizedAt;
    }

    public Instant authorizationExpiresAt() {
        return authorizationExpiresAt;
    }

    public ThreeDSecureResult authentication() {
        return authentication;
    }

    void setAuthentication(ThreeDSecureResult authentication) {
        this.authentication = authentication;
    }

    public RiskAssessment risk() {
        return risk;
    }

    void setRisk(RiskAssessment risk) {
        this.risk = risk;
    }

    public DeclineCode declineCode() {
        return declineCode;
    }

    void setDeclineCode(DeclineCode declineCode) {
        this.declineCode = declineCode;
    }

    public String routingExplanation() {
        return routingExplanation;
    }

    void setRoutingExplanation(String routingExplanation) {
        this.routingExplanation = routingExplanation;
    }

    public List<Capture> captures() {
        return Collections.unmodifiableList(captures);
    }

    public List<Refund> refunds() {
        return Collections.unmodifiableList(refunds);
    }

    public List<StatusTransition> history() {
        return Collections.unmodifiableList(history);
    }
}
