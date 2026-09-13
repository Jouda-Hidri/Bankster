package bankster.client.payments.ledger;

import java.util.List;

/**
 * The chart of accounts Bankster books against, modelled on how a payment
 * institution actually holds money rather than on a generic bookkeeping
 * example.
 *
 * <p>Two structural points are worth calling out, because they are where
 * naive payment ledgers go wrong:
 *
 * <ul>
 *   <li><b>Money in flight is an asset, not cash.</b> A captured card payment
 *       is not in the bank yet — it is a receivable from the acquirer that
 *       becomes cash only at settlement. Booking capture straight to the bank
 *       account overstates cash and makes settlement impossible to reconcile.</li>
 *   <li><b>Merchant money is a liability.</b> Funds owed to merchants are not
 *       revenue and not the institution's cash; only the fee margin is income.
 *       Conflating the two is the classic way a payments P&L ends up wrong by
 *       two orders of magnitude.</li>
 * </ul>
 *
 * <p>Account ids are suffixed with the currency because a
 * {@link LedgerAccount} is single-currency by construction.
 */
public final class ChartOfAccounts {

    private ChartOfAccounts() {
    }

    /** Cash actually held at the institution's own bank. */
    public static final String BANK_OPERATING = "assets.bank.operating";

    /** Captured card volume owed to us by the acquirer, until it settles. */
    public static final String SCHEME_RECEIVABLE = "assets.scheme.receivable";

    /** Balance at the SEPA clearing and settlement mechanism. */
    public static final String SEPA_SETTLEMENT = "assets.settlement.sepa";

    /** Nostro account held with a correspondent for cross-border SWIFT payments. */
    public static final String CORRESPONDENT_NOSTRO = "assets.correspondent.nostro";

    /** Items that could not be matched and must not be silently absorbed. */
    public static final String SUSPENSE = "assets.suspense.unreconciled";

    /** Net proceeds owed to merchants, before payout. */
    public static final String MERCHANT_PAYABLE = "liabilities.merchant.payable";

    /** Safeguarded customer balances — never the institution's own money. */
    public static final String CUSTOMER_FUNDS = "liabilities.customer.funds";

    /** Outbound payments instructed but not yet settled on the rail. */
    public static final String PAYMENTS_IN_TRANSIT = "liabilities.payments.in_transit";

    /** The merchant discount rate we earn on card volume. */
    public static final String REVENUE_MERCHANT_DISCOUNT = "revenue.fees.merchant_discount";

    /** Fees charged to a merchant when a chargeback is raised against them. */
    public static final String REVENUE_CHARGEBACK_FEES = "revenue.fees.chargeback";

    /** Fees earned on SEPA and cross-border transfers. */
    public static final String REVENUE_TRANSFER_FEES = "revenue.fees.transfer";

    /** Interchange paid away to the issuer on each card transaction. */
    public static final String EXPENSE_INTERCHANGE = "expense.interchange";

    /** Scheme assessments paid to Visa/Mastercard. */
    public static final String EXPENSE_SCHEME_FEES = "expense.scheme_fees";

    /** Losses absorbed when a chargeback cannot be recovered from the merchant. */
    public static final String EXPENSE_CHARGEBACK_LOSSES = "expense.chargeback_losses";

    private record Definition(String id, String name, AccountType type) {
    }

    private static final List<Definition> DEFINITIONS = List.of(
            new Definition(BANK_OPERATING, "Operating bank account", AccountType.ASSET),
            new Definition(SCHEME_RECEIVABLE, "Card scheme receivable", AccountType.ASSET),
            new Definition(SEPA_SETTLEMENT, "SEPA settlement account", AccountType.ASSET),
            new Definition(CORRESPONDENT_NOSTRO, "Correspondent nostro account", AccountType.ASSET),
            new Definition(SUSPENSE, "Unreconciled suspense", AccountType.ASSET),
            new Definition(MERCHANT_PAYABLE, "Merchant payable", AccountType.LIABILITY),
            new Definition(CUSTOMER_FUNDS, "Safeguarded customer funds", AccountType.LIABILITY),
            new Definition(PAYMENTS_IN_TRANSIT, "Payments in transit", AccountType.LIABILITY),
            new Definition(REVENUE_MERCHANT_DISCOUNT, "Merchant discount revenue", AccountType.REVENUE),
            new Definition(REVENUE_CHARGEBACK_FEES, "Chargeback fee revenue", AccountType.REVENUE),
            new Definition(REVENUE_TRANSFER_FEES, "Transfer fee revenue", AccountType.REVENUE),
            new Definition(EXPENSE_INTERCHANGE, "Interchange expense", AccountType.EXPENSE),
            new Definition(EXPENSE_SCHEME_FEES, "Scheme fees expense", AccountType.EXPENSE),
            new Definition(EXPENSE_CHARGEBACK_LOSSES, "Chargeback losses", AccountType.EXPENSE));

    /** Account id for a base account in a given currency, e.g. {@code assets.bank.operating.EUR}. */
    public static String in(String baseId, String currency) {
        return baseId + "." + currency.toUpperCase();
    }

    /** Per-merchant payable, so a payout can be computed without scanning every posting. */
    public static String merchantPayable(String merchantId, String currency) {
        return in(MERCHANT_PAYABLE + "." + merchantId, currency);
    }

    /**
     * Per-customer safeguarded balance.
     *
     * <p>One account per customer rather than a single pooled one, because a
     * pooled balance cannot answer "does this customer have enough to send
     * this payment" — and answering that from a pool is how one customer ends
     * up spending another's money.
     */
    public static String customerFunds(String customerId, String currency) {
        return in(CUSTOMER_FUNDS + "." + customerId, currency);
    }

    /** Opens a customer's safeguarded balance account. Safe to call repeatedly. */
    public static void openCustomerAccount(Ledger ledger, String customerId, String currency) {
        ledger.open(new LedgerAccount(
                customerFunds(customerId, currency),
                "Safeguarded funds — " + customerId + " (" + currency + ")",
                AccountType.LIABILITY,
                currency));
    }

    /** Opens every account in the chart for one currency, including a payable per merchant. */
    public static void bootstrap(Ledger ledger, String currency, List<String> merchantIds) {
        for (Definition definition : DEFINITIONS) {
            ledger.open(new LedgerAccount(
                    in(definition.id(), currency),
                    definition.name() + " (" + currency + ")",
                    definition.type(),
                    currency));
        }
        for (String merchantId : merchantIds) {
            ledger.open(new LedgerAccount(
                    merchantPayable(merchantId, currency),
                    "Merchant payable — " + merchantId + " (" + currency + ")",
                    AccountType.LIABILITY,
                    currency));
        }
    }
}
