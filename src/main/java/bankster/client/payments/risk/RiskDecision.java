package bankster.client.payments.risk;

/**
 * What to do with an attempt.
 *
 * <p>{@code CHALLENGE} is the reason this is three-valued rather than a
 * boolean. Declining a good customer costs more than the fraud it prevents, so
 * the middle band is escalated to 3-D Secure instead: the shopper proves who
 * they are, and liability moves to the issuer.
 */
public enum RiskDecision {

    APPROVE,
    CHALLENGE,
    DECLINE
}
