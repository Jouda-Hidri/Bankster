package bankster.client.payments.rails;

import java.time.LocalDate;
import java.util.List;

import bankster.client.payments.Money;

/**
 * A bank statement, as parsed from a {@code camt.053}.
 *
 * <p>This is the external truth reconciliation is run against. The internal
 * ledger says what the institution believes happened; the statement says what the
 * bank actually did. Where they disagree, the statement is not automatically right
 * — but the difference always has to be explained.
 *
 * <p>Opening and closing balances are carried because they make the statement
 * self-checking: opening plus the signed sum of the entries must equal closing. A
 * statement that fails that test has been truncated or mis-parsed, and
 * reconciling against it would produce nonsense.
 */
public record BankStatement(
        String statementId,
        String accountIban,
        LocalDate fromDate,
        LocalDate toDate,
        Money openingBalance,
        Money closingBalance,
        List<StatementEntry> entries) {

    public BankStatement {
        entries = List.copyOf(entries);
    }

    /**
     * One booked movement.
     *
     * <p>{@code endToEndId} is the field that makes automatic matching possible.
     * It is the payer's own reference, preserved unchanged by every institution in
     * the chain, so it identifies the internal record the entry corresponds to.
     * Matching on amount and date alone fails as soon as two payments of the same
     * amount happen on the same day — which, for any real volume, is constantly.
     */
    public record StatementEntry(
            String entryReference,
            Money amount,
            boolean credit,
            LocalDate bookingDate,
            LocalDate valueDate,
            String counterpartyName,
            String counterpartyIban,
            String remittanceInformation,
            String endToEndId,
            String bankTransactionCode) {

        /** Positive for a credit, negative for a debit. */
        public Money signedAmount() {
            return credit ? amount : amount.negate();
        }
    }

    /** Opening balance plus the signed entries — must equal the closing balance. */
    public Money computedClosingBalance() {
        Money running = openingBalance;
        for (StatementEntry entry : entries) {
            running = running.plus(entry.signedAmount());
        }
        return running;
    }

    /**
     * Whether the statement is internally consistent. False means the file is
     * incomplete, not that the bank is wrong.
     */
    public boolean isSelfConsistent() {
        return computedClosingBalance().equals(closingBalance);
    }

    public List<StatementEntry> credits() {
        return entries.stream().filter(StatementEntry::credit).toList();
    }

    public List<StatementEntry> debits() {
        return entries.stream().filter(entry -> !entry.credit()).toList();
    }
}
