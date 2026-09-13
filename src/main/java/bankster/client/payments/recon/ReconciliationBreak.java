package bankster.client.payments.recon;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import bankster.client.payments.Money;

/**
 * A discrepancy between two records of the same money.
 *
 * <p>Breaks are classified rather than merely counted because the type determines
 * who has to act. An item present internally and absent externally is usually a
 * timing difference that will clear itself; an item present externally and absent
 * internally means money moved that nobody recorded, which is an incident. Lumping
 * both into "unreconciled" guarantees that the second is missed inside the first.
 *
 * <p>{@code firstSeenAt} drives ageing, which is the other half of the discipline.
 * A break that appears today is normal; the same break still open after a month is
 * a control failure, and severity rises with age for that reason rather than with
 * amount alone.
 */
public record ReconciliationBreak(
        String breakKey,
        BreakType type,
        String reference,
        String paymentId,
        Money internalAmount,
        Money externalAmount,
        Money difference,
        String description,
        Instant firstSeenAt,
        Instant lastSeenAt,
        boolean writtenOff) {

    public enum BreakType {

        /** We recorded it; the external record has not. Often just timing. */
        MISSING_EXTERNALLY,

        /**
         * The external record has it; we do not. Money moved with nothing on our
         * books to explain it — the most serious ordinary break.
         */
        MISSING_INTERNALLY,

        /** Both have it, for different amounts. */
        AMOUNT_MISMATCH,

        /** The external file contains the same transaction twice. */
        DUPLICATE_EXTERNAL,

        /** The transaction matches but the fee charged does not. */
        FEE_MISMATCH,

        /** The batch total does not match the amount that reached the bank. */
        FUNDING_MISMATCH,

        /** The external file's own header and detail disagree. */
        SELF_INCONSISTENT_FILE,

        /** The ledger's debits and credits do not balance — an internal defect. */
        LEDGER_IMBALANCE,

        /** The journal's hash chain does not verify; an entry was altered. */
        LEDGER_TAMPERED
    }

    public enum Severity {
        /** Expected to clear by itself, typically a timing difference. */
        INFORMATIONAL,
        LOW,
        MEDIUM,
        HIGH,
        /** Money is unaccounted for, or the ledger itself is wrong. */
        CRITICAL
    }

    public long ageInDays(Instant now) {
        return ChronoUnit.DAYS.between(firstSeenAt, now);
    }

    /**
     * Severity from the type and how long it has been open.
     *
     * <p>The type sets the floor — an unexplained external item or a broken ledger
     * is serious on day one — and age raises everything else, because a break that
     * has survived a week is no longer a timing difference.
     */
    public Severity severity(Instant now) {
        if (type == BreakType.LEDGER_IMBALANCE || type == BreakType.LEDGER_TAMPERED
                || type == BreakType.MISSING_INTERNALLY) {
            return Severity.CRITICAL;
        }
        long age = ageInDays(now);
        if (type == BreakType.FUNDING_MISMATCH || type == BreakType.SELF_INCONSISTENT_FILE) {
            return age >= 2 ? Severity.CRITICAL : Severity.HIGH;
        }
        if (type == BreakType.DUPLICATE_EXTERNAL) {
            return Severity.HIGH;
        }
        // An amount or fee that does not agree is money, on day one. Starting these
        // low and letting them age up would mean a short-paid batch is paid out
        // before anyone looks at it, which is the one outcome reconciliation exists
        // to prevent. They still escalate with age on top of that floor.
        if (type == BreakType.AMOUNT_MISMATCH || type == BreakType.FEE_MISMATCH) {
            if (age >= 30) {
                return Severity.CRITICAL;
            }
            return age >= 7 ? Severity.HIGH : Severity.MEDIUM;
        }
        if (age >= 30) {
            return Severity.CRITICAL;
        }
        if (age >= 7) {
            return Severity.HIGH;
        }
        if (age >= 2) {
            return Severity.MEDIUM;
        }
        return type == BreakType.MISSING_EXTERNALLY ? Severity.INFORMATIONAL : Severity.LOW;
    }

    /** Ageing bucket, the form a reconciliation report is normally read in. */
    public String ageBucket(Instant now) {
        long age = ageInDays(now);
        if (age < 1) {
            return "same day";
        }
        if (age <= 7) {
            return "2–7 days";
        }
        if (age <= 30) {
            return "8–30 days";
        }
        return "over 30 days";
    }

    ReconciliationBreak seenAgain(Instant at) {
        return new ReconciliationBreak(breakKey, type, reference, paymentId, internalAmount,
                externalAmount, difference, description, firstSeenAt, at, writtenOff);
    }

    ReconciliationBreak markWrittenOff() {
        return new ReconciliationBreak(breakKey, type, reference, paymentId, internalAmount,
                externalAmount, difference, description, firstSeenAt, lastSeenAt, true);
    }
}
