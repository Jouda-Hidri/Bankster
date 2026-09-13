package bankster.client.payments.ledger;

import bankster.client.payments.Money;

/**
 * One leg of a journal entry: an amount moved onto one side of one account.
 *
 * <p>The amount is always positive. A "negative debit" and a credit are the
 * same thing, and allowing both spellings means the sum-to-zero check can be
 * satisfied by entries that are nonsense.
 */
public record Posting(String accountId, Direction direction, Money amount, String narrative) {

    public Posting {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("Posting needs an account id");
        }
        if (direction == null) {
            throw new IllegalArgumentException("Posting needs a direction");
        }
        if (amount == null || !amount.isPositive()) {
            throw new IllegalArgumentException(
                    "Posting amount must be positive; use the opposite direction instead of a negative amount");
        }
    }

    public static Posting debit(String accountId, Money amount, String narrative) {
        return new Posting(accountId, Direction.DEBIT, amount, narrative);
    }

    public static Posting credit(String accountId, Money amount, String narrative) {
        return new Posting(accountId, Direction.CREDIT, amount, narrative);
    }

    public boolean isDebit() {
        return direction == Direction.DEBIT;
    }
}
