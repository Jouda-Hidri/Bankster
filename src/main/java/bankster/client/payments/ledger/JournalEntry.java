package bankster.client.payments.ledger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import bankster.client.payments.Money;

/**
 * An immutable, balanced set of postings — the only unit the ledger accepts.
 *
 * <p>Two timestamps are kept deliberately. {@code effectiveAt} is when the
 * economic event happened and drives which accounting period the entry lands
 * in; {@code recordedAt} is when the system learned about it. A chargeback
 * received today for a purchase three weeks ago has very different values for
 * the two, and reconciliation needs both to explain a break.
 *
 * <p>Each entry carries the hash of its predecessor. Rewriting history then
 * means recomputing every subsequent hash, which is detectable by
 * {@link Ledger#verifyIntegrity()} — the practical form of an immutable,
 * auditable transaction history.
 */
public record JournalEntry(
        String entryId,
        long sequence,
        Instant effectiveAt,
        Instant recordedAt,
        String description,
        String reference,
        List<Posting> postings,
        String previousHash,
        String hash) {

    public JournalEntry {
        postings = List.copyOf(postings);
    }

    /** Sum of all debits, per currency. */
    public Map<String, Money> totalDebits() {
        return totalsFor(Direction.DEBIT);
    }

    /** Sum of all credits, per currency. */
    public Map<String, Money> totalCredits() {
        return totalsFor(Direction.CREDIT);
    }

    private Map<String, Money> totalsFor(Direction direction) {
        Map<String, Money> totals = new TreeMap<>();
        for (Posting posting : postings) {
            if (posting.direction() != direction) {
                continue;
            }
            totals.merge(posting.amount().currency(), posting.amount(), Money::plus);
        }
        return totals;
    }

    /** All currencies this entry touches. */
    public List<String> currencies() {
        return postings.stream().map(p -> p.amount().currency()).distinct().sorted().toList();
    }

    /**
     * Recomputes the entry's hash from its content. Any field change, including
     * reordering postings, produces a different digest.
     */
    String computeHash() {
        StringBuilder payload = new StringBuilder()
                .append(entryId).append('|')
                .append(sequence).append('|')
                .append(effectiveAt).append('|')
                .append(recordedAt).append('|')
                .append(description).append('|')
                .append(reference).append('|')
                .append(previousHash).append('|');
        for (Posting posting : postings) {
            payload.append(posting.accountId()).append(':')
                    .append(posting.direction()).append(':')
                    .append(posting.amount().currency()).append(':')
                    .append(posting.amount().minorUnits()).append(';');
        }
        return sha256(payload.toString());
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM specification", e);
        }
    }
}
