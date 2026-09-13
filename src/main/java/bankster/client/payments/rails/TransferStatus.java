package bankster.client.payments.rails;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The lifecycle of a credit transfer, in ISO 20022 status codes.
 *
 * <p>The codes are worth using rather than inventing local names for, because
 * they are what arrives in a {@code pain.002} status report from the bank and
 * what an operations team reads in any other system. They also draw a
 * distinction that local vocabularies tend to lose: {@code ACSP} means the
 * payment has been accepted and settlement is under way, while {@code ACSC}
 * means settlement has completed. Only the second means the beneficiary has the
 * money, and treating the first as success is how a customer gets told their
 * payment arrived before it did.
 */
public enum TransferStatus {

    /** RCVD — the instruction has been received but not yet checked. */
    RECEIVED("RCVD", "Received"),

    /** ACTC — structurally valid: IBAN check digits, mandatory fields, formats. */
    TECHNICALLY_VALIDATED("ACTC", "Accepted technical validation"),

    /** ACCP — customer profile checks passed: limits, account status, screening. */
    ACCEPTED("ACCP", "Accepted customer profile"),

    /** PDNG — held, typically awaiting a compliance review or the next cycle. */
    PENDING("PDNG", "Pending"),

    /** ACSP — on the rail; settlement has begun but is not finished. */
    SETTLEMENT_IN_PROGRESS("ACSP", "Accepted settlement in process"),

    /** ACSC — settled. The beneficiary has the funds. */
    SETTLED("ACSC", "Accepted settlement completed"),

    /** RJCT — refused. The reason code says by whom and why. */
    REJECTED("RJCT", "Rejected"),

    /**
     * The beneficiary's bank sent the money back after settlement — a closed
     * account, a name mismatch, or a recall the beneficiary agreed to. A return
     * is a separate transaction, not an undo of the original.
     */
    RETURNED("RTRN", "Returned");

    private final String isoCode;
    private final String description;

    TransferStatus(String isoCode, String description) {
        this.isoCode = isoCode;
        this.description = description;
    }

    private static final Map<TransferStatus, Set<TransferStatus>> TRANSITIONS = Map.of(
            RECEIVED, EnumSet.of(TECHNICALLY_VALIDATED, REJECTED),
            TECHNICALLY_VALIDATED, EnumSet.of(ACCEPTED, PENDING, REJECTED),
            ACCEPTED, EnumSet.of(SETTLEMENT_IN_PROGRESS, PENDING, REJECTED),
            PENDING, EnumSet.of(ACCEPTED, SETTLEMENT_IN_PROGRESS, REJECTED),
            SETTLEMENT_IN_PROGRESS, EnumSet.of(SETTLED, REJECTED),
            SETTLED, EnumSet.of(RETURNED),
            REJECTED, EnumSet.noneOf(TransferStatus.class),
            RETURNED, EnumSet.noneOf(TransferStatus.class));

    public String isoCode() {
        return isoCode;
    }

    public String description() {
        return description;
    }

    public boolean canTransitionTo(TransferStatus next) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(next);
    }

    public boolean isFinal() {
        return this == SETTLED || this == REJECTED || this == RETURNED;
    }

    /** True only when the money has actually reached the beneficiary. */
    public boolean isSettled() {
        return this == SETTLED;
    }
}
