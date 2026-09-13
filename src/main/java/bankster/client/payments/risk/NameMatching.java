package bankster.client.payments.risk;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;

/**
 * Fuzzy comparison of personal and company names.
 *
 * <p>Used in two places that look unrelated and need the same thing. Sanctions
 * screening has to catch a listed party who spells their name slightly
 * differently; Confirmation of Payee has to accept a payer who typed "J Smith"
 * for an account held by "John Smith". Both fail if the comparison is exact, and
 * both fail differently if it is too loose — a screening threshold set too low
 * buries analysts in false positives, and a payee threshold set too low lets
 * through the near-miss names that push-payment fraud relies on. So the matching
 * is shared and the thresholds are chosen per use.
 *
 * <p>Normalisation strips accents, case and punctuation and sorts the words, so
 * that word order and honorifics-free variants collapse together before any
 * distance is computed.
 */
public final class NameMatching {

    private NameMatching() {
    }

    /**
     * Reduces a name to a comparable form: accents removed, lowercased,
     * punctuation dropped, words sorted.
     *
     * <p>Sorting the words is what makes "Al-Rashid, Nadia" and "nadia al rashid"
     * identical — name order varies by culture and by data source, and it is not
     * a meaningful difference.
     */
    public static String normalise(String name) {
        if (name == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        String[] words = decomposed.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", " ")
                .trim()
                .split("\\s+");
        Arrays.sort(words);
        return String.join(" ", words);
    }

    /** Similarity of two already-normalised names, from 0 to 1. */
    public static double similarity(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        int longest = Math.max(a.length(), b.length());
        if (longest == 0) {
            return 1.0;
        }
        return 1.0 - ((double) levenshtein(a, b) / longest);
    }

    /** Normalises both names and compares them. */
    public static double similarityOf(String a, String b) {
        return similarity(normalise(a), normalise(b));
    }

    /**
     * Levenshtein edit distance, computed with two rows rather than a full
     * matrix — the distance is all that is wanted, not the alignment.
     */
    public static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
