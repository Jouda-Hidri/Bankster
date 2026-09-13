package bankster.client.payments.rails;

/**
 * ISO 20022 external status reason codes.
 *
 * <p>Returned in a {@code pain.002} status report and in {@code pacs.002}
 * between institutions. The codes are standard so that the payer's software can
 * act on them without parsing free text — {@code AM04} means top up the account
 * and retry, while {@code AC04} means the account is gone and retrying is
 * pointless. A system that rejects with a prose message forces a human to make
 * that distinction.
 */
public enum RejectionReason {

    /** Account number invalid or does not exist. */
    AC01("AC01", "Incorrect account number", false),

    /** The account is closed. Retrying will never work. */
    AC04("AC04", "Closed account number", false),

    /** The account exists but is blocked. */
    AC06("AC06", "Blocked account", false),

    /** Not enough money. Worth retrying once funded. */
    AM04("AM04", "Insufficient funds", true),

    /** Amount above the limit for this rail or this customer. */
    AM02("AM02", "Amount exceeds the permitted limit", true),

    /** The beneficiary name does not match the account — Confirmation of Payee. */
    BE01("BE01", "Beneficiary name and account do not match", false),

    /** Beneficiary details are missing or inconsistent. */
    BE05("BE05", "Unrecognised initiating party", false),

    /** Blocked for sanctions, AML or another regulatory reason. */
    RR04("RR04", "Regulatory reason", false),

    /** The beneficiary's institution cannot be reached on the requested rail. */
    AGNT("AGNT", "Incorrect or unreachable agent", true),

    /** Requested execution date invalid or in the past. */
    DT01("DT01", "Invalid date", true),

    /** No reason given — used when a rail returns an opaque failure. */
    MS03("MS03", "Reason not specified", true);

    private final String code;
    private final String description;
    private final boolean retryable;

    RejectionReason(String code, String description, boolean retryable) {
        this.code = code;
        this.description = description;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    /** Whether re-sending the same instruction could ever succeed. */
    public boolean isRetryable() {
        return retryable;
    }
}
