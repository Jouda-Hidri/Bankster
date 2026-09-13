package bankster.client.payments.rails;

import java.util.List;

import org.springframework.stereotype.Component;

import bankster.client.payments.Money;
import bankster.client.payments.ledger.ChartOfAccounts;
import bankster.client.payments.ledger.JournalEntry;
import bankster.client.payments.ledger.JournalEntryDraft;
import bankster.client.payments.ledger.Ledger;

/**
 * Accounting for outbound credit transfers.
 *
 * <p>The structural decision is the in-transit account. When a transfer is
 * instructed, the customer's money has left their balance but has not yet reached
 * the beneficiary, and it is not ours either — it is a liability sitting in
 * flight. Booking the debit straight against the clearing balance would claim
 * settlement that has not happened, and on a rail where settlement takes until
 * the next business day that claim would be wrong for most of a day, for every
 * payment.
 *
 * <p>Holding it in transit also gives reconciliation something to work with: the
 * in-transit balance should equal the sum of instructed-but-unsettled transfers,
 * and any difference is a break worth investigating immediately.
 *
 * <p>Customer balances are per customer, not pooled, so that an available-funds
 * check means something.
 */
@Component
public class TransferBookkeeper {

    private final Ledger ledger;

    public TransferBookkeeper(Ledger ledger) {
        this.ledger = ledger;
    }

    private void ensureAccounts(String currency, String customerId) {
        ChartOfAccounts.bootstrap(ledger, currency, List.of());
        if (customerId != null) {
            ChartOfAccounts.openCustomerAccount(ledger, customerId, currency);
        }
    }

    /**
     * Books the instruction.
     *
     * <pre>
     *   DR  customer funds        amount + fee
     *     CR  payments in transit  amount
     *     CR  transfer fee revenue fee
     * </pre>
     */
    public JournalEntry recordInstruction(String transferId, String customerId, Money amount, Money fee) {
        String currency = amount.currency();
        ensureAccounts(currency, customerId);

        JournalEntryDraft draft = JournalEntryDraft.of("Credit transfer instructed " + transferId)
                .reference(transferId)
                .debit(ChartOfAccounts.customerFunds(customerId, currency), amount.plus(fee),
                        "debited from the payer's balance")
                .credit(ChartOfAccounts.in(ChartOfAccounts.PAYMENTS_IN_TRANSIT, currency), amount,
                        "owed to the beneficiary until settlement");
        if (fee.isPositive()) {
            draft.credit(ChartOfAccounts.in(ChartOfAccounts.REVENUE_TRANSFER_FEES, currency), fee,
                    "transfer fee earned");
        }
        return ledger.post(draft);
    }

    /**
     * Books settlement: the in-transit liability is discharged against the
     * clearing balance.
     *
     * <pre>
     *   DR  payments in transit   amount
     *     CR  SEPA settlement      amount
     * </pre>
     */
    public JournalEntry recordSettlement(String transferId, Money amount) {
        String currency = amount.currency();
        ensureAccounts(currency, null);

        return ledger.post(JournalEntryDraft.of("Credit transfer settled " + transferId)
                .reference(transferId)
                .debit(ChartOfAccounts.in(ChartOfAccounts.PAYMENTS_IN_TRANSIT, currency), amount,
                        "liability discharged on settlement")
                .credit(ChartOfAccounts.in(ChartOfAccounts.SEPA_SETTLEMENT, currency), amount,
                        "paid away at the clearing mechanism"));
    }

    /**
     * Books a rejection before the money left: the in-transit liability is
     * unwound and the customer is made whole, fee included.
     */
    public JournalEntry recordRejection(String transferId, String customerId, Money amount, Money fee) {
        String currency = amount.currency();
        ensureAccounts(currency, customerId);

        JournalEntryDraft draft = JournalEntryDraft.of("Credit transfer rejected " + transferId)
                .reference(transferId)
                .debit(ChartOfAccounts.in(ChartOfAccounts.PAYMENTS_IN_TRANSIT, currency), amount,
                        "instruction withdrawn")
                .credit(ChartOfAccounts.customerFunds(customerId, currency), amount.plus(fee),
                        "returned to the payer's balance");
        if (fee.isPositive()) {
            // The fee is refunded too, so the revenue recognised at instruction
            // has to be given back.
            draft.debit(ChartOfAccounts.in(ChartOfAccounts.REVENUE_TRANSFER_FEES, currency), fee,
                    "fee refunded on rejection");
        }
        return ledger.post(draft);
    }

    /**
     * Books a return after settlement — an R-transaction.
     *
     * <p>Distinct from a rejection, because the money genuinely left and has come
     * back. The fee is not returned: the transfer was executed as instructed.
     */
    public JournalEntry recordReturn(String transferId, String customerId, Money amount) {
        String currency = amount.currency();
        ensureAccounts(currency, customerId);

        return ledger.post(JournalEntryDraft.of("Credit transfer returned " + transferId)
                .reference(transferId)
                .debit(ChartOfAccounts.in(ChartOfAccounts.SEPA_SETTLEMENT, currency), amount,
                        "funds received back from the beneficiary's bank")
                .credit(ChartOfAccounts.customerFunds(customerId, currency), amount,
                        "credited back to the payer"));
    }

    /** Funds a customer balance, so the demo has money to send. */
    public JournalEntry recordCustomerDeposit(String customerId, String reference, Money amount) {
        String currency = amount.currency();
        ensureAccounts(currency, customerId);

        return ledger.post(JournalEntryDraft.of("Customer deposit " + reference)
                .reference(reference)
                .debit(ChartOfAccounts.in(ChartOfAccounts.BANK_OPERATING, currency), amount,
                        "funds received")
                .credit(ChartOfAccounts.customerFunds(customerId, currency), amount,
                        "safeguarded on behalf of the customer"));
    }

    public Money customerFunds(String customerId, String currency) {
        ensureAccounts(currency, customerId);
        return ledger.balanceOf(ChartOfAccounts.customerFunds(customerId, currency));
    }

    public Money paymentsInTransit(String currency) {
        ensureAccounts(currency, null);
        return ledger.balanceOf(ChartOfAccounts.in(ChartOfAccounts.PAYMENTS_IN_TRANSIT, currency));
    }
}
