package bankster.client.payments.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import bankster.client.payments.Money;
import bankster.client.payments.TestClock;

/** The invariants that make the ledger worth trusting. */
class LedgerTest {

    private TestClock clock;
    private Ledger ledger;

    @BeforeEach
    void setUp() {
        clock = TestClock.businessDayMorning();
        ledger = new Ledger(clock);
        ChartOfAccounts.bootstrap(ledger, "EUR", List.of("merchant-1"));
    }

    private String bank() {
        return ChartOfAccounts.in(ChartOfAccounts.BANK_OPERATING, "EUR");
    }

    private String receivable() {
        return ChartOfAccounts.in(ChartOfAccounts.SCHEME_RECEIVABLE, "EUR");
    }

    private String payable() {
        return ChartOfAccounts.merchantPayable("merchant-1", "EUR");
    }

    private String revenue() {
        return ChartOfAccounts.in(ChartOfAccounts.REVENUE_MERCHANT_DISCOUNT, "EUR");
    }

    // --- Balance enforcement ---------------------------------------------

    @Test
    void acceptsABalancedEntry() {
        JournalEntry entry = ledger.post(JournalEntryDraft.of("funding")
                .reference("ref-1")
                .debit(bank(), Money.of("EUR", "100.00"), "in")
                .credit(receivable(), Money.of("EUR", "100.00"), "out"));

        assertEquals(1, entry.sequence());
        assertEquals(1, ledger.size());
        assertEquals(Money.of("EUR", "100.00"), entry.totalDebits().get("EUR"));
        assertEquals(Money.of("EUR", "100.00"), entry.totalCredits().get("EUR"));
    }

    @Test
    void rejectsAnUnbalancedEntry() {
        LedgerException failure = assertThrows(LedgerException.class, () ->
                ledger.post(JournalEntryDraft.of("wrong")
                        .debit(bank(), Money.of("EUR", "100.00"), "in")
                        .credit(receivable(), Money.of("EUR", "99.00"), "out")));

        assertTrue(failure.getMessage().contains("does not balance"), failure.getMessage());
        assertEquals(0, ledger.size(), "a rejected entry must leave no trace");
    }

    @Test
    void rejectsASinglePostingEntry() {
        LedgerException failure = assertThrows(LedgerException.class, () ->
                ledger.post(JournalEntryDraft.of("half")
                        .debit(bank(), Money.of("EUR", "100.00"), "in")));

        assertTrue(failure.getMessage().contains("at least two postings"), failure.getMessage());
    }

    @Test
    void rejectsAPostingToAnUnknownAccount() {
        assertThrows(LedgerException.class, () ->
                ledger.post(JournalEntryDraft.of("typo")
                        .debit("assets.does.not.exist.EUR", Money.of("EUR", "1.00"), "in")
                        .credit(bank(), Money.of("EUR", "1.00"), "out")));
    }

    @Test
    void rejectsAPostingInTheWrongCurrencyForItsAccount() {
        LedgerException failure = assertThrows(LedgerException.class, () ->
                ledger.post(JournalEntryDraft.of("currency mix")
                        .debit(bank(), Money.of("USD", "100.00"), "in")
                        .credit(receivable(), Money.of("USD", "100.00"), "out")));

        assertTrue(failure.getMessage().contains("denominated in EUR"), failure.getMessage());
    }

    @Test
    void rejectsANegativePostingAmount() {
        // A negative debit and a credit are the same thing; allowing both spellings
        // would let nonsense entries satisfy the sum-to-zero check.
        assertThrows(IllegalArgumentException.class,
                () -> Posting.debit(bank(), Money.of("EUR", "-1.00"), "negative"));
        assertThrows(IllegalArgumentException.class,
                () -> Posting.debit(bank(), Money.zero("EUR"), "zero"));
    }

    @Test
    void balancesEachCurrencyIndependently() {
        ChartOfAccounts.bootstrap(ledger, "USD", List.of("merchant-1"));
        String usdBank = ChartOfAccounts.in(ChartOfAccounts.BANK_OPERATING, "USD");
        String usdReceivable = ChartOfAccounts.in(ChartOfAccounts.SCHEME_RECEIVABLE, "USD");

        // Balanced in EUR and balanced in USD — acceptable, even in one entry.
        ledger.post(JournalEntryDraft.of("two currencies")
                .debit(bank(), Money.of("EUR", "10.00"), "eur in")
                .credit(receivable(), Money.of("EUR", "10.00"), "eur out")
                .debit(usdBank, Money.of("USD", "20.00"), "usd in")
                .credit(usdReceivable, Money.of("USD", "20.00"), "usd out"));

        assertEquals(1, ledger.size());

        // Balanced overall by minor-unit count but not per currency — rejected.
        assertThrows(LedgerException.class, () ->
                ledger.post(JournalEntryDraft.of("cross-currency nonsense")
                        .debit(bank(), Money.of("EUR", "10.00"), "eur in")
                        .credit(usdReceivable, Money.of("USD", "10.00"), "usd out")));
    }

