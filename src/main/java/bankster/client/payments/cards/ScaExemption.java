package bankster.client.payments.cards;

/**
 * Exemptions from strong customer authentication under the PSD2 regulatory
 * technical standards.
 *
 * <p>SCA is the default for electronic payments in the EEA; these are the
 * carve-outs. They exist because authenticating every transaction destroys
 * conversion on low-value and repeat purchases, and the regulation accepts that
 * trade-off where the risk is demonstrably small.
 *
 * <p>The cost of claiming one is liability. An authenticated transaction shifts
 * chargeback liability to the issuer; an exempted transaction does not, so the
 * acquirer or merchant keeps the fraud. Claiming an exemption is therefore a
 * commercial decision — accept the fraud risk in exchange for the conversion —
 * and not simply a way to skip a step.
 */
public enum ScaExemption {

    /** No exemption claimed; the transaction was authenticated. */
    NONE,

    /**
     * Under €30, subject to a cumulative counter: the exemption lapses after
     * five consecutive uses or €100 since the last authentication, whichever
     * comes first. The counter is what stops the exemption being used to split
     * a large fraud into small pieces.
     */
    LOW_VALUE,

    /**
     * Transaction risk analysis. Permitted up to a ceiling that depends on the
     * provider's own measured fraud rate — the lower the fraud, the higher the
     * ceiling. It is the only exemption a provider earns rather than simply
     * claims.
     */
    TRANSACTION_RISK_ANALYSIS,

    /** A series of same-amount, same-payee payments. Only the first needs SCA. */
    RECURRING,

    /**
     * Merchant-initiated, with no cardholder present to authenticate. Requires a
     * mandate agreed under SCA at setup.
     */
    MERCHANT_INITIATED,

    /** The cardholder has added this merchant to their issuer's trusted list. */
    TRUSTED_BENEFICIARY,

    /** Lodged corporate cards and secure corporate payment processes. */
    CORPORATE;

    /**
     * Whether claiming this exemption keeps chargeback liability with the
     * acquirer rather than moving it to the issuer.
     */
    public boolean retainsLiability() {
        return this != NONE;
    }
}
