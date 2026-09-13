package bankster.client.payments.rails;

import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;

/**
 * An International Bank Account Number.
 *
 * <p>The IBAN's useful property is that it is self-checking. Two check digits in
 * positions 3 and 4 are computed over the rest of the account number, so a
 * mistyped or transposed character is caught locally, before a payment is
 * instructed. That matters more here than in card payments: a credit transfer to
 * a wrong but valid-looking account is executed, and recovering the money
 * depends on the goodwill of whoever received it.
 *
 * <p>The check is a mod-97 calculation defined by ISO 13616 — move the first four
 * characters to the end, replace letters with numbers (A=10 … Z=35), and the
 * remainder on division by 97 must be 1. Length is validated per country as
 * well, because the mod-97 test alone accepts an IBAN that is the wrong length
 * for its country roughly once in every 97 attempts.
 */
public record Iban(String value) {

    /** IBAN lengths for SEPA and a few adjacent countries. */
    private static final Map<String, Integer> LENGTHS = Map.ofEntries(
            Map.entry("AD", 24), Map.entry("AT", 20), Map.entry("BE", 16), Map.entry("BG", 22),
            Map.entry("CH", 21), Map.entry("CY", 28), Map.entry("CZ", 24), Map.entry("DE", 22),
            Map.entry("DK", 18), Map.entry("EE", 20), Map.entry("ES", 24), Map.entry("FI", 18),
            Map.entry("FR", 27), Map.entry("GB", 22), Map.entry("GI", 23), Map.entry("GR", 27),
            Map.entry("HR", 21), Map.entry("HU", 28), Map.entry("IE", 22), Map.entry("IS", 26),
            Map.entry("IT", 27), Map.entry("LI", 21), Map.entry("LT", 20), Map.entry("LU", 20),
            Map.entry("LV", 21), Map.entry("MC", 27), Map.entry("MT", 31), Map.entry("NL", 18),
            Map.entry("NO", 15), Map.entry("PL", 28), Map.entry("PT", 25), Map.entry("RO", 24),
            Map.entry("SE", 24), Map.entry("SI", 19), Map.entry("SK", 24), Map.entry("SM", 27),
            Map.entry("VA", 22));

    public Iban {
        String normalised = normalise(value);
        ValidationResult result = validate(normalised);
        if (!result.valid()) {
            throw new IllegalArgumentException("Invalid IBAN: " + result.reason());
        }
        value = normalised;
    }

    public record ValidationResult(boolean valid, String reason) {

        static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        static ValidationResult invalid(String reason) {
            return new ValidationResult(false, reason);
        }
    }

    /**
     * Checks an IBAN without throwing, for validating user input where the
     * reason has to be shown back to the user.
     */
    public static ValidationResult validate(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return ValidationResult.invalid("no IBAN supplied");
        }
        String normalised = normalise(candidate);
        if (!normalised.matches("[A-Z]{2}\\d{2}[A-Z0-9]+")) {
            return ValidationResult.invalid(
                    "must be two letters, two check digits, then alphanumerics");
        }
        String country = normalised.substring(0, 2);
        Integer expectedLength = LENGTHS.get(country);
        if (expectedLength == null) {
            return ValidationResult.invalid(country + " is not a country this application knows");
        }
        if (normalised.length() != expectedLength) {
            return ValidationResult.invalid(
                    country + " IBANs are " + expectedLength + " characters, this one is " + normalised.length());
        }
        if (mod97(normalised) != 1) {
            return ValidationResult.invalid("check digits do not match — likely a typo");
        }
        return ValidationResult.ok();
    }

    public static boolean isValid(String candidate) {
        return validate(candidate).valid();
    }

    public static Optional<Iban> parse(String candidate) {
        return isValid(candidate) ? Optional.of(new Iban(candidate)) : Optional.empty();
    }

    public String countryCode() {
        return value.substring(0, 2);
    }

    public String checkDigits() {
        return value.substring(2, 4);
    }

    /** The national part — the domestic account identifier. */
    public String bban() {
        return value.substring(4);
    }

    /** Grouped in fours, the form used on paper and in interfaces. */
    public String formatted() {
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < value.length(); i += 4) {
            if (i > 0) {
                formatted.append(' ');
            }
            formatted.append(value, i, Math.min(i + 4, value.length()));
        }
        return formatted.toString();
    }

    /**
     * Obscures the middle. An IBAN is not as sensitive as a PAN, but it is
     * personal data and identifies an account that can be debited by direct
     * debit, so full display is avoided where the last digits are enough.
     */
    public String masked() {
        return value.substring(0, 4) + "*".repeat(value.length() - 8) + value.substring(value.length() - 4);
    }

    /** Whether this IBAN is in the SEPA area, and so reachable by credit transfer. */
    public boolean isSepa() {
        return LENGTHS.containsKey(countryCode());
    }

    @Override
    public String toString() {
        return formatted();
    }

    private static String normalise(String candidate) {
        return candidate == null ? "" : candidate.replaceAll("[\\s-]", "").toUpperCase();
    }

    /**
     * The mod-97 check. BigInteger rather than a rolling remainder because a
     * 34-character IBAN converts to a 40-digit number, well past a long.
     */
    private static int mod97(String iban) {
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        StringBuilder numeric = new StringBuilder();
        for (char character : rearranged.toCharArray()) {
            if (Character.isDigit(character)) {
                numeric.append(character);
            } else {
                numeric.append(character - 'A' + 10);
            }
        }
        return new BigInteger(numeric.toString()).mod(BigInteger.valueOf(97)).intValue();
    }

    /**
     * Computes the check digits for a country and national account number, which
     * is how a valid IBAN is constructed from domestic details.
     */
    public static Iban build(String countryCode, String bban) {
        String withZeroCheck = countryCode.toUpperCase() + "00" + bban.toUpperCase();
        int remainder = mod97(withZeroCheck);
        int checkDigits = 98 - remainder;
        return new Iban(String.format(java.util.Locale.ROOT, "%s%02d%s", countryCode.toUpperCase(), checkDigits, bban.toUpperCase()));
    }
}
