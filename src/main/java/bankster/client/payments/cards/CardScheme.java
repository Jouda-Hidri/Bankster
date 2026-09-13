package bankster.client.payments.cards;

/**
 * The card networks Bankster can route to.
 *
 * <p>The scheme is derived from the leading digits of the card number rather
 * than asked for, because the issuer identification number is authoritative and
 * a shopper-selected scheme is not. It matters operationally: interchange,
 * chargeback rules and which processors can even accept the transaction all
 * depend on it.
 */
public enum CardScheme {

    VISA("Visa"),
    MASTERCARD("Mastercard"),
    AMEX("American Express"),
    DISCOVER("Discover"),
    JCB("JCB"),
    DINERS("Diners Club"),
    UNKNOWN("Unknown");

    private final String displayName;

    CardScheme(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * Resolves the scheme from the issuer identification number.
     *
     * <p>The ranges are the published ones; Mastercard's 2221–2720 block was
     * added in 2017 and is still missed by naive "starts with 5" checks, which
     * is exactly the sort of bug that routes a live card to the wrong acquirer.
     */
    public static CardScheme fromNumber(String digits) {
        if (digits == null || digits.length() < 4) {
            return UNKNOWN;
        }
        int twoDigit = Integer.parseInt(digits.substring(0, 2));
        int fourDigit = Integer.parseInt(digits.substring(0, 4));
        int threeDigit = Integer.parseInt(digits.substring(0, 3));

        if (digits.charAt(0) == '4') {
            return VISA;
        }
        if ((twoDigit >= 51 && twoDigit <= 55) || (fourDigit >= 2221 && fourDigit <= 2720)) {
            return MASTERCARD;
        }
        if (twoDigit == 34 || twoDigit == 37) {
            return AMEX;
        }
        if (fourDigit == 6011 || twoDigit == 65 || (threeDigit >= 644 && threeDigit <= 649)) {
            return DISCOVER;
        }
        if (fourDigit >= 3528 && fourDigit <= 3589) {
            return JCB;
        }
        if ((threeDigit >= 300 && threeDigit <= 305) || twoDigit == 36 || twoDigit == 38) {
            return DINERS;
        }
        return UNKNOWN;
    }
}
