package bankster.client.payments.cards;

/**
 * A primary account number — the long number on the front of a card.
 *
 * <p>The PAN is the most sensitive value in card payments and PCI DSS governs
 * everywhere it may appear. The rules this type exists to enforce are that it
 * never lands in a log, a template or an exception message: {@link #toString()}
 * returns the masked form, and only {@link #value()} exposes the digits, called
 * solely by the token vault and the processor client.
 *
 * <p>Validation is a Luhn check. That catches transposed and mistyped digits
 * before a request is spent on the network; it says nothing about whether the
 * card exists or has funds, which only the issuer can answer.
 */
public record Pan(String value) {

    public Pan {
        String digits = value == null ? "" : value.replaceAll("[\\s-]", "");
        if (!digits.matches("\\d{12,19}")) {
            throw new IllegalArgumentException("A card number must be 12 to 19 digits");
        }
        if (!passesLuhn(digits)) {
            throw new IllegalArgumentException("Card number fails the Luhn check");
        }
        value = digits;
    }

    /** First six digits: the issuer identification number, used for routing and risk. */
    public String bin() {
        return value.substring(0, 6);
    }

    public String last4() {
        return value.substring(value.length() - 4);
    }

    public CardScheme scheme() {
        return CardScheme.fromNumber(value);
    }

    /** The only form safe to display, store outside the vault, or log. */
    public String masked() {
        return bin() + "*".repeat(value.length() - 10) + last4();
    }

    @Override
    public String toString() {
        return masked();
    }

    /**
     * The Luhn checksum: doubling every second digit from the right and summing
     * the digits of the results must produce a multiple of ten.
     */
    public static boolean passesLuhn(String digits) {
        int sum = 0;
        boolean doubling = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubling) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubling = !doubling;
        }
        return sum % 10 == 0;
    }

    /** Computes the digit that would make {@code partial} Luhn-valid. */
    public static int luhnCheckDigit(String partial) {
        int sum = 0;
        boolean doubling = true;
        for (int i = partial.length() - 1; i >= 0; i--) {
            int digit = partial.charAt(i) - '0';
            if (doubling) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubling = !doubling;
        }
        return (10 - (sum % 10)) % 10;
    }
}
