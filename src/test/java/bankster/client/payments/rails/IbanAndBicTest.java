package bankster.client.payments.rails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The self-checking properties of account and institution identifiers. Worth
 * testing hard: a credit transfer to a wrong but valid-looking account is executed,
 * and getting the money back depends on goodwill.
 */
class IbanAndBicTest {

    private static final String VALID_EE = "EE717700771001735865";
    private static final String VALID_DE = "DE04500700100532013000";
    private static final String VALID_NL = "NL86INGB0002445588";

    @Test
    void acceptsValidIbansAndNormalisesFormatting() {
        Iban iban = new Iban("ee71 7700-7710 0173 5865");

        assertEquals(VALID_EE, iban.value());
        assertEquals("EE", iban.countryCode());
        assertEquals("71", iban.checkDigits());
        assertEquals("7700771001735865", iban.bban());
    }

    @Test
    void rejectsAMistypedDigit() {
        Iban.ValidationResult result = Iban.validate("EE717700771001735866");

        assertFalse(result.valid());
        assertTrue(result.reason().contains("check digits"), result.reason());
    }

    @Test
    void rejectsTransposedCharacters() {
        // Swapping two digits is the error the check digits exist to catch.
        assertFalse(Iban.isValid("EE717700771001735856"));
    }

    @Test
    void rejectsTheWrongLengthForTheCountry() {
        Iban.ValidationResult result = Iban.validate("EE7177007710017358");

        assertFalse(result.valid());
        assertTrue(result.reason().contains("20 characters"), result.reason());
    }

    @Test
    void lengthIsCheckedAsWellAsTheChecksum() {
        // The mod-97 test alone accepts a wrong-length IBAN about one time in 97,
        // which is why both checks are needed.
        assertTrue(Iban.validate("EE7177007710017358").reason().contains("characters"));
    }

    @Test
    void rejectsAnUnknownCountry() {
        Iban.ValidationResult result = Iban.validate("ZZ1234567890123456");

        assertFalse(result.valid());
        assertTrue(result.reason().contains("not a country"), result.reason());
    }

    @Test
    void rejectsStructurallyWrongInput() {
        assertFalse(Iban.isValid(null));
        assertFalse(Iban.isValid(""));
        assertFalse(Iban.isValid("   "));
        assertFalse(Iban.isValid("1234567890123456"), "must start with a country code");
        assertFalse(Iban.isValid("EEXX7700771001735865"), "check digits must be digits");
    }

    @Test
    void theConstructorThrowsWhileValidateReportsTheReason() {
        assertThrows(IllegalArgumentException.class, () -> new Iban("EE000000000000000000"));
        assertTrue(Iban.parse("EE000000000000000000").isEmpty());
        assertTrue(Iban.parse(VALID_EE).isPresent());
    }

    @Test
    void validatesIbansAcrossSeveralCountries() {
        assertTrue(Iban.isValid(VALID_EE));
        assertTrue(Iban.isValid(VALID_DE));
        assertTrue(Iban.isValid(VALID_NL));
        assertTrue(Iban.isValid("SE2930000000000540398031"));
        assertTrue(Iban.isValid("GB33BUKB20201555555555"));
        assertTrue(Iban.isValid("FR1420041010050500013M02606"));
    }

    @Test
    void checkDigitsCanBeComputedFromDomesticDetails() {
        Iban built = Iban.build("EE", "7700771001735865");

        assertEquals(VALID_EE, built.value());
        assertTrue(Iban.isValid(built.value()));
    }

    @Test
    void buildingRoundTripsForEveryKnownCountryLength() {
        Iban germany = Iban.build("DE", "500700100532013000");
        Iban netherlands = Iban.build("NL", "INGB0002445588");

        assertEquals(VALID_DE, germany.value());
        assertEquals(VALID_NL, netherlands.value());
    }

