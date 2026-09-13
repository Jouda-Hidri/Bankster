package bankster.client.payments.risk;

/**
 * Where a customer stands in Know Your Customer onboarding.
 *
 * <p>Only {@link #VERIFIED} may transact. The states before it are not
 * administrative detail — under AMLD a regulated firm must complete customer
 * due diligence before establishing a business relationship, so letting a
 * PENDING customer move money is a compliance failure rather than a product
 * shortcut.
 */
public enum KycStatus {

    NOT_STARTED,

    /** Documents submitted, checks running. */
    PENDING,

    /** Identity established and screened; the customer may transact. */
    VERIFIED,

    /** Due diligence failed — typically an unresolved sanctions or identity hit. */
    REJECTED,

    /**
     * Previously verified but the evidence is stale. Periodic refresh is
     * required, more often for higher-risk customers.
     */
    EXPIRED;

    public boolean canTransact() {
        return this == VERIFIED;
    }
}
