package bankster.client.payments.cards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.YearMonth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Card number handling and tokenization: the two places where a mistake becomes a
 * PCI finding rather than a bug.
 */
class PanAndTokenVaultTest {

    private TokenVault vault;

    @BeforeEach
    void setUp() {
        vault = new TokenVault();
    }

    /** Builds a Luhn-valid card number from a 15-digit prefix. */
    private static Pan pan(String fifteenDigits) {
        return new Pan(fifteenDigits + Pan.luhnCheckDigit(fifteenDigits));
    }

    private static CardDetails card(String fifteenDigits) {
        return new CardDetails(pan(fifteenDigits), YearMonth.of(2030, 6), "A HOLDER", "123");
    }

    // --- Luhn and formatting ---------------------------------------------

    @Test
    void acceptsALuhnValidNumberAndNormalisesSeparators() {
        Pan withSpaces = new Pan("4111 1111-1111 1111");

        assertEquals("4111111111111111", withSpaces.value());
        assertEquals("411111", withSpaces.bin());
        assertEquals("1111", withSpaces.last4());
    }

    @Test
    void rejectsANumberThatFailsTheLuhnCheck() {
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> new Pan("4111111111111112"));
        assertTrue(failure.getMessage().contains("Luhn"), failure.getMessage());
    }

    @Test
    void rejectsNumbersOfImplausibleLength() {
        assertThrows(IllegalArgumentException.class, () -> new Pan("41111"));
        assertThrows(IllegalArgumentException.class, () -> new Pan("41111111111111111111"));
        assertThrows(IllegalArgumentException.class, () -> new Pan("4111-not-digits"));
        assertThrows(IllegalArgumentException.class, () -> new Pan(null));
    }

    @Test
    void luhnCatchesTheErrorsItIsDesignedFor() {
        String valid = "4111111111111111";
        assertTrue(Pan.passesLuhn(valid));

        // A single mistyped digit.
        assertFalse(Pan.passesLuhn("4111111111111121"));
        // Two adjacent digits transposed.
        assertFalse(Pan.passesLuhn("4111111111111211".substring(0, 14) + "11"));
    }

    @Test
    void theMaskedFormIsWhatToStringExposes() {
        Pan card = new Pan("4111111111111111");

        assertEquals("411111******1111", card.masked());
        assertEquals(card.masked(), card.toString(),
                "the PAN must not leak through a log statement or an exception message");
        assertFalse(card.toString().contains("1111111111"));
    }

    @Test
    void cardDetailsDoNotPrintTheNumberEither() {
        assertFalse(card("411111111111111").toString().contains("4111111111111111"));
    }

    @Test
    void schemeIsDerivedFromTheIssuerIdentificationNumber() {
        assertEquals(CardScheme.VISA, CardScheme.fromNumber("4111111111111111"));
        assertEquals(CardScheme.MASTERCARD, CardScheme.fromNumber("5105105105105100"));
        assertEquals(CardScheme.AMEX, CardScheme.fromNumber("378282246310005"));
        assertEquals(CardScheme.DISCOVER, CardScheme.fromNumber("6011111111111117"));
        assertEquals(CardScheme.JCB, CardScheme.fromNumber("3530111333300000"));
        assertEquals(CardScheme.DINERS, CardScheme.fromNumber("30569309025904"));
        assertEquals(CardScheme.UNKNOWN, CardScheme.fromNumber("9999999999999999"));
    }

    @Test
    void recognisesMastercardsSecondBinRange() {
        // The 2221-2720 block was added in 2017 and is missed by a "starts with 5" check.
        assertEquals(CardScheme.MASTERCARD, CardScheme.fromNumber("2221000000000009"));
        assertEquals(CardScheme.MASTERCARD, CardScheme.fromNumber("2720000000000005"));
        assertEquals(CardScheme.UNKNOWN, CardScheme.fromNumber("2220000000000000"));
        assertEquals(CardScheme.UNKNOWN, CardScheme.fromNumber("2721000000000000"));
    }

    @Test
    void expiryIsInclusiveOfTheWholeMonth() {
        CardDetails details = new CardDetails(pan("411111111111111"),
                YearMonth.of(2026, 9), "A HOLDER", "123");

        assertFalse(details.isExpiredAt(YearMonth.of(2026, 9)), "usable through the last day");
        assertTrue(details.isExpiredAt(YearMonth.of(2026, 10)));
    }

    // --- Tokenization -----------------------------------------------------

    @Test
    void theSameCardAtTheSameMerchantAlwaysGetsTheSameToken() {
        CardToken first = vault.tokenize(card("411111111111111"), "merchant-a");
        CardToken second = vault.tokenize(card("411111111111111"), "merchant-a");

        assertEquals(first.token(), second.token(),
                "card-on-file and repeat-customer analytics depend on this being stable");
    }

    @Test
    void theSameCardAtDifferentMerchantsGetsDifferentTokens() {
        CardToken atA = vault.tokenize(card("411111111111111"), "merchant-a");
        CardToken atB = vault.tokenize(card("411111111111111"), "merchant-b");

        assertNotEquals(atA.token(), atB.token(),
                "a token leaked from one merchant must be useless at another");
    }

    @Test
    void thePaymentAccountReferenceIsStableAcrossMerchants() {
        CardToken atA = vault.tokenize(card("411111111111111"), "merchant-a");
        CardToken atB = vault.tokenize(card("411111111111111"), "merchant-b");

        assertEquals(atA.par(), atB.par(),
                "cross-merchant velocity checks need this, and it contains no card digits");
        assertFalse(atA.par().contains("4111"));
        assertEquals(29, atA.par().length());
    }

    @Test
    void differentCardsGetDifferentReferences() {
        CardToken one = vault.tokenize(card("411111111111111"), "merchant-a");
        CardToken two = vault.tokenize(card("453201111111111"), "merchant-a");

        assertNotEquals(one.par(), two.par());
        assertNotEquals(one.token(), two.token());
    }

    @Test
    void theTokenKeepsTheBinAndIsItselfLuhnValid() {
        CardToken token = vault.tokenize(card("411111111111111"), "merchant-a");

        assertEquals("411111", token.bin());
        assertEquals(16, token.token().length());
        assertTrue(Pan.passesLuhn(token.token()),
                "network tokens are format-preserving so downstream scheme detection still works");
        assertEquals(CardScheme.VISA, CardScheme.fromNumber(token.token()));
    }

    @Test
    void tokenMetadataCarriesOnlyWhatIsSafeToStore() {
        CardToken token = vault.tokenize(card("411111111111111"), "merchant-a");

        assertEquals("1111", token.last4());
        assertEquals("411111******1111", token.masked());
        assertEquals(CardScheme.VISA, token.scheme());
        assertEquals("merchant-a", token.merchantId());
        assertEquals(YearMonth.of(2030, 6), token.expiry());
    }

    @Test
    void detokenizationRecoversThePanForTheProcessorCallOnly() {
        CardToken token = vault.tokenize(card("411111111111111"), "merchant-a");

        assertEquals("4111111111111111", vault.detokenize(token.token()).orElseThrow().value());
        assertTrue(vault.detokenize("not-a-token").isEmpty());
    }

    @Test
    void revokingATokenForgetsThePan() {
        CardToken token = vault.tokenize(card("411111111111111"), "merchant-a");
        assertEquals(1, vault.size());

        assertTrue(vault.revoke(token.token()));

        assertTrue(vault.detokenize(token.token()).isEmpty());
        assertTrue(vault.lookup(token.token()).isEmpty());
        assertEquals(0, vault.size());
        assertFalse(vault.revoke(token.token()), "already gone");
    }

    @Test
    void tokenExpiryTracksTheUnderlyingCard() {
        CardDetails expiring = new CardDetails(pan("411111111111111"),
                YearMonth.of(2026, 9), "A HOLDER", "123");
        CardToken token = vault.tokenize(expiring, "merchant-a");

        assertFalse(token.isExpiredAt(YearMonth.of(2026, 9)));
        assertTrue(token.isExpiredAt(YearMonth.of(2026, 10)));
    }
}
