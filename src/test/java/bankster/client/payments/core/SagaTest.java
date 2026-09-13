package bankster.client.payments.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Compensation in place of rollback, which is the only option once a third party
 * is a participant.
 */
class SagaTest {

    @Test
    void allStepsSucceedingNeedsNoCompensation() {
        List<String> log = new ArrayList<>();

        Saga.Result result = Saga.named("happy path")
                .step("one", () -> log.add("one"), () -> log.add("undo one"))
                .step("two", () -> log.add("two"), () -> log.add("undo two"))
                .execute();

        assertTrue(result.isSuccess());
        assertEquals(Saga.Status.COMPLETED, result.status());
        assertEquals(List.of("one", "two"), log);
        assertEquals(List.of("one", "two"), result.completedSteps());
    }

    @Test
    void aFailureCompensatesCompletedStepsInReverseOrder() {
        List<String> log = new ArrayList<>();

        Saga.Result result = Saga.named("capture")
                .step("claim funds", () -> log.add("claimed"), () -> log.add("refunded"))
                .step("book to ledger", () -> log.add("booked"), () -> log.add("reversed"))
                .step("notify", () -> {
                    throw new IllegalStateException("webhook endpoint unreachable");
                }, () -> log.add("unnotified"))
                .execute();

        assertFalse(result.isSuccess());
        assertEquals(Saga.Status.COMPENSATED, result.status());
        assertEquals("notify", result.failedStep());
        assertEquals("webhook endpoint unreachable", result.failureReason());
        // Later steps are unwound first, because they may depend on earlier ones.
        assertEquals(List.of("claimed", "booked", "reversed", "refunded"), log);
        assertEquals(List.of("book to ledger", "claim funds"), result.compensatedSteps());
    }

    @Test
    void theFailingStepIsNotItselfCompensated() {
        List<String> log = new ArrayList<>();

        Saga.Result result = Saga.named("partial")
                .step("first", () -> log.add("first"), () -> log.add("undo first"))
                .step("second", () -> {
                    throw new IllegalStateException("boom");
                }, () -> log.add("undo second"))
                .execute();

        assertFalse(log.contains("undo second"), "a step that never completed has nothing to undo");
        assertEquals(List.of("first"), result.completedSteps());
    }

    @Test
    void stepsWithoutCompensationAreSkippedOnUnwind() {
        List<String> log = new ArrayList<>();

        Saga.named("with a read")
                .stepWithoutCompensation("evaluate risk", () -> log.add("scored"))
                .step("fail", () -> {
                    throw new IllegalStateException("no");
                }, () -> log.add("undo fail"))
                .execute();

        assertEquals(List.of("scored"), log);
    }

    @Test
    void retryableStepsAreReattemptedBeforeTheSagaGivesUp() {
        AtomicInteger attempts = new AtomicInteger();

        Saga.Result result = Saga.named("transient fault")
                .retryableStep("flaky call", 3, () -> {
                    if (attempts.incrementAndGet() < 3) {
                        throw new IllegalStateException("504 from the acquirer");
                    }
                }, () -> {
                })
                .execute();

        assertTrue(result.isSuccess());
        assertEquals(3, attempts.get(), "two failures then a success");
    }

    @Test
    void retriesAreBoundedAndThenTheSagaCompensates() {
        AtomicInteger attempts = new AtomicInteger();
        List<String> log = new ArrayList<>();

        Saga.Result result = Saga.named("permanently broken")
                .step("first", () -> log.add("first"), () -> log.add("undo first"))
                .retryableStep("never works", 3, () -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("still broken");
                }, () -> {
                })
                .execute();

        assertEquals(3, attempts.get());
        assertEquals(Saga.Status.COMPENSATED, result.status());
        assertEquals(List.of("first", "undo first"), log);
    }

    @Test
    void aFailedCompensationIsReportedRatherThanSwallowed() {
        List<String> log = new ArrayList<>();

        Saga.Result result = Saga.named("stranded funds")
                .step("move money", () -> log.add("moved"), () -> {
                    throw new IllegalStateException("refund endpoint down");
                })
                .step("fail", () -> {
                    throw new IllegalStateException("ledger write failed");
                }, () -> log.add("undo"))
                .execute();

        assertEquals(Saga.Status.COMPENSATION_FAILED, result.status());
        assertTrue(result.needsIntervention(), "money moved and could not be moved back");
        assertEquals(1, result.compensationFailures().size());
        assertTrue(result.compensationFailures().get(0).contains("refund endpoint down"));
    }

    @Test
    void compensationContinuesPastAFailureToRecoverWhatItCan() {
        List<String> log = new ArrayList<>();

        Saga.Result result = Saga.named("partial recovery")
                .step("recoverable", () -> log.add("a"), () -> log.add("undo a"))
                .step("unrecoverable", () -> log.add("b"), () -> {
                    throw new IllegalStateException("cannot undo b");
                })
                .step("fail", () -> {
                    throw new IllegalStateException("boom");
                }, () -> log.add("undo c"))
                .execute();

        // Stopping at the first compensation failure would have left 'a' unwound too.
        assertTrue(log.contains("undo a"));
        assertEquals(List.of("recoverable"), result.compensatedSteps());
        assertEquals(1, result.compensationFailures().size());
    }
}
