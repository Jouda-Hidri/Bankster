package bankster.client.payments.orchestration;

/**
 * How to choose between processors that could all handle a transaction.
 *
 * <p>There is no universally correct answer, which is why this is a policy knob
 * rather than a hard-coded comparator. The cheapest processor is not the most
 * profitable one if it approves fewer transactions: on a €50 basket, a one
 * percentage point difference in approval rate is worth far more than ten basis
 * points of pricing. Conversely, on high-volume low-margin traffic where
 * approval rates are indistinguishable, cost is the only thing left to optimise.
 */
public enum RoutingStrategy {

    /**
     * Highest observed approval rate first. The usual default, because a
     * declined payment earns nothing however cheaply it was attempted.
     */
    HIGHEST_APPROVAL,

    /** Widest margin after interchange and scheme fees. */
    LOWEST_COST,

    /** Shortest time to funds — what a merchant with a cash-flow problem wants. */
    FASTEST_SETTLEMENT
}