    @Test
    void formattingAndMaskingAreBothAvailable() {
        Iban iban = new Iban(VALID_EE);

        assertEquals("EE71 7700 7710 0173 5865", iban.formatted());
        assertEquals("EE71 7700 7710 0173 5865", iban.toString());
        assertEquals("EE71" + "*".repeat(12) + "5865", iban.masked());
        assertFalse(iban.masked().contains("7700771001"));
    }

    @Test
    void sepaMembershipIsKnown() {
        assertTrue(new Iban(VALID_EE).isSepa());
        assertTrue(new Iban("GB33BUKB20201555555555").isSepa());
    }

    // --- BIC --------------------------------------------------------------

    @Test
    void acceptsEightAndElevenCharacterBics() {
        Bic head = new Bic("LHVBEE22");
        Bic branch = new Bic("lhvbee22xyz");

        assertEquals("LHVB", head.institutionCode());
        assertEquals("EE", head.countryCode());
        assertEquals("22", head.locationCode());
        assertEquals("XXX", head.branchCode(), "an 8-character BIC addresses the head office");
        assertEquals("XYZ", branch.branchCode());
        assertEquals("LHVBEE22", branch.institutionBic());
    }

    @Test
    void rejectsMalformedBics() {
        assertThrows(IllegalArgumentException.class, () -> new Bic("LHVB"));
        assertThrows(IllegalArgumentException.class, () -> new Bic("LHVBEE2"));
        assertThrows(IllegalArgumentException.class, () -> new Bic("LHVBEE22XY"));
        assertThrows(IllegalArgumentException.class, () -> new Bic("1HVBEE22"));
        assertTrue(Bic.parse("not a bic at all").isEmpty());
        // Eight letters is a structurally valid BIC, whatever it spells — format
        // validation is not a directory lookup.
        assertTrue(Bic.parse("NONSENSE").isPresent());
    }

    @Test
    void identifiesTestAndTrainingBics() {
        // A location code ending in 0 must never address live money.
        assertTrue(new Bic("LHVBEE20").isTestBic());
        assertFalse(new Bic("LHVBEE22").isTestBic());
    }

    // --- IBAN to BIC ------------------------------------------------------

    @Test
    void resolvesTheInstitutionFromTheNationalBankCode() {
        IbanBicDirectory directory = new IbanBicDirectory();

        assertEquals("LHVBEE22", directory.resolve(new Iban(VALID_EE)).orElseThrow().value());
        assertEquals("DEUTDEFF", directory.resolve(new Iban(VALID_DE)).orElseThrow().value());
        assertEquals("INGBNL2A", directory.resolve(new Iban(VALID_NL)).orElseThrow().value());
    }

    @Test
    void extractsTheBankCodeFromTheRightPositionPerCountry() {
        IbanBicDirectory directory = new IbanBicDirectory();

        assertEquals("77", directory.bankCode(new Iban(VALID_EE)).orElseThrow());
        assertEquals("50070010", directory.bankCode(new Iban(VALID_DE)).orElseThrow(),
                "the German Bankleitzahl is eight digits");
        assertEquals("INGB", directory.bankCode(new Iban(VALID_NL)).orElseThrow());
    }

    @Test
    void anUnknownBankCodeResolvesToNothingRatherThanGuessing() {
        IbanBicDirectory directory = new IbanBicDirectory();
        Iban unknownBank = Iban.build("EE", "9900771001735865");

        assertTrue(directory.resolve(unknownBank).isEmpty());
        assertEquals("99", directory.bankCode(unknownBank).orElseThrow());
    }

    @Test
    void newInstitutionsCanBeRegisteredAsADirectoryRefreshWould() {
        IbanBicDirectory directory = new IbanBicDirectory();
        int before = directory.size();
        directory.register("EE", "99", "NEWBEE22");

        assertEquals(before + 1, directory.size());
        assertEquals("NEWBEE22",
                directory.resolve(Iban.build("EE", "9900771001735865")).orElseThrow().value());
    }
}
