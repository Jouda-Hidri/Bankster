package bankster.client.payments;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock the tests move by hand.
 *
 * <p>Nearly everything in payments is time-dependent — authorization expiry,
 * settlement cut-offs, representment deadlines, velocity windows, break ageing —
 * and none of it is testable against a real clock. With this, "an authorization
 * lapses after seven days" is a test that runs in a millisecond.
 */
public final class TestClock extends Clock {

    private final ZoneId zone;
    private Instant instant;

    private TestClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    /** @param isoInstant e.g. {@code 2026-09-15T09:00:00Z} */
    public static TestClock at(String isoInstant) {
        return new TestClock(Instant.parse(isoInstant), ZoneOffset.UTC);
    }

    /** A Tuesday morning, well inside the SEPA cut-off and a TARGET settlement day. */
    public static TestClock businessDayMorning() {
        return at("2026-09-15T09:00:00Z");
    }

    public TestClock advance(Duration amount) {
        instant = instant.plus(amount);
        return this;
    }

    public TestClock advanceDays(long days) {
        return advance(Duration.ofDays(days));
    }

    public TestClock set(String isoInstant) {
        instant = Instant.parse(isoInstant);
        return this;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new TestClock(instant, newZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
