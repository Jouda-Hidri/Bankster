package bankster.client.payments.cards;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Issuer identification lookup: what a card's leading digits say about it.
 *
 * <p>The BIN is consulted on nearly every decision in the flow, because it is
 * the only thing known about the card before the issuer is asked anything:
 *
 * <ul>
 *   <li><b>Pricing.</b> EEA interchange caps apply to consumer cards only —
 *       0.2% debit, 0.3% credit. Commercial cards and cards issued outside the
 *       EEA are uncapped and cost several times as much, so a merchant quoted a
 *       blended rate on traffic that turns out to be corporate loses money on
 *       every transaction.</li>
 *   <li><b>Risk.</b> The issuing country is half of the geography check, and a
 *       prepaid card behaves differently from a credit line.</li>
 *   <li><b>Routing.</b> Some processors have better issuer relationships in some
 *       countries than others.</li>
 * </ul>
 *
 * <p>Real BIN tables are licensed datasets of hundreds of thousands of ranges,
 * refreshed monthly. This one holds enough entries to make the decisions above
 * demonstrable, and falls back to a conservative assumption — consumer credit,
 * unknown country — for anything unrecognised, because guessing a cheaper
 * category than reality means under-charging.
 */
@Component
public class BinTable {

    public enum Funding {
        DEBIT,
        CREDIT,
        PREPAID
    }

    /**
     * @param commercial corporate, business or purchasing card — outside the
     *                   interchange caps
     */
    public record BinInfo(
            String binRange,
            String issuerName,
            String issuerCountry,
            CardScheme scheme,
            Funding funding,
            boolean commercial,
            boolean eea) {
    }

    /** EEA membership, which is what decides whether the interchange caps bite. */
    private static final Set<String> EEA_COUNTRIES = Set.of(
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE",
            "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE",
            "IS", "LI", "NO");

    private final Map<String, BinInfo> ranges = new LinkedHashMap<>();

    public BinTable() {
        // Reserved ranges used by the demo and the tests.
        add("400000", "Bankster Test Issuer", "EE", CardScheme.VISA, Funding.DEBIT, false);
        add("400002", "Bankster Test Issuer", "EE", CardScheme.VISA, Funding.DEBIT, false);
        add("400003", "Bankster Test Issuer", "EE", CardScheme.VISA, Funding.CREDIT, false);
        add("400004", "Bankster Test Issuer", "EE", CardScheme.VISA, Funding.CREDIT, false);
        add("400005", "Bankster Test Issuer", "EE", CardScheme.VISA, Funding.DEBIT, false);
        add("400006", "Bankster Test Issuer", "EE", CardScheme.VISA, Funding.DEBIT, false);

        add("411111", "Nordic Retail Bank", "SE", CardScheme.VISA, Funding.DEBIT, false);
        add("453201", "Banque de Paris", "FR", CardScheme.VISA, Funding.CREDIT, false);
        add("492910", "Corporate Card Services", "DE", CardScheme.VISA, Funding.CREDIT, true);
        add("510510", "Deutsche Kartenbank", "DE", CardScheme.MASTERCARD, Funding.DEBIT, false);
        add("555555", "Mastercard Test Issuer", "IE", CardScheme.MASTERCARD, Funding.CREDIT, false);
        add("222100", "Baltic Credit Union", "LT", CardScheme.MASTERCARD, Funding.PREPAID, false);
        add("340000", "American Express EU", "GB", CardScheme.AMEX, Funding.CREDIT, false);
        add("374245", "American Express Business", "US", CardScheme.AMEX, Funding.CREDIT, true);
        add("601100", "Discover Bank", "US", CardScheme.DISCOVER, Funding.CREDIT, false);
        add("356600", "JCB Issuer", "JP", CardScheme.JCB, Funding.CREDIT, false);
    }

    private void add(String bin, String issuerName, String country, CardScheme scheme,
                     Funding funding, boolean commercial) {
        ranges.put(bin, new BinInfo(bin, issuerName, country, scheme, funding, commercial,
                EEA_COUNTRIES.contains(country)));
    }

    public BinInfo lookup(String bin) {
        BinInfo exact = ranges.get(bin);
        if (exact != null) {
            return exact;
        }
        // Unknown card: assume the most expensive plausible category rather than
        // the cheapest, so an unpriced BIN cannot silently erode margin.
        return new BinInfo(bin, "Unknown issuer", "??",
                CardScheme.fromNumber(bin), Funding.CREDIT, false, false);
    }

    public BinInfo lookup(CardToken card) {
        return lookup(card.bin());
    }

    /**
     * The pricing that applies to this card. Commercial and non-EEA cards fall
     * outside the caps; consumer debit is the cheapest category.
     */
    public FeeSchedule feeScheduleFor(BinInfo info, String currency) {
        if (info.commercial() || !info.eea()) {
            return FeeSchedule.uncappedCommercial(currency);
        }
        return info.funding() == Funding.DEBIT
                ? FeeSchedule.standardEeaDebit(currency)
                : FeeSchedule.standardEeaCredit(currency);
    }

    public int size() {
        return ranges.size();
    }
}
