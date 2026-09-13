package bankster.client.payments.core;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A saga: a business transaction spread across services that cannot share a
 * database transaction.
 *
 * <p>Taking a card payment touches the risk engine, an acquirer, the ledger and
 * a notification path. Those cannot be wrapped in one ACID transaction — the
 * acquirer is a third party on the far side of a network and has no notion of
 * rolling back because our ledger write failed afterwards. Two-phase commit is
 * not available and, with a third-party participant, would not be desirable:
 * it blocks every participant while the coordinator decides.
 *
 * <p>A saga replaces rollback with <em>compensation</em>. Each step names the
 * action that undoes it, and on failure the completed steps are compensated in
 * reverse order. The important consequence is that compensation is a new
 * forward action, not an erasure: an authorization is undone by sending a
 * reversal, and both the authorization and the reversal remain in the history.
 * This is why the ledger is append-only — the two models agree.
 *
 * <p>Failure is handled in two stages. A step may first be retried, because
 * most payment failures are transient (a timeout, a 503 from a processor).
 * Only once retries are exhausted does the saga give up and compensate.
 * Compensation itself can fail; that is recorded rather than swallowed, because
 * a failed compensation means money is stranded and a human has to look.
 */
public final class Saga {

    private static final Logger log = LoggerFactory.getLogger(Saga.class);

    /**
     * @param action       the forward work
     * @param compensation how to undo it, or {@code null} when the step has no
     *                     externally visible effect and needs no undo
     * @param maxAttempts  how many times to try the action before compensating
     */
    public record Step(String name, Runnable action, Runnable compensation, int maxAttempts) {
    }

    public enum Status {
        /** Every step succeeded. */
        COMPLETED,
        /** A step failed and all prior steps were successfully undone. */
        COMPENSATED,
        /** A step failed and at least one compensation also failed — funds may be stranded. */
        COMPENSATION_FAILED
    }

    public record Result(
            String sagaName,
            Status status,
            List<String> completedSteps,
            String failedStep,
            String failureReason,
            List<String> compensatedSteps,
            List<String> compensationFailures) {

        public boolean isSuccess() {
            return status == Status.COMPLETED;
        }

        /** True when the saga needs an operator: money moved and could not be moved back. */
        public boolean needsIntervention() {
            return status == Status.COMPENSATION_FAILED;
        }
    }

    private final String name;
    private final List<Step> steps = new ArrayList<>();

    private Saga(String name) {
        this.name = name;
    }

    public static Saga named(String name) {
        return new Saga(name);
    }

    public Saga step(String stepName, Runnable action, Runnable compensation) {
        steps.add(new Step(stepName, action, compensation, 1));
        return this;
    }

    /** A step whose action is safe to retry — the caller guarantees idempotency. */
    public Saga retryableStep(String stepName, int maxAttempts, Runnable action, Runnable compensation) {
        steps.add(new Step(stepName, action, compensation, Math.max(1, maxAttempts)));
        return this;
    }

    /** A step with nothing to undo, such as a pure read or a risk evaluation. */
    public Saga stepWithoutCompensation(String stepName, Runnable action) {
        steps.add(new Step(stepName, action, null, 1));
        return this;
    }

    public Result execute() {
        List<String> completed = new ArrayList<>();
        List<Step> toCompensate = new ArrayList<>();

        for (Step step : steps) {
            RuntimeException failure = runWithRetries(step);
            if (failure == null) {
                completed.add(step.name());
                if (step.compensation() != null) {
                    toCompensate.add(step);
                }
                continue;
            }
            log.warn("Saga {} failed at step '{}': {}", name, step.name(), failure.getMessage());
            return compensate(completed, toCompensate, step.name(), failure.getMessage());
        }

        return new Result(name, Status.COMPLETED, List.copyOf(completed), null, null, List.of(), List.of());
    }

    private RuntimeException runWithRetries(Step step) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= step.maxAttempts(); attempt++) {
            try {
                step.action().run();
                return null;
            } catch (RuntimeException e) {
                last = e;
                if (attempt < step.maxAttempts()) {
                    log.info("Saga {} step '{}' attempt {} failed ({}), retrying",
                            name, step.name(), attempt, e.getMessage());
                }
            }
        }
        return last;
    }

    private Result compensate(List<String> completed, List<Step> toCompensate,
                              String failedStep, String failureReason) {
        List<String> compensated = new ArrayList<>();
        List<String> compensationFailures = new ArrayList<>();

        // Reverse order: later steps may depend on earlier ones, so they have to
        // be unwound first.
        for (int i = toCompensate.size() - 1; i >= 0; i--) {
            Step step = toCompensate.get(i);
            try {
                step.compensation().run();
                compensated.add(step.name());
            } catch (RuntimeException e) {
                // Keep going — compensating the remaining steps recovers more
                // money than stopping at the first problem.
                compensationFailures.add(step.name() + ": " + e.getMessage());
                log.error("Saga {} could not compensate step '{}': {}", name, step.name(), e.getMessage());
            }
        }

        Status status = compensationFailures.isEmpty() ? Status.COMPENSATED : Status.COMPENSATION_FAILED;
        return new Result(name, status, List.copyOf(completed), failedStep, failureReason,
                List.copyOf(compensated), List.copyOf(compensationFailures));
    }
}