    // --- Balances are derived --------------------------------------------

    @Test
    void balancesAreFoldedOverPostingsOnTheAccountsNaturalSide() {
        ledger.post(JournalEntryDraft.of("capture")
                .debit(receivable(), Money.of("EUR", "100.00"), "due from acquirer")
                .credit(payable(), Money.of("EUR", "95.00"), "owed to merchant")
                .credit(revenue(), Money.of("EUR", "5.00"), "our margin"));

        // An asset's natural side is debit, a liability's and revenue's is credit, so
        // all three read positive.
        assertEquals(Money.of("EUR", "100.00"), ledger.balanceOf(receivable()));
        assertEquals(Money.of("EUR", "95.00"), ledger.balanceOf(payable()));
        assertEquals(Money.of("EUR", "5.00"), ledger.balanceOf(revenue()));
    }

    @Test
    void trialBalanceAgreesAcrossEveryEntry() {
        ledger.post(JournalEntryDraft.of("capture")
                .debit(receivable(), Money.of("EUR", "100.00"), "a")
                .credit(payable(), Money.of("EUR", "95.00"), "b")
                .credit(revenue(), Money.of("EUR", "5.00"), "c"));
        ledger.post(JournalEntryDraft.of("settlement")
                .debit(bank(), Money.of("EUR", "100.00"), "d")
                .credit(receivable(), Money.of("EUR", "100.00"), "e"));

        Ledger.TrialBalance trialBalance = ledger.trialBalance();

        assertTrue(trialBalance.balances());
        assertEquals(trialBalance.totalDebits().get("EUR"), trialBalance.totalCredits().get("EUR"));
        // The receivable was opened and then cleared, so it drops out entirely.
        assertFalse(trialBalance.debitBalances().containsKey(receivable()));
        assertEquals(Money.of("EUR", "100.00"), trialBalance.debitBalances().get(bank()));
    }

    @Test
    void separatesTheEconomicDateFromTheRecordedDate() {
        Instant threeWeeksAgo = clock.instant().minusSeconds(21 * 86_400);

        JournalEntry entry = ledger.post(JournalEntryDraft.of("late chargeback")
                .effectiveAt(threeWeeksAgo)
                .debit(payable(), Money.of("EUR", "50.00"), "a")
                .credit(receivable(), Money.of("EUR", "50.00"), "b"));

        assertEquals(threeWeeksAgo, entry.effectiveAt());
        assertEquals(clock.instant(), entry.recordedAt());
    }

    // --- Append-only and reversal ----------------------------------------

    @Test
    void correctionIsAReversingEntryRatherThanAnEdit() {
        JournalEntry original = ledger.post(JournalEntryDraft.of("mistake")
                .reference("pay-1")
                .debit(receivable(), Money.of("EUR", "100.00"), "a")
                .credit(payable(), Money.of("EUR", "100.00"), "b"));

        JournalEntry reversal = ledger.reverse(original, "booked against the wrong merchant");

        assertEquals(2, ledger.size(), "the wrong entry stays in the journal");
        assertTrue(reversal.description().contains("Reversal of " + original.entryId()));
        assertEquals("pay-1", reversal.reference());
        // Net effect is nil, and both halves remain visible.
        assertEquals(Money.zero("EUR"), ledger.balanceOf(receivable()));
        assertEquals(Money.zero("EUR"), ledger.balanceOf(payable()));
        assertTrue(ledger.trialBalance().balances());
    }

    @Test
    void postOnceIsIdempotentOnItsReference() {
        JournalEntry first = ledger.postOnce("pay-7", JournalEntryDraft.of("capture")
                .debit(receivable(), Money.of("EUR", "40.00"), "a")
                .credit(payable(), Money.of("EUR", "40.00"), "b"));

        JournalEntry second = ledger.postOnce("pay-7", JournalEntryDraft.of("capture again")
                .debit(receivable(), Money.of("EUR", "40.00"), "a")
                .credit(payable(), Money.of("EUR", "40.00"), "b"));

        assertSame(first, second, "a retried business event must not be booked twice");
        assertEquals(1, ledger.size());
        assertEquals(Money.of("EUR", "40.00"), ledger.balanceOf(receivable()));
    }

