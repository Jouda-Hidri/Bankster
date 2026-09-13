package bankster.client.payments.cards;

import java.time.Instant;

import bankster.client.payments.Money;

/**
 * Money returned to the cardholder at the merchant's instruction.
 *
 * <p>A refund is a fresh transaction in the opposite direction, not an undo. It
 * has its own scheme message, its own settlement, and its own timing — days
 * later than the original, which is why a refunded customer does not see the
 * money back immediately.
 *
 * <p>The distinction from a chargeback matters commercially. A refund is
 * voluntary and costs the merchant the transaction fees; a chargeback is imposed
 * by the issuer, carries a penalty fee, and counts towards the ratio that gets a
 * merchant placed in a scheme monitoring programme. Refunding a complaining
 * customer promptly is almost always cheaper than arguing about it.
 */
public record Refund(
        String refundId,
        String paymentId,
        Money amount,
        String reason,
        Instant refundedAt) {
}
