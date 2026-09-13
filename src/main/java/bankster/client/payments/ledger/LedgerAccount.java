package bankster.client.payments.ledger;

/**
 * An account in the chart of accounts.
 *
 * <p>{@code currency} pins an account to a single currency. Mixing currencies
 * in one account makes its balance meaningless, so multi-currency operations
 * open one account per currency instead.
 */
public record LedgerAccount(String id, String name, AccountType type, String currency) {

    public LedgerAccount {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Account needs an id");
        }
        if (type == null) {
            throw new IllegalArgumentException("Account " + id + " needs a type");
        }
    }
}
