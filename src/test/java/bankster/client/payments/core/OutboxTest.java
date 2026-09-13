package bankster.client.payments.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import bankster.client.payments.TestClock;

/**
 * At-least-once delivery with consumer-side deduplication — the mechanism by which
 * exactly-once <em>processing</em> is reconstructed on top of a transport that
 * cannot promise it.
 */
class OutboxTest {

    private TestClock clock;
    private Outbox outbox;

    @BeforeEach
    void setUp() {
        clock = TestClock.businessDayMorning();
        outbox = new Outbox(clock);
    }

    /** A handler that records what it was given and can be made to fail. */
    private static final class RecordingHandler implements EventHandler {

        private final String name;
        private final List<String> handled = new ArrayList<>();
        private boolean failing;
        private String typeFilter;

        RecordingHandler(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean handles(String eventType) {
            return typeFilter == null || typeFilter.equals(eventType);
        }

        @Override
        public void handle(DomainEvent event) {
            if (failing) {
                throw new IllegalStateException("endpoint down");
            }
            handled.add(event.eventId());
        }
    }

    @Test
    void appendedEventsAreDeliveredOnDrain() {
        RecordingHandler handler = new RecordingHandler("h1");
        outbox.subscribe(handler);

        outbox.append("payment.captured", "pay-1", Map.of("amount", "10.00 EUR"));
        outbox.append("payment.captured", "pay-2", Map.of("amount", "20.00 EUR"));

        assertEquals(2, outbox.drain());
        assertEquals(2, handler.handled.size());
        assertTrue(outbox.pending().isEmpty());
    }

    @Test
    void aSecondDrainDoesNotRedeliverWhatAlreadySucceeded() {
        RecordingHandler handler = new RecordingHandler("h1");
        outbox.subscribe(handler);
        outbox.append("payment.captured", "pay-1", Map.of());

        outbox.drain();
        assertEquals(0, outbox.drain(), "nothing left pending");
        assertEquals(1, handler.handled.size());
    }

    @Test
    void everyHandlerGetsItsOwnCopyAndNeitherSeesItTwice() {
        RecordingHandler webhooks = new RecordingHandler("webhooks");
        RecordingHandler compliance = new RecordingHandler("compliance");
        outbox.subscribe(webhooks);
        outbox.subscribe(compliance);

        DomainEvent event = outbox.append("chargeback.received", "cb-1", Map.of());
        outbox.drain();
        outbox.drain();

        assertEquals(List.of(event.eventId()), webhooks.handled);
        assertEquals(List.of(event.eventId()), compliance.handled);
    }

    @Test
    void handlersOnlyReceiveTypesTheySubscribedTo() {
        RecordingHandler selective = new RecordingHandler("selective");
        selective.typeFilter = "transfer.rejected";
        outbox.subscribe(selective);

        outbox.append("payment.captured", "pay-1", Map.of());
        outbox.append("transfer.rejected", "ct-1", Map.of());
        outbox.drain();

        assertEquals(1, selective.handled.size());
    }

    @Test
    void aFailingHandlerCausesRedeliveryAndThenDeadLettering() {
        RecordingHandler handler = new RecordingHandler("h1");
        handler.failing = true;
        outbox.subscribe(handler);
        outbox.append("payment.refunded", "pay-1", Map.of());

        for (int attempt = 0; attempt < Outbox.MAX_ATTEMPTS; attempt++) {
            assertEquals(0, outbox.drain(), "delivery keeps failing");
        }

        assertEquals(1, outbox.deadLetters().size());
        Outbox.Record parked = outbox.deadLetters().get(0);
        assertEquals(Outbox.Status.DEAD_LETTERED, parked.status());
        assertEquals(Outbox.MAX_ATTEMPTS, parked.attempts());
        assertTrue(parked.lastError().contains("endpoint down"));
        assertTrue(outbox.pending().isEmpty(), "a parked event must not block the queue");
    }

    @Test
    void aDeadLetterCanBeReplayedOnceTheFaultIsFixed() {
        RecordingHandler handler = new RecordingHandler("h1");
        handler.failing = true;
        outbox.subscribe(handler);
        DomainEvent event = outbox.append("payment.refunded", "pay-1", Map.of());

        for (int attempt = 0; attempt < Outbox.MAX_ATTEMPTS; attempt++) {
            outbox.drain();
        }
        handler.failing = false;

        assertTrue(outbox.replayDeadLetter(event.eventId()));
        assertEquals(1, outbox.drain());
        assertEquals(List.of(event.eventId()), handler.handled);
        assertTrue(outbox.deadLetters().isEmpty());
    }

    @Test
    void replayingAnEventAHandlerAlreadyAppliedIsSuppressedByTheDedupeLog() {
        RecordingHandler applied = new RecordingHandler("already-applied");
        RecordingHandler broken = new RecordingHandler("broken");
        broken.failing = true;
        outbox.subscribe(applied);
        outbox.subscribe(broken);

        DomainEvent event = outbox.append("payment.captured", "pay-1", Map.of());
        for (int attempt = 0; attempt < Outbox.MAX_ATTEMPTS; attempt++) {
            outbox.drain();
        }

        // The working handler applied it on the very first attempt...
        assertEquals(1, applied.handled.size());
        // ...and the four redeliveries caused by the broken one did not reach it again.
        broken.failing = false;
        outbox.replayDeadLetter(event.eventId());
        outbox.drain();

        assertEquals(1, applied.handled.size(), "exactly-once processing, despite five deliveries");
        assertEquals(1, broken.handled.size());
    }

    @Test
    void replayingAnEventThatIsNotDeadLetteredDoesNothing() {
        outbox.subscribe(new RecordingHandler("h1"));
        DomainEvent event = outbox.append("payment.captured", "pay-1", Map.of());
        outbox.drain();

        assertFalse(outbox.replayDeadLetter(event.eventId()));
        assertFalse(outbox.replayDeadLetter("no-such-event"));
    }

    @Test
    void eventIdentityIsAssignedOnceAndSurvivesRedelivery() {
        RecordingHandler handler = new RecordingHandler("h1");
        handler.failing = true;
        outbox.subscribe(handler);

        DomainEvent event = outbox.append("payment.captured", "pay-1", Map.of("k", "v"));
        outbox.drain();
        outbox.drain();

        assertEquals(event.eventId(), outbox.records().get(0).event().eventId(),
                "a redelivered event carries the id of the original, which is what consumers dedupe on");
        assertEquals("v", event.payloadValue("k"));
        assertEquals(clock.instant(), event.occurredAt());
    }

    @Test
    void eventsCanBeQueriedByType() {
        outbox.append("payment.captured", "pay-1", Map.of());
        outbox.append("payment.captured", "pay-2", Map.of());
        outbox.append("payment.refunded", "pay-1", Map.of());

        assertEquals(2, outbox.eventsOfType("payment.captured").size());
        assertEquals(1, outbox.eventsOfType("payment.refunded").size());
    }

    @Test
    void processedLogIsTrackedPerHandler() {
        RecordingHandler handler = new RecordingHandler("h1");
        outbox.subscribe(handler);
        DomainEvent event = outbox.append("payment.captured", "pay-1", Map.of());
        outbox.drain();

        assertTrue(outbox.processedBy("h1").contains(event.eventId()));
        assertTrue(outbox.processedBy("unknown-handler").isEmpty());
    }
}
