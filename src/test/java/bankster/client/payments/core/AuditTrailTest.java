package bankster.client.payments.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import bankster.client.payments.TestClock;

/** Tamper-evidence on the record a dispute is argued from. */
class AuditTrailTest {

    private TestClock clock;
    private AuditTrail auditTrail;

    @BeforeEach
    void setUp() {
        clock = TestClock.businessDayMorning();
        auditTrail = new AuditTrail(clock);
    }

    @Test
    void eventsAreNumberedAndChainedFromGenesis() {
        AuditTrail.AuditEvent first = auditTrail.record("merchant-1", "payment.created", "pay-1");
        AuditTrail.AuditEvent second = auditTrail.record("3ds", "payment.authenticated", "pay-1");

        assertEquals(1, first.sequence());
        assertEquals(2, second.sequence());
        assertEquals(AuditTrail.GENESIS_HASH, first.previousHash());
        assertEquals(first.hash(), second.previousHash());
        assertTrue(auditTrail.verifyIntegrity());
    }

    @Test
    void theHistoryOfOneObjectCanBeReadBackInOrder() {
        auditTrail.record("merchant-1", "payment.created", "pay-1");
        auditTrail.record("merchant-1", "payment.created", "pay-2");
        auditTrail.record("acquirer", "payment.authorized", "pay-1");

        assertEquals(2, auditTrail.forSubject("pay-1").size());
        assertEquals("payment.created", auditTrail.forSubject("pay-1").get(0).action());
        assertEquals("payment.authorized", auditTrail.forSubject("pay-1").get(1).action());
        assertEquals(1, auditTrail.forSubject("pay-2").size());
    }

    @Test
    void metadataIsPartOfTheDigest() {
        AuditTrail.AuditEvent event = auditTrail.record("acquirer", "payment.authorized", "pay-1",
                Map.of("amount", "10.00 EUR", "code", "00"));

        assertEquals(event.hash(), event.computeHash());

        AuditTrail.AuditEvent altered = new AuditTrail.AuditEvent(
                event.sequence(), event.at(), event.actor(), event.action(), event.subject(),
                Map.of("amount", "1000.00 EUR", "code", "00"), event.previousHash(), event.hash());

        assertNotEquals(altered.hash(), altered.computeHash(),
                "changing the recorded amount must be detectable");
    }

    @Test
    void metadataOrderDoesNotAffectTheDigest() {
        Map<String, String> oneOrder = new LinkedHashMap<>();
        oneOrder.put("a", "1");
        oneOrder.put("b", "2");
        Map<String, String> otherOrder = new LinkedHashMap<>();
        otherOrder.put("b", "2");
        otherOrder.put("a", "1");

        AuditTrail.AuditEvent first = auditTrail.record("x", "y", "z", oneOrder);
        AuditTrail trail2 = new AuditTrail(clock);
        AuditTrail.AuditEvent second = trail2.record("x", "y", "z", otherOrder);

        assertEquals(first.hash(), second.hash(),
                "insertion order is not information; the digest must not depend on it");
    }

    @Test
    void timestampsComeFromTheInjectedClock() {
        AuditTrail.AuditEvent first = auditTrail.record("a", "b", "c");
        clock.advanceDays(1);
        AuditTrail.AuditEvent second = auditTrail.record("a", "b", "c");

        assertEquals(clock.instant().minusSeconds(86_400), first.at());
        assertEquals(clock.instant(), second.at());
    }

    @Test
    void anEmptyTrailIsTriviallyIntact() {
        assertTrue(auditTrail.verifyIntegrity());
        assertTrue(auditTrail.events().isEmpty());
    }
}
