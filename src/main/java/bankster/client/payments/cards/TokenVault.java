package bankster.client.payments.cards;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * Tokenization: replaces card numbers with surrogates so that the rest of the
 * system never touches a PAN.
 *
 * <p>The value of tokenization is that it shrinks the blast radius of a breach.
 * A stolen PAN works anywhere the card works; a stolen token works nowhere,
 * because it is meaningless without the vault. Concretely this vault implements
 * two properties that matter:
 *
 * <ul>
 *   <li><b>Merchant scoping.</b> The token is derived from the PAN <em>and</em>
 *       the merchant id, so the same card presented at two merchants yields two
 *       different tokens. A token leaked from one merchant cannot be replayed
 *       at another — the defence that a shared token would not give.</li>
 *   <li><b>Stable linkage without the PAN.</b> A payment account reference is
 *       derived from the PAN alone. It contains no card digits, so it is safe
 *       to store and to index, yet it lets risk rules recognise "the same card"
 *       across merchants. Without it, cross-merchant velocity checks would
 *       require keeping PANs, which is precisely what tokenization is meant to
 *       avoid.</li>
 * </ul>
 *
 * <p>Both derivations are keyed HMACs, so they are deterministic — the same
 * card tokenizes to the same value on every visit, which is what makes
 * card-on-file and repeat-customer analytics work — while being impossible to
 * invert without the key. The token is itself Luhn-valid and keeps the original
 * BIN, mirroring how network tokens are issued so that scheme detection and
 * BIN routing continue to work downstream.
 *
 * <p>The key is generated per process here. A real deployment holds it in an
 * HSM or KMS and never lets it reach application memory in the clear.
 */
@Component
public class TokenVault {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] tokenKey = randomKey();
    private final byte[] parKey = randomKey();

    /** token -> PAN. The only place card numbers live. */
    private final Map<String, Pan> vault = new ConcurrentHashMap<>();

    /** token -> the metadata safe to expose. */
    private final Map<String, CardToken> issued = new ConcurrentHashMap<>();

    /**
     * Stores the card and returns its surrogate. Called once, at the edge,
     * before the card details are discarded.
     */
    public CardToken tokenize(CardDetails details, String merchantId) {
        Pan pan = details.pan();
        String token = deriveToken(pan, merchantId);
        String par = derivePar(pan);

        CardToken cardToken = new CardToken(
                token, par, pan.scheme(), pan.bin(), pan.last4(), merchantId, details.expiry());

        vault.put(token, pan);
        issued.put(token, cardToken);
        return cardToken;
    }

    /**
     * Recovers the PAN. Reserved for the component that actually talks to the
     * acquirer; nothing else in the application calls this.
     */
    public Optional<Pan> detokenize(String token) {
        return Optional.ofNullable(vault.get(token));
    }

    public Optional<CardToken> lookup(String token) {
        return Optional.ofNullable(issued.get(token));
    }

    /** Card-on-file deletion: the surrogate is revoked and the PAN forgotten. */
    public boolean revoke(String token) {
        issued.remove(token);
        return vault.remove(token) != null;
    }

    public int size() {
        return vault.size();
    }

    /**
     * Builds a Luhn-valid 16-digit token that keeps the PAN's BIN, so that
     * scheme resolution and BIN-based routing behave the same on the token as
     * on the card.
     */
    private String deriveToken(Pan pan, String merchantId) {
        String digits = digitsFromHmac(tokenKey, pan.value() + "|" + merchantId, 9);
        String withoutCheckDigit = pan.bin() + digits;
        return withoutCheckDigit + Pan.luhnCheckDigit(withoutCheckDigit);
    }

    /** 29 characters, the scheme-defined PAR length, derived from the PAN only. */
    private String derivePar(Pan pan) {
        StringBuilder par = new StringBuilder("BNK");
        par.append(digitsFromHmac(parKey, pan.value(), 26));
        return par.toString();
    }

    private String digitsFromHmac(byte[] key, String message, int length) {
        byte[] mac = hmac(key, message);
        StringBuilder digits = new StringBuilder(length);
        for (int i = 0; digits.length() < length; i++) {
            int value = mac[i % mac.length] & 0xFF;
            digits.append(value % 10);
            if (digits.length() < length) {
                digits.append((value / 10) % 10);
            }
        }
        return digits.substring(0, length);
    }

    private byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Could not derive token", e);
        }
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
