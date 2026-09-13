package bankster.client.payments.ledger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import bankster.client.payments.Money;

/**
 * A proposed journal entry, before the ledger has accepted it.
 *
 * <p>Drafts are mutable and cheap; {@link JournalEntry} instances are neither,
 * which keeps the "nothing in the journal ever changes" rule easy to hold.
 */
public final class JournalEntryDraft {

    private final List<Posting> postings = new ArrayList<>();
    private String description = "";
    private String reference = "";
    private Instant effectiveAt;

    public static JournalEntryDraft of(String description) {
        JournalEntryDraft draft = new JournalEntryDraft();
        draft.description = description;
        return draft;
    }

    /**
     * The business identifier this entry belongs to — a payment id, settlement
     * batch id or chargeback case id. Reconciliation and the audit trail both
     * navigate by it.
     */
    public JournalEntryDraft reference(String reference) {
        this.reference = reference == null ? "" : reference;
        return this;
    }

    /** When the economic event happened, if that differs from now. */
    public JournalEntryDraft effectiveAt(Instant effectiveAt) {
        this.effectiveAt = effectiveAt;
        return this;
    }

    public JournalEntryDraft debit(String accountId, Money amount, String narrative) {
        postings.add(Posting.debit(accountId, amount, narrative));
        return this;
    }

    public JournalEntryDraft credit(String accountId, Money amount, String narrative) {
        postings.add(Posting.credit(accountId, amount, narrative));
        return this;
    }

    /** Shorthand for the common two-legged entry. */
    public JournalEntryDraft transfer(String fromAccountId, String toAccountId, Money amount, String narrative) {
        return debit(toAccountId, amount, narrative).credit(fromAccountId, amount, narrative);
    }

    public List<Posting> postings() {
        return List.copyOf(postings);
    }

    public String description() {
        return description;
    }

    public String reference() {
        return reference;
    }

    public Instant effectiveAt() {
        return effectiveAt;
    }
}
