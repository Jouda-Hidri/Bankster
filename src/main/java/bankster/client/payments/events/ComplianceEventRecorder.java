package bankster.client.payments.events;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

import bankster.client.payments.core.DomainEvent;
import bankster.client.payments.core.EventHandler;

/**
 * Keeps a compliance-facing record of events that may have to be explained to a
 * regulator.
 *
 * <p>A second, independent subscriber to the same events as the webhook
 * dispatcher, which is what makes the per-handler deduplication in the outbox
 * necessary rather than incidental: both handlers must receive every event exactly
 * once, and neither may be skipped because the other has already seen it.
 *
 * <p>What it keeps is narrower and longer-lived than a webhook log — blocked
 * payments, rejected transfers, disputes, rail downgrades. These are the events
 * where a customer was refused or did not get what they asked for, and those are
 * the ones somebody eventually asks about.
 */
@Component
public class ComplianceEventRecorder implements EventHandler {

    private static final List<String> RECORDED_TYPES = List.of(
            "transfer.rejected",
            "transfer.returned",
            "transfer.rail_downgraded",
            "payment.declined",
            "chargeback.received",
            "chargeback.resolved",
            "reconciliation.breaks_found");

    private final List<ComplianceRecord> records = new CopyOnWriteArrayList<>();

    public record ComplianceRecord(
            String eventId,
            String eventType,
            String subject,
            Map<String, String> detail,
            Instant at) {
    }

    @Override
    public String name() {
        return "compliance-log";
    }

    @Override
    public boolean handles(String eventType) {
        return RECORDED_TYPES.contains(eventType);
    }

    @Override
    public void handle(DomainEvent event) {
        records.add(new ComplianceRecord(
                event.eventId(), event.type(), event.subject(), event.payload(), event.occurredAt()));
    }

    public List<ComplianceRecord> records() {
        return List.copyOf(records);
    }

    public List<ComplianceRecord> ofType(String eventType) {
        return records.stream().filter(record -> record.eventType().equals(eventType)).toList();
    }

    public int size() {
        return records.size();
    }
}