    // --- Hash chain -------------------------------------------------------

    @Test
    void theChainStartsAtGenesisAndLinksEachEntryToItsPredecessor() {
        JournalEntry first = ledger.post(JournalEntryDraft.of("one")
                .debit(bank(), Money.of("EUR", "1.00"), "a")
                .credit(receivable(), Money.of("EUR", "1.00"), "b"));
        JournalEntry second = ledger.post(JournalEntryDraft.of("two")
                .debit(bank(), Money.of("EUR", "2.00"), "a")
                .credit(receivable(), Money.of("EUR", "2.00"), "b"));

        assertEquals(Ledger.GENESIS_HASH, first.previousHash());
        assertEquals(first.hash(), second.previousHash());
        assertNotEquals(first.hash(), second.hash());

        Ledger.IntegrityReport report = ledger.verifyIntegrity();
        assertTrue(report.intact());
        assertEquals(2, report.entriesChecked());
    }

    @Test
    void alteringAnEntrysContentBreaksItsRecordedHash() {
        JournalEntry entry = ledger.post(JournalEntryDraft.of("original")
                .debit(bank(), Money.of("EUR", "10.00"), "a")
                .credit(receivable(), Money.of("EUR", "10.00"), "b"));

        // Records are immutable, so a tamperer would have to rebuild the entry —
        // and the rebuilt content no longer matches the stored digest.
        JournalEntry tampered = new JournalEntry(entry.entryId(), entry.sequence(),
                entry.effectiveAt(), entry.recordedAt(), entry.description(), entry.reference(),
                List.of(Posting.debit(bank(), Money.of("EUR", "1000.00"), "a"),
                        Posting.credit(receivable(), Money.of("EUR", "1000.00"), "b")),
                entry.previousHash(), entry.hash());

        assertNotEquals(tampered.hash(), tampered.computeHash());
    }

    @Test
    void reorderingPostingsChangesTheDigest() {
        JournalEntry entry = ledger.post(JournalEntryDraft.of("order matters")
                .debit(bank(), Money.of("EUR", "10.00"), "a")
                .credit(receivable(), Money.of("EUR", "10.00"), "b"));

        JournalEntry reordered = new JournalEntry(entry.entryId(), entry.sequence(),
                entry.effectiveAt(), entry.recordedAt(), entry.description(), entry.reference(),
                List.of(entry.postings().get(1), entry.postings().get(0)),
                entry.previousHash(), entry.hash());

        assertNotEquals(entry.hash(), reordered.computeHash());
    }

    // --- Lookups ----------------------------------------------------------

    @Test
    void entriesCanBeFoundByAccountAndByReference() {
        ledger.post(JournalEntryDraft.of("one").reference("pay-1")
                .debit(receivable(), Money.of("EUR", "10.00"), "a")
                .credit(payable(), Money.of("EUR", "10.00"), "b"));
        ledger.post(JournalEntryDraft.of("two").reference("pay-2")
                .debit(bank(), Money.of("EUR", "20.00"), "a")
                .credit(receivable(), Money.of("EUR", "20.00"), "b"));

        assertEquals(2, ledger.entriesFor(receivable()).size());
        assertEquals(1, ledger.entriesFor(payable()).size());
        assertEquals(1, ledger.entriesWithReference("pay-2").size());
        assertTrue(ledger.entriesWithReference("pay-999").isEmpty());
    }

    @Test
    void openingTheSameAccountTwiceIsFineButRedefiningItIsNot() {
        LedgerAccount account = new LedgerAccount("assets.test.EUR", "Test", AccountType.ASSET, "EUR");
        ledger.open(account);
        ledger.open(account);

        assertThrows(LedgerException.class, () -> ledger.open(
                new LedgerAccount("assets.test.EUR", "Test", AccountType.LIABILITY, "EUR")));
    }

    @Test
    void accountTypesKnowWhichSideTheyLiveOn() {
        assertEquals(Direction.DEBIT, AccountType.ASSET.normalBalance());
        assertEquals(Direction.CREDIT, AccountType.LIABILITY.normalBalance());
        assertEquals(1, AccountType.ASSET.signOf(Direction.DEBIT));
        assertEquals(-1, AccountType.ASSET.signOf(Direction.CREDIT));
        assertEquals(1, AccountType.REVENUE.signOf(Direction.CREDIT));
    }
}
