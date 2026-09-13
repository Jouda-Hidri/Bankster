package bankster.client.payments.ledger;

/** The two sides of a posting. Every journal entry must balance debits against credits. */
public enum Direction {
    DEBIT,
    CREDIT;

    public Direction opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
