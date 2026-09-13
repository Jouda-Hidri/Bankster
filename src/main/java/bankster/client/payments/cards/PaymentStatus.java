package bankster.client.payments.cards;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The lifecycle of a card payment.
 *
 * <p>The states exist because the money moves in stages, not in one step, and
 * treating the whole thing as a single "paid / not paid" flag is where most
 * payment bugs start. The shape worth internalising is:
 *
 * <pre>
 *   CREATED ─▶ REQUIRES_AUTHENTICATION ─▶ AUTHENTICATED ─▶ AUTHORIZED ─▶ CAPTURED ─▶ SETTLED
 *                                                             │             │           │
 *                                                          VOIDED      REFUNDED    CHARGED_BACK
 * </pre>
 *
 * <p>Transitions are declared rather than implied, so an illegal one — capturing
 * a voided payment, refunding something never captured — fails loudly instead
 * of corrupting the balance.
 */
public enum PaymentStatus {

    /** Intent recorded; nothing has been asked of the issuer yet. */
    CREATED,

    /** 3-D Secure returned a challenge; waiting for the cardholder. */
    REQUIRES_AUTHENTICATION,

    /** Authentication succeeded (or was exempted); ready to authorize. */
    AUTHENTICATED,

    /** The issuer refused. Whether it is worth retrying depends on {@link DeclineCode}. */
    AUTHORIZATION_DECLINED,

    /** Funds are held on the cardholder's line of credit. No money has moved. */
    AUTHORIZED,

    /** Some of the authorized amount has been claimed; more may follow. */
    PARTIALLY_CAPTURED,

    /** The full claim has been made and will be included in a settlement batch. */
    CAPTURED,

    /** Funds have arrived from the acquirer and been booked as cash. */
    SETTLED,

    /** Part of the captured amount has been returned to the cardholder. */
    PARTIALLY_REFUNDED,

    /** The whole captured amount has been returned. */
    REFUNDED,

    /** The hold was released before capture; the cardholder is never debited. */
    VOIDED,

    /** The hold lapsed unused — issuers release them after roughly a week. */
    EXPIRED,

    /** The cardholder disputed it and the issuer pulled the funds back. */
    CHARGED_BACK,

    /** Could not be processed — a technical failure, not an issuer decision. */
    FAILED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> TRANSITIONS = Map.ofEntries(
            Map.entry(CREATED, EnumSet.of(REQUIRES_AUTHENTICATION, AUTHENTICATED, AUTHORIZED,
                    AUTHORIZATION_DECLINED, FAILED)),
            Map.entry(REQUIRES_AUTHENTICATION, EnumSet.of(AUTHENTICATED, AUTHORIZATION_DECLINED, FAILED)),
            Map.entry(AUTHENTICATED, EnumSet.of(AUTHORIZED, AUTHORIZATION_DECLINED, FAILED)),
            Map.entry(AUTHORIZED, EnumSet.of(PARTIALLY_CAPTURED, CAPTURED, VOIDED, EXPIRED, FAILED)),
            Map.entry(PARTIALLY_CAPTURED, EnumSet.of(PARTIALLY_CAPTURED, CAPTURED, SETTLED, VOIDED, EXPIRED,
                    PARTIALLY_REFUNDED, REFUNDED, CHARGED_BACK)),
            Map.entry(CAPTURED, EnumSet.of(SETTLED, PARTIALLY_REFUNDED, REFUNDED, CHARGED_BACK)),
            Map.entry(SETTLED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED, CHARGED_BACK)),
            Map.entry(PARTIALLY_REFUNDED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED, CHARGED_BACK, SETTLED)),
            Map.entry(REFUNDED, EnumSet.of(CHARGED_BACK)),
            Map.entry(AUTHORIZATION_DECLINED, EnumSet.noneOf(PaymentStatus.class)),
            Map.entry(VOIDED, EnumSet.noneOf(PaymentStatus.class)),
            Map.entry(EXPIRED, EnumSet.noneOf(PaymentStatus.class)),
            Map.entry(CHARGED_BACK, EnumSet.of(SETTLED)),
            Map.entry(FAILED, EnumSet.noneOf(PaymentStatus.class)));

    public boolean canTransitionTo(PaymentStatus next) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(next);
    }

    /** True once nothing further can happen to the payment. */
    public boolean isTerminal() {
        return TRANSITIONS.getOrDefault(this, Set.of()).isEmpty();
    }

    /** True when funds have been claimed and the payment belongs in a settlement batch. */
    public boolean isCaptured() {
        return this == CAPTURED || this == PARTIALLY_CAPTURED || this == SETTLED
                || this == PARTIALLY_REFUNDED || this == REFUNDED;
    }
}
