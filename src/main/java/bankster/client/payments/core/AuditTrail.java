package bankster.client.payments.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

/**
 * A tamper-evident record of who did what to which payment object.
 *
 * <p>Regulators and card schemes both require that the history of a payment can
 * be reconstructed after the fact, and a dispute is usually argued on exactly
 * this evidence. Application logs are not sufficient: they are mutable,
 * rotated, and often unstructured.
 *
 * <p>The trail is append-only and hash-chained, so an entry cannot be edited or
 * quietly dropped without {@link #verifyIntegrity()} noticing. Metadata is kept
 * as sorted key/value pairs so the digest is stable regardless of insertion
 * order.
 */
@Component
public class AuditTrail {

    static final String GENESIS_HASH = "0".repeat(64);

    private final Clock clock;
    private final List<AuditEvent> events = new ArrayList<>();

    public AuditTrail(Clock clock) {
        this.clock = clock;
    }

    /**
     * @param actor    who caused it — a user id, merchant id, or a system component
     * @param action   what happened, e.g. {@code payment.captured}
     * @param subject  the object it happened to, e.g. a payment id
     */
    public record AuditEvent(
            long sequence,
            Instant at,
            String actor,
            String action,
            String subject,
            Map<String, String> metadata,
            String previousHash,
            String hash) {

        String computeHash() {
            StringBuilder payload = new StringBuilder()
                    .append(sequence).append('|')
                    .append(at).append('|')
                    .append(actor).append('|')
                    .append(action).append('|')
                    .append(subject).append('|')
                    .append(previousHash).append('|');
            new TreeMap<>(metadata).forEach((key, value) ->
                    payload.append(key).append('=').append(value).append(';'));
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(digest.digest(payload.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is required by the JVM specification", e);
            }
        }
    }

    public synchronized AuditEvent record(String actor, String action, String subject, Map<String, String> metadata) {
        Map<String, String> safeMetadata = metadata == null
                ? Map.of()
                : new LinkedHashMap<>(metadata);
        String previousHash = events.isEmpty() ? GENESIS_HASH : events.get(events.size() - 1).hash();
        long sequence = events.size() + 1L;

        AuditEvent unhashed = new AuditEvent(
                sequence, clock.instant(), actor, action, subject, safeMetadata, previousHash, "");
        AuditEvent event = new AuditEvent(
                sequence, unhashed.at(), actor, action, subject, safeMetadata, previousHash, unhashed.computeHash());
        events.add(event);
        return event;
    }

    public AuditEvent record(String actor, String action, String subject) {
        return record(actor, action, subject, Map.of());
    }

    /** The full history of one object, oldest first — the view a dispute needs. */
    public synchronized List<AuditEvent> forSubject(String subject) {
        return events.stream().filter(event -> event.subject().equals(subject)).toList();
    }

    public synchronized List<AuditEvent> events() {
        return List.copyOf(events);
    }

    public synchronized boolean verifyIntegrity() {
        String expectedPrevious = GENESIS_HASH;
        for (AuditEvent event : events) {
            if (!event.previousHash().equals(expectedPrevious) || !event.computeHash().equals(event.hash())) {
                return false;
            }
            expectedPrevious = event.hash();
        }
        return true;
    }
}
