package bankster.client.payments.core;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Something that happened, stated in the past tense.
 *
 * <p>The {@code eventId} is the deduplication key consumers use, which is why
 * it is assigned once at creation and never regenerated on redelivery — a
 * redelivered event carries the id of the original.
 */
public record DomainEvent(
        String eventId,
        String type,
        String subject,
        Map<String, String> payload,
        Instant occurredAt) {

    public DomainEvent {
        payload = Map.copyOf(payload);
    }

    public static DomainEvent of(String type, String subject, Map<String, String> payload, Instant occurredAt) {
        return new DomainEvent(UUID.randomUUID().toString(), type, subject, payload, occurredAt);
    }

    public String payloadValue(String key) {
        return payload.get(key);
    }
}
