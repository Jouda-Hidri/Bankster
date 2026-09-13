package bankster.client.payments.risk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Sanctions and watchlist screening.
 *
 * <p>Screening cannot be an equality check. Sanctioned parties do not spell
 * their names helpfully: transliteration from Arabic or Cyrillic is not
 * standardised, name order varies by culture, and a single inserted letter
 * would defeat an exact match. Regulators expect fuzzy matching with a tunable
 * threshold, and they expect it to be tuned towards false positives — missing a
 * true hit is an enforcement matter, while a false positive costs an analyst a
 * few minutes.
 *
 * <p>Matching here normalises accents, case, punctuation and word order, then
 * scores with Levenshtein similarity over the whole name and over individual
 * tokens. The entries are illustrative placeholders; a production system
 * consumes the OFAC SDN, EU consolidated and UN lists on a schedule and rescreens
 * the whole customer base whenever they change, because a customer who was clean
 * yesterday may be listed today.
 */
@Component
public class SanctionsList {

    /**
     * Similarity at or above which a name is treated as a possible hit.
     *
     * <p>0.80 rather than something tighter, because the threshold has to be loose
     * enough to survive transliteration. "Petrov" and "Petroff" are two edits apart
     * in a twelve-character name, which scores 0.83 — a tighter threshold would let
     * the commonest real-world spelling variant through, which is an enforcement
     * matter rather than an inconvenience. The cost of the looser setting is analyst
     * time on false positives, which is the trade regulators expect to be made in
     * this direction.
     */
    public static final double MATCH_THRESHOLD = 0.80;

    /**
     * Jurisdictions under comprehensive sanctions or on the FATF call-for-action
     * list. Payments touching these are stopped rather than scored.
     */
    private static final List<String> PROHIBITED_COUNTRIES = List.of("IR", "KP", "SY", "CU");

    /** FATF "increased monitoring" — permitted, but requiring enhanced due diligence. */
    private static final List<String> HIGH_RISK_COUNTRIES = List.of("MM", "YE", "SS", "HT", "CD");

    private record Entry(String name, String listName, String programme) {
    }

    private final List<Entry> entries = new ArrayList<>(List.of(
            new Entry("Ivan Petrov", "EU Consolidated", "Asset freeze"),
            new Entry("Global Trade Holdings LLC", "OFAC SDN", "Trade sanctions"),
            new Entry("Nadia Al-Rashid", "UN Consolidated", "Terrorism financing"),
            new Entry("Northstar Shipping Company", "OFAC SDN", "Shipping — sectoral")));

    /** A possible match, for an analyst to clear or escalate. */
    public record SanctionsMatch(String screenedName, String listedName, String listName,
                                 String programme, double similarity) {
    }

    public Optional<SanctionsMatch> screenName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String normalised = NameMatching.normalise(name);
        SanctionsMatch best = null;
        for (Entry entry : entries) {
            double similarity = NameMatching.similarity(normalised, NameMatching.normalise(entry.name()));
            if (similarity >= MATCH_THRESHOLD && (best == null || similarity > best.similarity())) {
                best = new SanctionsMatch(name, entry.name(), entry.listName(), entry.programme(), similarity);
            }
        }
        return Optional.ofNullable(best);
    }

    public boolean isProhibitedCountry(String countryCode) {
        return countryCode != null && PROHIBITED_COUNTRIES.contains(countryCode.toUpperCase(Locale.ROOT));
    }

    public boolean isHighRiskCountry(String countryCode) {
        return countryCode != null && HIGH_RISK_COUNTRIES.contains(countryCode.toUpperCase(Locale.ROOT));
    }

    /** Adds an entry, as a list refresh would. */
    public void addEntry(String name, String listName, String programme) {
        entries.add(new Entry(name, listName, programme));
    }

    public int size() {
        return entries.size();
    }

}
