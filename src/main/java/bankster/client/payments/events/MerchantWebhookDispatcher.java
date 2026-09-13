package bankster.client.payments.events;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import bankster.client.payments.core.DomainEvent;
import bankster.client.payments.core.EventHandler;

/**
 * Delivers payment events to merchants as webhooks.
 *
 * <p>This is the consumer that makes the outbox's guarantees concrete. Webhook
 * delivery is the canonical at-least-once problem: the merchant's endpoint may be
 * slow, down, or may process the request successfully and then fail to return a
 * 200. We cannot distinguish the last case from an outright failure, so we retry,
 * so the merchant receives duplicates.
 *
 * <p>Which is why a well-behaved webhook consumer is told to deduplicate on the
 * event id, and why this dispatcher sends it. The outbox deduplicates on our side
 * too, so the same event is not dispatched twice by us — but a merchant integrating
 * over the network has to assume it will be, because any delivery that times out
 * will be tried again.
 *
 * <p>{@link #failDeliveriesOfType(String)} makes one event type fail on every
 * attempt, so that retry and eventual dead-lettering can be demonstrated without
 * waiting for a real endpoint to misbehave.
 */
@Component
public class MerchantWebhookDispatcher implements EventHandler {

    private static final Logger log = LoggerFactory.getLogger(MerchantWebhookDispatcher.class);

    /** Events merchants are told about. Internal events are not their business. */
    private static final List<String> SUBSCRIBED_PREFIXES = List.of(
            "payment.", "chargeback.", "settlement.");

    private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();

    /** Event types whose delivery always fails — for demonstrating the retry path. */
    private final Set<String> failingTypes = ConcurrentHashMap.newKeySet();

    public record Delivery(String eventId, String eventType, String subject, Instant at) {
    }

    @Override
    public String name() {
        return "merchant-webhooks";
    }

    @Override
    public boolean handles(String eventType) {
        return SUBSCRIBED_PREFIXES.stream().anyMatch(eventType::startsWith);
    }

    @Override
    public void handle(DomainEvent event) {
        if (failingTypes.contains(event.type())) {
            // Throwing tells the outbox the delivery failed, so it will retry and
            // eventually dead-letter.
            throw new IllegalStateException("merchant endpoint returned 503 for " + event.type());
        }
        deliveries.add(new Delivery(event.eventId(), event.type(), event.subject(), event.occurredAt()));
        log.debug("Webhook delivered: {} for {}", event.type(), event.subject());
    }

    public List<Delivery> deliveries() {
        return List.copyOf(deliveries);
    }

    public long deliveriesOf(String eventType) {
        return deliveries.stream().filter(delivery -> delivery.eventType().equals(eventType)).count();
    }

    /** Simulates a merchant endpoint that rejects every delivery of one event type. */
    public void failDeliveriesOfType(String eventType) {
        failingTypes.add(eventType);
    }

    /** Brings the simulated endpoint back up, so dead letters can be replayed. */
    public void recover() {
        failingTypes.clear();
    }

    public Set<String> failingTypes() {
        return Set.copyOf(failingTypes);
    }
}
