package bankster.client.payments.cards;

/**
 * Dispute reason codes, in Visa's current numbering.
 *
 * <p>The code is not paperwork — it determines what evidence wins the case. A
 * fraud dispute is answered with proof that the cardholder was authenticated; a
 * "goods not received" dispute is answered with a delivery confirmation. Filing
 * the right evidence against the wrong code loses.
 *
 * <p>The fraud codes are also the ones 3-D Secure protects against. Where the
 * transaction carried a liability shift, the issuer is not entitled to charge a
 * fraud dispute back to the merchant at all, and the representment is close to
 * automatic. That protection does not extend to the service codes: no amount of
 * authentication proves a parcel arrived.
 */
public enum ChargebackReason {

    /** 10.4 — cardholder denies making a card-absent transaction. */
    FRAUD_CARD_ABSENT("10.4", "Other fraud — card-absent environment", true),

    /** 10.1 — EMV counterfeit at the point of sale. */
    FRAUD_COUNTERFEIT("10.1", "EMV liability shift counterfeit fraud", true),

    /** 11.3 — no valid authorization was obtained. Indefensible if true. */
    NO_AUTHORIZATION("11.3", "No authorization", false),

    /** 12.5 — the amount settled differs from the amount authorized. */
    INCORRECT_AMOUNT("12.5", "Incorrect amount", false),

    /** 12.6 — the same transaction was presented twice. */
    DUPLICATE_PROCESSING("12.6", "Duplicate processing", false),

    /** 13.1 — paid for, never arrived. */
    GOODS_NOT_RECEIVED("13.1", "Merchandise or services not received", false),

    /** 13.3 — arrived, but not what was sold. */
    NOT_AS_DESCRIBED("13.3", "Not as described or defective", false),

    /** 13.6 — a refund was promised and never made. Avoidable by refunding. */
    CREDIT_NOT_PROCESSED("13.6", "Credit not processed", false),

    /** 13.2 — a subscription continued after the cardholder cancelled it. */
    CANCELLED_RECURRING("13.2", "Cancelled recurring transaction", false);

    private final String code;
    private final String description;
    private final boolean fraud;

    ChargebackReason(String code, String description, boolean fraud) {
        this.code = code;
        this.description = description;
        this.fraud = fraud;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    /** Whether 3-D Secure liability shift is a defence against this code. */
    public boolean isFraud() {
        return fraud;
    }

    /** What the merchant has to produce to defend the case. */
    public String evidenceExpected() {
        return switch (this) {
            case FRAUD_CARD_ABSENT, FRAUD_COUNTERFEIT ->
                    "authentication data (ECI, CAVV, DS transaction id) or proof the cardholder benefited";
            case NO_AUTHORIZATION -> "the authorization code and approval response";
            case INCORRECT_AMOUNT -> "the authorized amount alongside the settled amount";
            case DUPLICATE_PROCESSING -> "evidence the two transactions were for separate purchases";
            case GOODS_NOT_RECEIVED -> "tracked delivery confirmation showing receipt";
            case NOT_AS_DESCRIBED -> "the product description as sold, and any returns correspondence";
            case CREDIT_NOT_PROCESSED -> "proof the refund was issued, with its date and reference";
            case CANCELLED_RECURRING -> "the mandate and the cancellation policy accepted at sign-up";
        };
    }
}
