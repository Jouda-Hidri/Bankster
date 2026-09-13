package bankster.client.payments.core;

/**
 * A consumer of domain events.
 *
 * <p>{@link #name()} identifies the subscription. Deduplication is per handler,
 * not global: two handlers must each get their own copy of an event, but
 * neither may see the same event twice.
 */
public interface EventHandler {

    String name();

    /** Types this handler wants, or an empty match-all set. */
    default boolean handles(String eventType) {
        return true;
    }

    /**
     * Processes the event. Throwing signals a retryable failure — the outbox
     * will redeliver, so implementations must tolerate being called again for
     * the same event after a partial failure.
     */
    void handle(DomainEvent event);
}
