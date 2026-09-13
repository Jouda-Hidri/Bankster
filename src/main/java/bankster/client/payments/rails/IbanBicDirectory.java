package bankster.client.payments.rails;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Resolves an IBAN to the BIC of the institution holding it.
 *
 * <p>SEPA has been IBAN-only since 2016: a payer supplies an IBAN and nothing
 * else, and the sending institution is expected to work out where it goes. The
 * national bank identifier embedded in the IBAN is what makes that possible —
 * the German Bankleitzahl, the French code banque, the two-digit Estonian bank
 * code — though its position and length differ by country, which is why this is a
 * lookup rather than a formula.
 *
 * <p>Resolution is needed even though the payment itself does not carry a BIC,
 * because reachability is a property of the institution. Deciding whether a
 * transfer can go instant means identifying the beneficiary's bank first.
 */
@Component
public class IbanBicDirectory {

    /** Where the national bank identifier sits inside the BBAN, per country. */
    private static final Map<String, int[]> BANK_CODE_POSITIONS = Map.ofEntries(
            Map.entry("EE", new int[]{4, 6}),
            Map.entry("DE", new int[]{4, 12}),
            Map.entry("FR", new int[]{4, 9}),
            Map.entry("NL", new int[]{4, 8}),
            Map.entry("SE", new int[]{4, 7}),
            Map.entry("MT", new int[]{4, 8}),
            Map.entry("CH", new int[]{4, 9}),
            Map.entry("LT", new int[]{4, 9}),
            Map.entry("ES", new int[]{4, 8}),
            Map.entry("IT", new int[]{5, 10}),
            Map.entry("BE", new int[]{4, 7}),
            Map.entry("AT", new int[]{4, 9}),
            Map.entry("IE", new int[]{4, 8}),
            Map.entry("FI", new int[]{4, 10}),
            Map.entry("PT", new int[]{4, 8}));

    private final Map<String, String> bankCodeToBic = new ConcurrentHashMap<>();

    public IbanBicDirectory() {
        register("EE", "77", "LHVBEE22");
        register("DE", "50070010", "DEUTDEFF");
        register("FR", "30004", "BNPAFRPP");
        register("NL", "INGB", "INGBNL2A");
        register("SE", "300", "NDEASESS");
        register("MT", "MTLC", "MTLCMT21");
        register("CH", "00700", "CRESCHZZ");
        register("LT", "32000", "REVOLT21");
    }

    public void register(String country, String bankCode, String bic) {
        bankCodeToBic.put(key(country, bankCode), bic);
    }

    /** The institution's BIC, if the national bank code is recognised. */
    public Optional<Bic> resolve(Iban iban) {
        return bankCode(iban)
                .map(code -> bankCodeToBic.get(key(iban.countryCode(), code)))
                .flatMap(Bic::parse);
    }

    /** The national bank identifier embedded in the IBAN. */
    public Optional<String> bankCode(Iban iban) {
        int[] positions = BANK_CODE_POSITIONS.get(iban.countryCode());
        if (positions == null || iban.value().length() < positions[1]) {
            return Optional.empty();
        }
        return Optional.of(iban.value().substring(positions[0], positions[1]));
    }

    private String key(String country, String bankCode) {
        return country.toUpperCase() + ":" + bankCode.toUpperCase();
    }

    public int size() {
        return bankCodeToBic.size();
    }
}
