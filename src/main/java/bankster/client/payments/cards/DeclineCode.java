package bankster.client.payments.cards;

import java.util.Arrays;

/**
 * Issuer response codes, in the ISO 8583 numbering the schemes still use.
 *
 * <p>The distinction that actually drives behaviour is soft versus hard.
 *
 * <ul>
 *   <li>A <b>soft</b> decline is a "not like this, not right now": the issuer
 *       wants authentication, the acquirer timed out, a limit was hit for the
 *       day. Retrying — often with 3-D Secure attached, or simply later — has a
 *       real chance of succeeding, and for subscription billing this recovery
 *       path is worth a noticeable share of revenue.</li>
 *   <li>A <b>hard</b> decline is a "no": the card is closed, stolen, or does not
 *       exist. Retrying will not help. Doing it anyway attracts scheme fines
 *       for excessive re-attempts and, on a stolen card, looks exactly like
 *       card testing.</li>
 * </ul>
 */
public enum DeclineCode {

    APPROVED("00", "Approved", false),
    REFER_TO_ISSUER("01", "Refer to card issuer", false),
    DO_NOT_HONOUR("05", "Do not honour", false),
    INVALID_TRANSACTION("12", "Invalid transaction", false),
    INVALID_CARD_NUMBER("14", "Invalid card number", false),
    LOST_CARD("41", "Lost card — pick up", false),
    STOLEN_CARD("43", "Stolen card — pick up", false),
    INSUFFICIENT_FUNDS("51", "Insufficient funds", true),
    EXPIRED_CARD("54", "Expired card", false),
    TRANSACTION_NOT_PERMITTED("57", "Transaction not permitted to cardholder", false),
    EXCEEDS_LIMIT("61", "Exceeds withdrawal amount limit", true),
    RESTRICTED_CARD("62", "Restricted card", false),
    EXCEEDS_FREQUENCY("65", "Exceeds withdrawal frequency limit", true),
    STRONG_AUTHENTICATION_REQUIRED("1A", "Strong customer authentication required", true),
    ISSUER_UNAVAILABLE("91", "Issuer or switch inoperative", true),
    SYSTEM_MALFUNCTION("96", "System malfunction", true);

    private final String code;
    private final String description;
    private final boolean soft;

    DeclineCode(String code, String description, boolean soft) {
        this.code = code;
        this.description = description;
        this.soft = soft;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public boolean isApproval() {
        return this == APPROVED;
    }

    /** Whether a re-attempt is legitimate. */
    public boolean isSoftDecline() {
        return soft;
    }

    /**
     * Whether the failure was ours or the network's rather than the issuer's —
     * these are the only ones safe to retry immediately, and only against a
     * different processor.
     */
    public boolean isTechnicalFailure() {
        return this == ISSUER_UNAVAILABLE || this == SYSTEM_MALFUNCTION;
    }

    /** A soft decline that goes away if the transaction is authenticated. */
    public boolean isRetryableWithAuthentication() {
        return this == STRONG_AUTHENTICATION_REQUIRED;
    }

    public static DeclineCode fromCode(String code) {
        return Arrays.stream(values())
                .filter(value -> value.code.equals(code))
                .findFirst()
                .orElse(DO_NOT_HONOUR);
    }
}
