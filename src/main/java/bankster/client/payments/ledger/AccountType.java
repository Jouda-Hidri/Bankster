package bankster.client.payments.ledger;

/**
 * The five classes of account in double-entry bookkeeping, each with the side
 * on which a positive balance sits.
 *
 * <p>The normal side is what makes a trial balance readable: an asset with a
 * credit balance or a liability with a debit balance is not illegal, but it is
 * almost always a sign that postings were wired the wrong way round.
 */
public enum AccountType {

    /** Things the business owns or is owed — cash at bank, receivables from the scheme. */
    ASSET(Direction.DEBIT),

    /** Things the business owes — merchant payables, customer funds held. */
    LIABILITY(Direction.CREDIT),

    /** The residual claim of the owners. */
    EQUITY(Direction.CREDIT),

    /** Fees earned — interchange, scheme and processing margin. */
    REVENUE(Direction.CREDIT),

    /** Costs incurred — chargeback losses, scheme assessments. */
    EXPENSE(Direction.DEBIT);

    private final Direction normalBalance;

    AccountType(Direction normalBalance) {
        this.normalBalance = normalBalance;
    }

    public Direction normalBalance() {
        return normalBalance;
    }

    /** Signed contribution of a posting on {@code direction} to this account's balance. */
    public int signOf(Direction direction) {
        return direction == normalBalance ? 1 : -1;
    }
}
