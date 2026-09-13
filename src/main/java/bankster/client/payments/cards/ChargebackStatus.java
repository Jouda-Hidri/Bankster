package bankster.client.payments.cards;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Stages of a dispute.
 *
 * <p>The escalation exists because each round costs both sides money, so both
 * are pushed towards settling early. Arbitration in particular carries a fee of
 * several hundred euro levied on whoever loses, which makes fighting a small
 * dispute to the end irrational regardless of who is right.
 *
 * <pre>
 *   RECEIVED ─▶ REPRESENTED ─▶ PRE_ARBITRATION ─▶ ARBITRATION ─▶ WON / LOST
 *      │
 *      ├─▶ ACCEPTED (merchant concedes)
 *      └─▶ EXPIRED  (merchant missed the deadline)
 * </pre>
 */
public enum ChargebackStatus {

    /** The issuer has claimed the funds back; the merchant has been debited. */
    RECEIVED,

    /** The merchant conceded rather than spend money defending it. */
    ACCEPTED,

    /** Defended with evidence — the second presentment. */
    REPRESENTED,

    /** The issuer rejected the defence and is pressing on. */
    PRE_ARBITRATION,

    /** Referred to the scheme to rule. The loser pays the arbitration fee. */
    ARBITRATION,

    /** Resolved in the merchant's favour; the funds return. */
    WON,

    /** Resolved against the merchant; the claw-back stands. */
    LOST,

    /** The representment window closed with no defence filed. */
    EXPIRED;

    private static final Map<ChargebackStatus, Set<ChargebackStatus>> TRANSITIONS = Map.of(
            RECEIVED, EnumSet.of(ACCEPTED, REPRESENTED, EXPIRED),
            REPRESENTED, EnumSet.of(WON, LOST, PRE_ARBITRATION),
            PRE_ARBITRATION, EnumSet.of(ARBITRATION, WON, LOST),
            ARBITRATION, EnumSet.of(WON, LOST),
            ACCEPTED, EnumSet.of(LOST),
            EXPIRED, EnumSet.of(LOST),
            WON, EnumSet.noneOf(ChargebackStatus.class),
            LOST, EnumSet.noneOf(ChargebackStatus.class));

    public boolean canTransitionTo(ChargebackStatus next) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(next);
    }

    public boolean isResolved() {
        return this == WON || this == LOST;
    }
}
