package bankster.client.payments.ledger;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import bankster.client.payments.Money;

/**
 * An append-only, double-entry journal.
 *
 * <p>The design commitments are the ones that make a ledger trustworthy:
 *
 * <ul>
 *   <li><b>Append-only.</b> There is no update and no delete. A mistake is
 *       corrected by posting a reversing entry, so the wrong figure and its
 *       correction both remain visible.</li>
 *   <li><b>Balanced.</b> Every entry's debits equal its credits in each
 *       currency it touches. An unbalanced entry is rejected, not repaired.</li>
 *   <li><b>Balances are derived.</b> Account balances are a fold over the
 *       postings, never a stored mutable number that can drift away from the
 *       journal that is supposed to explain it.</li>
 *   <li><b>Hash-chained.</b> Each entry commits to its predecessor, so a
 *       retroactive edit is detectable.</li>
 * </ul>
 *
 * <p>Storage is in-memory: the point being demonstrated is the invariant set
 * and the posting model, and those are identical whether the journal lives in a
 * list or in an append-only table. A durable implementation would swap the list
 * for a database with a unique index on {@code sequence} and keep everything
 * else unchanged.
 */
@Component
public class Ledger {

    private final Clock clock;

    private final Map<String, LedgerAccount> accounts = new ConcurrentHashMap<>();
    private final List<JournalEntry> journal = new ArrayList<>();
    private final Map<String, JournalEntry> byReference = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    /** Hash of the empty journal — the anchor the chain is built on. */
    static final String GENESIS_HASH = "0".repeat(64);

    public Ledger(Clock clock) {
        this.clock = clock;
    }

    // --- Chart of accounts ----------------------------------------------

    public LedgerAccount open(LedgerAccount account) {
        LedgerAccount existing = accounts.putIfAbsent(account.id(), account);
        if (existing != null && !existing.equals(account)) {
            throw new LedgerException("Account " + account.id() + " already exists with different attributes");
        }
        return account;
    }

    public Optional<LedgerAccount> account(String accountId) {
        return Optional.ofNullable(accounts.get(accountId));
    }

    public Collection<LedgerAccount> accounts() {
        return accounts.values().stream()
                .sorted((a, b) -> a.id().compareTo(b.id()))
                .toList();
    }

    // --- Posting ---------------------------------------------------------

    /**
     * Validates and appends an entry.
     *
     * <p>Synchronized because the sequence number, the previous hash and the
     * journal tail must be taken together: two concurrent posts that both read
     * the same previous hash would fork the chain.
     */
    public synchronized JournalEntry post(JournalEntryDraft draft) {
        List<Posting> postings = draft.postings();
        validate(postings);

        Instant now = clock.instant();
        long seq = sequence.incrementAndGet();
        String previousHash = journal.isEmpty() ? GENESIS_HASH : journal.get(journal.size() - 1).hash();

        JournalEntry unhashed = new JournalEntry(
                UUID.randomUUID().toString(),
                seq,
                draft.effectiveAt() == null ? now : draft.effectiveAt(),
                now,
                draft.description(),
                draft.reference(),
                postings,
                previousHash,
                "");
        JournalEntry entry = new JournalEntry(
                unhashed.entryId(), seq, unhashed.effectiveAt(), now, unhashed.description(),
                unhashed.reference(), postings, previousHash, unhashed.computeHash());

        journal.add(entry);
        if (!entry.reference().isBlank()) {
            byReference.putIfAbsent(entry.reference(), entry);
        }
        return entry;
    }

    /**
     * Posts only if nothing has been posted under {@code reference} yet,
     * otherwise returns the entry that already exists.
     *
     * <p>This is the ledger's own contribution to exactly-once: even if a
     * caller retries after a timeout and the upstream idempotency check is
     * bypassed, the same business event cannot be booked twice.
     */
    public synchronized JournalEntry postOnce(String reference, JournalEntryDraft draft) {
        JournalEntry existing = byReference.get(reference);
        if (existing != null) {
            return existing;
        }
        return post(draft.reference(reference));
    }

    /**
     * Posts the mirror image of an existing entry. Used to correct mistakes and
     * to unwind reservations, and the only sanctioned way to "undo" anything.
     */
    public synchronized JournalEntry reverse(JournalEntry original, String reason) {
        JournalEntryDraft draft = JournalEntryDraft.of("Reversal of " + original.entryId() + ": " + reason)
                .reference(original.reference());
        for (Posting posting : original.postings()) {
            if (posting.isDebit()) {
                draft.credit(posting.accountId(), posting.amount(), "reversal: " + posting.narrative());
            } else {
                draft.debit(posting.accountId(), posting.amount(), "reversal: " + posting.narrative());
            }
        }
        return post(draft);
    }

