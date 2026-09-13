package bankster.client.payments.rails;

import java.util.Optional;

/**
 * A Business Identifier Code — the SWIFT address of a financial institution.
 *
 * <p>Eight or eleven characters: four for the institution, two for the country,
 * two for the location, and optionally three identifying a branch. An 8-character
 * BIC addresses the institution's head office, which is the same as a branch code
 * of {@code XXX}.
 *
 * <p>Within SEPA the BIC is mostly redundant — IBAN-only addressing has been the
 * rule since 2016, and the IBAN identifies the institution. It remains essential
 * for cross-border SWIFT payments, where there is no equivalent, and for deciding
 * reachability: whether a bank can be reached on SEPA Instant is a property of its
 * BIC.
 */
public record Bic(String value) {

    public Bic {
        String normalised = value == null ? "" : value.replaceAll("\\s", "").toUpperCase();
        if (!isValidFormat(normalised)) {
            throw new IllegalArgumentException(
                    "Invalid BIC: must be 8 or 11 characters, as AAAACCLL[BBB], got " + value);
        }
        value = normalised;
    }

    public static boolean isValidFormat(String candidate) {
        return candidate != null && candidate.matches("[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?");
    }

    public static Optional<Bic> parse(String candidate) {
        String normalised = candidate == null ? "" : candidate.replaceAll("\\s", "").toUpperCase();
        return isValidFormat(normalised) ? Optional.of(new Bic(normalised)) : Optional.empty();
    }

    public String institutionCode() {
        return value.substring(0, 4);
    }

    public String countryCode() {
        return value.substring(4, 6);
    }

    public String locationCode() {
        return value.substring(6, 8);
    }

    /** {@code XXX} when the BIC addresses the head office. */
    public String branchCode() {
        return value.length() == 11 ? value.substring(8) : "XXX";
    }

    /** The 8-character form, which is how reachability directories are keyed. */
    public String institutionBic() {
        return value.substring(0, 8);
    }

    /**
     * A BIC whose location code ends in {@code 0} is a test-and-training code and
     * must never be used to address live money.
     */
    public boolean isTestBic() {
        return value.charAt(7) == '0';
    }

    @Override
    public String toString() {
        return value;
    }
}
