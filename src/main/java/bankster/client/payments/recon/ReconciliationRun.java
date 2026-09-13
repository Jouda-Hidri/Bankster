package bankster.client.payments.recon;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import bankster.client.payments.Money;
import bankster.client.payments.recon.ReconciliationBreak.BreakType;
import bankster.client.payments.recon.ReconciliationBreak.Severity;

/**
 * The result of one reconciliation pass.
 *
 * <p>Both the matched count and the totals are reported, because either alone can
 * be misleading. Matching every line proves nothing if the totals differ — a
 * missing line reconciles perfectly against nothing. And matching totals proves
 * nothing if the lines differ, since two offsetting errors net to zero.
 */
public record ReconciliationRun(
        String runId,
        Instant runAt,
        String batchId,
        int matchedCount,
        int withinToleranceCount,
        List<ReconciliationBreak> breaks,
        Money internalTotal,
        Money externalTotal,
        Money bankTotal) {

    public ReconciliationRun {
        breaks = List.copyOf(breaks);
    }

    /** Nothing to investigate. */
    public boolean isClean() {
        return breaks.isEmpty();
    }

    /**
     * Whether anything found would stop a payout.
     *
     * <p>The bar is MEDIUM rather than HIGH on purpose. A payout is irreversible, so
     * the asymmetry favours holding: delaying a merchant a day costs goodwill, and
     * paying out money that turns out not to have arrived costs the money.
     * {@code INFORMATIONAL} and {@code LOW} items — overwhelmingly timing
     * differences that clear themselves — do not hold anything.
     */
    public boolean hasBlockingBreaks() {
        return breaks.stream()
                .map(breakItem -> breakItem.severity(runAt))
                .anyMatch(severity -> severity == Severity.CRITICAL || severity == Severity.HIGH
                        || severity == Severity.MEDIUM);
    }

    public List<ReconciliationBreak> breaksOfType(BreakType type) {
        return breaks.stream().filter(breakItem -> breakItem.type() == type).toList();
    }

    public Map<Severity, Long> breaksBySeverity() {
        Map<Severity, Long> counts = new LinkedHashMap<>();
        for (Severity severity : Severity.values()) {
            long count = breaks.stream()
                    .filter(breakItem -> breakItem.severity(runAt) == severity)
                    .count();
            if (count > 0) {
                counts.put(severity, count);
            }
        }
        return counts;
    }

    /**
     * The unexplained difference between what we think we are owed and what the
     * external record says. Zero when the totals agree, whatever the line-level
     * breaks.
     */
    public Money unexplainedDifference() {
        return internalTotal.minus(externalTotal);
    }

    /** A one-line summary for the console and the audit trail. */
    public String summary() {
        if (isClean()) {
            return matchedCount + " items matched; nothing outstanding";
        }
        return matchedCount + " matched, " + withinToleranceCount + " within tolerance, "
                + breaks.size() + " break(s); unexplained difference "
                + unexplainedDifference();
    }
}
