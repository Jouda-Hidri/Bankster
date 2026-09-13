package bankster.client.payments.core;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Transactional outbox with at-least-once delivery and consumer-side
 * deduplication.
 *
 * <p><b>Why an outbox.</b> A payment service must both change its own state and
 * tell the rest of the system about it. Doing the second with a network call
 * inside the first's transaction gives two failure modes that are each
 * unacceptable: publish-then-commit can announce a payment that never happened,
 * and commit-then-publish can silently lose a payment that did. The outbox
 * removes the distributed commit entirely — the event is written to the same
 * store as the state change, and a separate dispatcher moves it outward
 * afterwards. State and announcement can no longer disagree; they can only be
 * temporarily out of step, which is what eventual consistency means in
 * practice.
 *
 * <p><b>Why "exactly once" is a consumer property.</b> The dispatcher cannot
 * distinguish "delivery failed" from "delivery succeeded but the acknowledgement
 * was lost", so it must retry, and retries mean duplicates. Exactly-once
 * <em>processing</em> is therefore reconstructed at the consumer: each handler
 * records the event ids it has already applied and skips repeats. The wire is
 * at-least-once; the effect is once.
 *
 * <p>Delivery that keeps failing is not retried forever. After
 * {@link #MAX_ATTEMPTS} the event is parked as dead-lettered, where it is
 * visible for operator intervention instead of blocking the queue behind it.
 */
@Component
public class Outbox {

    private static final Logger log = LoggerFactory.getLogger(Outbox.class);

    /** Redelivery attempts before an event is parked for manual handling. */
    public static final int MAX_ATTEMPTS = 5;

    public enum Status {
        PENDING,
        DISPATCHED,
        DEAD_LETTERED
    }

    /** An event in the outbox together with its delivery bookkeeping. */
    public static final class Record {

        private final DomainEvent event;
        private Status status = Status.PENDING;
        private int attempts;
        private String lastError;

        Record(DomainEvent event) {
            this.event = event;
        }

        public DomainEvent event() {
            return event;
        }

        public Status status() {
            return status;
        }

        public int attempts() {
            return attempts;
        }

        public String lastError() {
            return lastError;
        }
    }

    private final Clock clock;
    private final List<Record> records = new CopyOnWriteArrayList<>();
    private final List<EventHandler> handlers = new CopyOnWriteArrayList<>();

    /** Event ids already applied, per handler — the consumer-side dedupe log. */
    private final Map<String, Set<String>> processed = new ConcurrentHashMap<>();

    public Outbox(Clock clock) {
        this.clock = clock;
    }

    public void subscribe(EventHandler handler) {
        handlers.add(handler);
        processed.computeIfAbsent(handler.name(), ignored -> ConcurrentHashMap.newKeySet());
    }

    /**
     * Stages an event. In a database-backed implementation this insert would
     * sit in the same transaction as the state change it describes; here the
     * in-memory journal and the outbox are updated under the same call, which
     * gives the same ordering guarantee.
     */
    public DomainEvent append(String type, String subject, Map<String, String> payload) {
        DomainEvent event = DomainEvent.of(type, subject, payload, clock.instant());
        records.add(new Record(event));
        return event;
    }

    /**
     * Attempts delivery of every pending event, in order.
     *
     * <p>Called explicitly rather than on a timer so that tests and the demo
     * console can observe the system in a settled state. A production
     * deployment would drive the same method from a poller.
     *
     * @return how many events reached at least one handler on this pass
     */
    public synchronized int drain() {
        int dispatched = 0;
        for (Record record : records) {
            if (record.status != Status.PENDING) {
                continue;
            }
            record.attempts++;
            List<String> failures = deliver(record.event);
            if (failures.isEmpty()) {
                record.status = Status.DISPATCHED;
                record.lastError = null;
                dispatched++;
            } else {
                record.lastError = String.join("; ", failures);
                if (record.attempts >= MAX_ATTEMPTS) {
                    record.status = Status.DEAD_LETTERED;
                    log.warn("Event {} dead-lettered after {} attempts: {}",
                            record.event.eventId(), record.attempts, record.lastError);
                }
            }
        }
        return dispatched;
    }

    private List<String> deliver(DomainEvent event) {
        List<String> failures = new ArrayList<>();
        for (EventHandler handler : handlers) {
            if (!handler.handles(event.type())) {
                continue;
            }
            Set<String> seen = processed.computeIfAbsent(handler.name(), ignored -> ConcurrentHashMap.newKeySet());
            if (seen.contains(event.eventId())) {
                // Redelivery of an event this handler already applied.
                continue;
            }
            try {
                handler.handle(event);
                seen.add(event.eventId());
            } catch (RuntimeException e) {
                failures.add(handler.name() + ": " + e.getMessage());
            }
        }
        return failures;
    }

    /**
     * Re-queues a dead-lettered event after the underlying fault has been
     * fixed. Handlers that already applied it stay deduplicated, so replay is
     * safe.
     */
    public synchronized boolean replayDeadLetter(String eventId) {
        for (Record record : records) {
            if (record.event.eventId().equals(eventId) && record.status == Status.DEAD_LETTERED) {
                record.status = Status.PENDING;
                record.attempts = 0;
                return true;
            }
        }
        return false;
    }

    public List<Record> records() {
        return List.copyOf(records);
    }

    public List<Record> deadLetters() {
        return records.stream().filter(record -> record.status == Status.DEAD_LETTERED).toList();
    }

    public List<Record> pending() {
        return records.stream().filter(record -> record.status == Status.PENDING).toList();
    }

    /** Events of one type, for the console and for assertions. */
    public List<DomainEvent> eventsOfType(String type) {
        return records.stream()
                .map(Record::event)
                .filter(event -> event.type().equals(type))
                .toList();
    }

    public Set<String> processedBy(String handlerName) {
        return new LinkedHashSet<>(processed.getOrDefault(handlerName, Set.of()));
    }

    public Instant now() {
        return clock.instant();
    }
}
