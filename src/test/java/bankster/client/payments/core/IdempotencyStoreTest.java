package bankster.client.payments.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import bankster.client.payments.TestClock;
import bankster.client.payments.core.IdempotencyStore.IdempotencyConflictException;

/**
 * The protection against double-charging a customer whose client timed out and
 * retried. Each of the three cases below reintroduces the double charge if it is
 * handled wrongly.
 */
class IdempotencyStoreTest {

    private TestClock clock;
    private IdempotencyStore store;

    @BeforeEach
    void setUp() {
        clock = TestClock.businessDayMorning();
        store = new IdempotencyStore(clock);
    }

    @Test
    void firstCallExecutesAndIsNotFlaggedAsAReplay() {
        AtomicInteger executions = new AtomicInteger();

        IdempotencyStore.Outcome<String> outcome =
                store.execute("key-1", "fingerprint", () -> {
                    executions.incrementAndGet();
                    return "charged";
                });

        assertEquals("charged", outcome.value());
        assertFalse(outcome.replayed());
        assertEquals(1, executions.get());
    }

    @Test
    void repeatedCallReplaysTheStoredResponseWithoutExecutingAgain() {
        AtomicInteger executions = new AtomicInteger();

        store.execute("key-1", "fingerprint", () -> "charge-" + executions.incrementAndGet());
        IdempotencyStore.Outcome<String> replay =
                store.execute("key-1", "fingerprint", () -> "charge-" + executions.incrementAndGet());

        assertEquals("charge-1", replay.value(), "the original response, verbatim");
        assertTrue(replay.replayed());
        assertEquals(1, executions.get(), "re-running would charge the customer twice");
    }

    @Test
    void reusingAKeyForADifferentRequestIsAConflict() {
        store.execute("key-1", "charge-10-eur", () -> "ok");

        IdempotencyConflictException failure = assertThrows(IdempotencyConflictException.class,
                () -> store.execute("key-1", "charge-500-eur", () -> "ok"));

        assertTrue(failure.getMessage().contains("different request"), failure.getMessage());
    }

    @Test
    void aConcurrentDuplicateIsRejectedRatherThanRunInParallel() {
        // The inner call models a second request arriving while the first is still
        // running. Allowing it through would send two authorizations to the acquirer.
        IdempotencyConflictException failure = assertThrows(IdempotencyConflictException.class,
                () -> store.execute("key-1", "fingerprint", () ->
                        store.execute("key-1", "fingerprint", () -> "inner").value()));

        assertTrue(failure.getMessage().contains("already being processed"), failure.getMessage());
    }

    @Test
    void aFailedAttemptLeavesTheKeyAvailableForAGenuineRetry() {
        assertThrows(IllegalStateException.class, () ->
                store.execute("key-1", "fingerprint", () -> {
                    throw new IllegalStateException("acquirer timed out");
                }));

        // The failure left no side effect worth replaying, so the caller may try again.
        IdempotencyStore.Outcome<String> retry =
                store.execute("key-1", "fingerprint", () -> "charged on retry");

        assertEquals("charged on retry", retry.value());
        assertFalse(retry.replayed());
    }

    @Test
    void anAbsentKeyMeansTheCallerOptedOutOfTheGuarantee() {
        AtomicInteger executions = new AtomicInteger();

        store.execute(null, "fingerprint", executions::incrementAndGet);
        store.execute("", "fingerprint", executions::incrementAndGet);

        assertEquals(2, executions.get());
        assertEquals(0, store.size(), "nothing is retained for an unkeyed call");
    }

    @Test
    void recordsExpireOnceTheRetryWindowHasPassed() {
        store.execute("key-1", "fingerprint", () -> "first");
        assertTrue(store.hasCompleted("key-1"));

        clock.advance(IdempotencyStore.RETENTION.plus(Duration.ofMinutes(1)));

        assertFalse(store.hasCompleted("key-1"));
        IdempotencyStore.Outcome<String> afterExpiry =
                store.execute("key-1", "fingerprint", () -> "second");
        assertEquals("second", afterExpiry.value());
        assertFalse(afterExpiry.replayed());
    }

    @Test
    void distinctKeysDoNotInterfere() {
        store.execute("key-1", "fingerprint-a", () -> "a");
        store.execute("key-2", "fingerprint-b", () -> "b");

        assertEquals("a", store.execute("key-1", "fingerprint-a", () -> "x").value());
        assertEquals("b", store.execute("key-2", "fingerprint-b", () -> "y").value());
        assertEquals(2, store.size());
    }
}
