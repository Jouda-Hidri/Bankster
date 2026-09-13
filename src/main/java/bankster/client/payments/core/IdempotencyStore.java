package bankster.client.payments.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

/**
 * Idempotency keys for state-changing payment operations.
 *
 * <p>Payment APIs are called over networks that lose responses. A client that
 * times out cannot tell whether its charge was applied, so it retries — and
 * without protection the customer is charged twice. The fix is for the client
 * to attach a key it generates once per logical operation and reuses on every
 * retry; the server then performs the work at most once and replays the stored
 * response thereafter.
 *
 * <p>Three cases have to be distinguished, and getting any of them wrong
 * reintroduces the double charge:
 *
 * <ul>
 *   <li><b>Replay.</b> Key seen, work finished — return the original response
 *       verbatim. Re-running would charge again.</li>
 *   <li><b>In flight.</b> Key seen, work still running — this is a concurrent
 *       duplicate, not a sequential retry. It must be rejected rather than
 *       allowed to run in parallel, because both would reach the acquirer.</li>
 *   <li><b>Conflict.</b> Key seen but with a different request body — the
 *       client has reused a key for different work. Replaying the old response
 *       would be a lie and executing the new request would break the client's
 *       own at-most-once assumption, so it is an error.</li>
 * </ul>
 *
 * <p>Records expire: keys are retained long enough to cover realistic retry
 * windows, not forever.
 */
@Component
public class IdempotencyStore {

    /** How long a completed result stays replayable. Stripe and Adyen both use 24h. */
    public static final Duration RETENTION = Duration.ofHours(24);

    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public IdempotencyStore(Clock clock) {
        this.clock = clock;
    }

    private enum State {
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    private record Entry(String fingerprint, State state, Object response, Instant createdAt) {
    }

    /** What a caller learns about how its request was handled. */
    public record Outcome<T>(T value, boolean replayed) {
    }

    /**
     * Runs {@code operation} at most once for {@code key}.
     *
     * @param fingerprint a stable digest of the request, used to detect a key
     *                    being reused for different work
     */
    @SuppressWarnings("unchecked")
    public <T> Outcome<T> execute(String key, String fingerprint, Supplier<T> operation) {
        if (key == null || key.isBlank()) {
            // No key supplied: the caller has opted out of the guarantee.
            return new Outcome<>(operation.get(), false);
        }

        purgeExpired();
        Instant now = clock.instant();

        Entry claimed = entries.compute(key, (ignored, existing) -> {
            if (existing == null) {
                return new Entry(fingerprint, State.IN_PROGRESS, null, now);
            }
            if (!existing.fingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException(
                        "Idempotency key " + key + " was already used for a different request");
            }
            if (existing.state() == State.IN_PROGRESS) {
                throw new IdempotencyConflictException(
                        "Idempotency key " + key + " is already being processed");
            }
            if (existing.state() == State.FAILED) {
                // A failed attempt left no side effects worth replaying, so the
                // caller is allowed to try again under the same key.
                return new Entry(fingerprint, State.IN_PROGRESS, null, now);
            }
            return existing;
        });

        if (claimed.state() == State.COMPLETED) {
            return new Outcome<>((T) claimed.response(), true);
        }

        try {
            T result = operation.get();
            entries.put(key, new Entry(fingerprint, State.COMPLETED, result, now));
            return new Outcome<>(result, false);
        } catch (RuntimeException e) {
            entries.put(key, new Entry(fingerprint, State.FAILED, null, now));
            throw e;
        }
    }

    /**
     * Whether a completed result is currently replayable for this key.
     *
     * <p>Expiry is applied here too, not just in {@link #execute}: a query that
     * reported a key as still held while {@code execute} would happily re-run it
     * would be worse than no query at all.
     */
    public boolean hasCompleted(String key) {
        purgeExpired();
        Entry entry = entries.get(key);
        return entry != null && entry.state() == State.COMPLETED;
    }

    public int size() {
        purgeExpired();
        return entries.size();
    }

    private void purgeExpired() {
        Instant cutoff = clock.instant().minus(RETENTION);
        entries.entrySet().removeIf(entry -> entry.getValue().createdAt().isBefore(cutoff));
    }

    /** Raised when an idempotency key is reused incompatibly. Maps to HTTP 409. */
    public static class IdempotencyConflictException extends RuntimeException {

        public IdempotencyConflictException(String message) {
            super(message);
        }
    }
}
