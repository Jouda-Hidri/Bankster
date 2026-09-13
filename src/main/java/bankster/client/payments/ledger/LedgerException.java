package bankster.client.payments.ledger;

/** Raised when a proposed journal entry would break a ledger invariant. */
public class LedgerException extends RuntimeException {

    public LedgerException(String message) {
        super(message);
    }
}