    private void validate(List<Posting> postings) {
        if (postings.size() < 2) {
            throw new LedgerException("A journal entry needs at least two postings, got " + postings.size());
        }

        Map<String, Long> netByCurrency = new TreeMap<>();
        for (Posting posting : postings) {
            LedgerAccount account = accounts.get(posting.accountId());
            if (account == null) {
                throw new LedgerException("Unknown account: " + posting.accountId());
            }
            if (account.currency() != null && !account.currency().equals(posting.amount().currency())) {
                throw new LedgerException("Account " + account.id() + " is denominated in " + account.currency()
                        + " but the posting is in " + posting.amount().currency());
            }
            long signed = posting.isDebit() ? posting.amount().minorUnits() : -posting.amount().minorUnits();
            netByCurrency.merge(posting.amount().currency(), signed, Long::sum);
        }

        for (Map.Entry<String, Long> net : netByCurrency.entrySet()) {
            if (net.getValue() != 0) {
                throw new LedgerException("Entry does not balance in " + net.getKey()
                        + ": debits minus credits = " + net.getValue() + " minor units");
            }
        }
    }

    // --- Projections -----------------------------------------------------

    /**
     * The account's balance, expressed on its natural side: positive means a
     * debit balance for an asset or expense, a credit balance for a liability,
     * equity or revenue account.
     */
    public synchronized Money balanceOf(String accountId) {
        LedgerAccount account = accounts.get(accountId);
        if (account == null) {
            throw new LedgerException("Unknown account: " + accountId);
        }
        String currency = account.currency() == null ? "EUR" : account.currency();
        long total = 0;
        for (JournalEntry entry : journal) {
            for (Posting posting : entry.postings()) {
                if (posting.accountId().equals(accountId)) {
                    total += (long) account.type().signOf(posting.direction()) * posting.amount().minorUnits();
                }
            }
        }
        return Money.ofMinor(currency, total);
    }

    /** Every entry touching an account, oldest first. */
    public synchronized List<JournalEntry> entriesFor(String accountId) {
        return journal.stream()
                .filter(entry -> entry.postings().stream().anyMatch(p -> p.accountId().equals(accountId)))
                .toList();
    }

    public synchronized List<JournalEntry> entriesWithReference(String reference) {
        return journal.stream().filter(entry -> entry.reference().equals(reference)).toList();
    }

    public synchronized List<JournalEntry> entries() {
        return List.copyOf(journal);
    }

    public synchronized int size() {
        return journal.size();
    }

    /**
     * Totals every account by side. In a consistent ledger the debit column and
     * the credit column are equal for each currency — the aggregate form of the
     * per-entry balance rule, and the first thing to check when a reconciliation
     * break appears.
     */
    public synchronized TrialBalance trialBalance() {
        Map<String, Money> debitBalances = new LinkedHashMap<>();
        Map<String, Money> creditBalances = new LinkedHashMap<>();
        Map<String, Money> totalDebits = new TreeMap<>();
        Map<String, Money> totalCredits = new TreeMap<>();

        for (LedgerAccount account : accounts()) {
            long raw = 0;
            for (JournalEntry entry : journal) {
                for (Posting posting : entry.postings()) {
                    if (posting.accountId().equals(account.id())) {
                        raw += posting.isDebit() ? posting.amount().minorUnits() : -posting.amount().minorUnits();
                    }
                }
            }
            if (raw == 0) {
                continue;
            }
            String currency = account.currency() == null ? "EUR" : account.currency();
            Money amount = Money.ofMinor(currency, Math.abs(raw));
            if (raw > 0) {
                debitBalances.put(account.id(), amount);
                totalDebits.merge(currency, amount, Money::plus);
            } else {
                creditBalances.put(account.id(), amount);
                totalCredits.merge(currency, amount, Money::plus);
            }
        }
        return new TrialBalance(debitBalances, creditBalances, totalDebits, totalCredits);
    }

    /**
     * Walks the hash chain from the genesis anchor. A mismatch means an entry
     * was altered or removed after the fact.
     */
    public synchronized IntegrityReport verifyIntegrity() {
        String expectedPrevious = GENESIS_HASH;
        for (JournalEntry entry : journal) {
            if (!entry.previousHash().equals(expectedPrevious)) {
                return IntegrityReport.broken(entry, "previous hash does not match the preceding entry");
            }
            if (!entry.computeHash().equals(entry.hash())) {
                return IntegrityReport.broken(entry, "entry content does not match its recorded hash");
            }
            expectedPrevious = entry.hash();
        }
        return IntegrityReport.intact(journal.size());
    }

    /** Result of {@link Ledger#trialBalance()}. */
    public record TrialBalance(
            Map<String, Money> debitBalances,
            Map<String, Money> creditBalances,
            Map<String, Money> totalDebits,
            Map<String, Money> totalCredits) {

        public boolean balances() {
            return totalDebits.equals(totalCredits);
        }
    }

    /** Result of {@link Ledger#verifyIntegrity()}. */
    public record IntegrityReport(boolean intact, int entriesChecked, String failedEntryId, String reason) {

        static IntegrityReport intact(int entriesChecked) {
            return new IntegrityReport(true, entriesChecked, null, null);
        }

        static IntegrityReport broken(JournalEntry entry, String reason) {
            return new IntegrityReport(false, (int) entry.sequence(), entry.entryId(), reason);
        }
    }
}
