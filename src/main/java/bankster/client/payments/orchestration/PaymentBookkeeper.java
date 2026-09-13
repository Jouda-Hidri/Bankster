package bankster.client.payments.orchestration;

import java.util.List;

import org.springframework.stereotype.Component;

import bankster.client.payments.Money;
import bankster.client.payments.cards.FeeSchedule;
import bankster.client.payments.ledger.ChartOfAccounts;
import bankster.client.payments.ledger.JournalEntry;
import bankster.client.payments.ledger.JournalEntryDraft;
import bankster.client.payments.ledger.Ledger;

/**
 * The accounting recipes for each stage of a payment's life.
 *
 * <p>Keeping them in one place makes the most important property of the design
 * checkable at a glance: <b>authorization books nothing</b>. An authorization
 * moves no money — it only reduces what the cardholder may spend elsewhere — so
 * there is no entry to make. Recording it as a receivable would overstate both
 * assets and merchant liabilities, by the whole value of every authorization
 * that is later voided or left to expire.
 *
 * <p>Capture is the first event with an accounting consequence, and it is
 * deliberately booked as five postings rather than two, because five different
 * things are true at once: a receivable exists against the acquirer, interchange
 * and scheme fees have been incurred, the merchant is owed its net, and the
 * margin has been earned. Netting those into one figure would make the P&L
 * unrecoverable.
 */
@Component
public class PaymentBookkeeper {

    private final Ledger ledger;

    public PaymentBookkeeper(Ledger ledger) {
        this.ledger = ledger;
    }

    /** Opens the accounts a merchant's traffic needs. Safe to call repeatedly. */
    public void ensureAccounts(String currency, String merchantId) {
        ChartOfAccounts.bootstrap(ledger, currency, List.of(merchantId));
    }

    /**
     * Books a capture.
     *
     * <pre>
     *   DR  scheme receivable        gross − interchange − scheme fees
     *   DR  interchange expense      interchange
     *   DR  scheme fees expense      scheme fees
     *     CR  merchant payable       gross − merchant discount
     *     CR  merchant discount rev. merchant discount
     * </pre>
     *
     * <p>Both columns total the gross amount. The receivable is deliberately net
     * of the fees the scheme will deduct before paying us, so that settlement
     * later matches to the cent against what actually arrives.
     */
    public JournalEntry recordCapture(String paymentId, String merchantId, Money gross, FeeSchedule fees) {
        String currency = gross.currency();
        ensureAccounts(currency, merchantId);

        Money interchange = fees.interchange(gross);
        Money schemeFee = fees.schemeFee();
        Money merchantDiscount = fees.merchantDiscount(gross);
        Money receivable = gross.minus(interchange).minus(schemeFee);
        Money netToMerchant = gross.minus(merchantDiscount);

        JournalEntryDraft draft = JournalEntryDraft.of("Card capture " + paymentId)
                .reference(paymentId)
                .debit(ChartOfAccounts.in(ChartOfAccounts.SCHEME_RECEIVABLE, currency), receivable,
                        "due from acquirer, net of fees deducted at source")
                .debit(ChartOfAccounts.in(ChartOfAccounts.EXPENSE_INTERCHANGE, currency), interchange,
                        "interchange paid to issuer")
                .credit(ChartOfAccounts.merchantPayable(merchantId, currency), netToMerchant,
                        "owed to merchant " + merchantId)
                .credit(ChartOfAccounts.in(ChartOfAccounts.REVENUE_MERCHANT_DISCOUNT, currency), merchantDiscount,
                        "merchant discount earned");

        // A zero scheme fee would be an invalid posting, so it is only added when
        // it is actually charged.
        if (schemeFee.isPositive()) {
            draft.debit(ChartOfAccounts.in(ChartOfAccounts.EXPENSE_SCHEME_FEES, currency), schemeFee,
                    "scheme assessment");
        }
        return ledger.post(draft);
    }

    /**
     * Books a refund.
     *
     * <p>The fees are not given back — neither by the scheme to us, nor by us to
     * the merchant — which is why this is not a mirror image of the capture. The
     * merchant bears the full amount returned to the cardholder and has already
     * paid the discount on the original sale.
     */
    public JournalEntry recordRefund(String paymentId, String refundId, String merchantId, Money amount) {
        String currency = amount.currency();
        ensureAccounts(currency, merchantId);

        return ledger.post(JournalEntryDraft.of("Refund " + refundId + " on " + paymentId)
                .reference(paymentId)
                .debit(ChartOfAccounts.merchantPayable(merchantId, currency), amount,
                        "recovered from merchant " + merchantId)
                .credit(ChartOfAccounts.in(ChartOfAccounts.SCHEME_RECEIVABLE, currency), amount,
                        "returned to cardholder via the scheme"));
    }

    /**
     * Books a chargeback: the issuer has taken the money back.
     *
     * <pre>
     *   DR  merchant payable        recovered from the merchant
     *   DR  chargeback losses       the part of the dispute they could not cover
     *     CR  scheme receivable       the disputed amount, withdrawn by the issuer
     *     CR  chargeback fee revenue  the part of our fee we actually collected
     * </pre>
     *
     * <p>The allocation matters and is easy to get wrong. The issuer takes the
     * <em>disputed amount</em> from us, no more — the handling fee is ours, not
     * theirs. So when the merchant's balance cannot cover both, recovery is applied
     * to the disputed amount first and the fee second: we would rather recover the
     * money we actually owe the issuer than the fee we would like to earn. Whatever
     * is still short on the disputed amount is an expense, and that expense is the
     * acquirer's real credit risk — if a merchant disappears owing disputed volume,
     * the acquirer still owes the issuers.
     *
     * @param recoveredFromMerchant total debited from the merchant's balance
     * @param recoveredFee          the portion of that which was our fee
     * @param lossAbsorbed          the portion of the disputed amount we could not recover
     */
    public JournalEntry recordChargeback(String caseId, String paymentId, String merchantId,
                                         Money disputed, Money recoveredFromMerchant,
                                         Money recoveredFee, Money lossAbsorbed) {
        String currency = disputed.currency();
        ensureAccounts(currency, merchantId);

        JournalEntryDraft draft = JournalEntryDraft.of("Chargeback " + caseId + " on " + paymentId)
                .reference(paymentId)
                .credit(ChartOfAccounts.in(ChartOfAccounts.SCHEME_RECEIVABLE, currency), disputed,
                        "funds withdrawn by the issuer");
        if (recoveredFromMerchant.isPositive()) {
            draft.debit(ChartOfAccounts.merchantPayable(merchantId, currency), recoveredFromMerchant,
                    "clawed back from merchant " + merchantId);
        }
        if (lossAbsorbed.isPositive()) {
            draft.debit(ChartOfAccounts.in(ChartOfAccounts.EXPENSE_CHARGEBACK_LOSSES, currency),
                    lossAbsorbed, "merchant balance insufficient to cover the dispute");
        }
        if (recoveredFee.isPositive()) {
            draft.credit(ChartOfAccounts.in(ChartOfAccounts.REVENUE_CHARGEBACK_FEES, currency),
                    recoveredFee, "chargeback handling fee");
        }
        return ledger.post(draft);
    }

    /**
     * Books a successful representment: the dispute was defended and the money
     * comes back.
     *
     * <p>The exact mirror of {@link #recordChargeback}, which is the point. It is
     * not enough to credit the merchant with the disputed amount: if part of the
     * claw-back was absorbed as a loss, that expense has to be reversed too, and the
     * merchant restored by what was actually taken from them rather than by the
     * headline figure. Getting this wrong leaves a phantom expense on the books and
     * the merchant better off than before the dispute.
     */
    public JournalEntry recordRepresentmentWon(String caseId, String paymentId, String merchantId,
                                               Money disputed, Money recoveredFromMerchant,
                                               Money recoveredFee, Money lossAbsorbed) {
        String currency = disputed.currency();
        ensureAccounts(currency, merchantId);

        JournalEntryDraft draft = JournalEntryDraft.of("Representment won on " + caseId)
                .reference(paymentId)
                .debit(ChartOfAccounts.in(ChartOfAccounts.SCHEME_RECEIVABLE, currency), disputed,
                        "funds returned by the issuer");
        if (recoveredFromMerchant.isPositive()) {
            draft.credit(ChartOfAccounts.merchantPayable(merchantId, currency), recoveredFromMerchant,
                    "restored to merchant " + merchantId);
        }
        if (lossAbsorbed.isPositive()) {
            draft.credit(ChartOfAccounts.in(ChartOfAccounts.EXPENSE_CHARGEBACK_LOSSES, currency),
                    lossAbsorbed, "absorbed loss reversed — the dispute was defended");
        }
        if (recoveredFee.isPositive()) {
            draft.debit(ChartOfAccounts.in(ChartOfAccounts.REVENUE_CHARGEBACK_FEES, currency),
                    recoveredFee, "handling fee refunded to the merchant");
        }
        return ledger.post(draft);
    }

    /**
     * Books the arbitration fee the scheme levies on whoever lost the case.
     *
     * <p>It is not our revenue either way — the scheme keeps it, deducting it from
     * our settlement. When the merchant lost we pass it on to them; when we lost it
     * is our expense. Booking it as revenue in the first case would overstate income
     * by money we never keep.
     */
    public JournalEntry recordArbitrationFee(String caseId, String paymentId, String merchantId,
                                             Money fee, boolean borneByMerchant) {
        String currency = fee.currency();
        ensureAccounts(currency, merchantId);

        JournalEntryDraft draft = JournalEntryDraft.of("Arbitration fee on " + caseId)
                .reference(paymentId)
                .credit(ChartOfAccounts.in(ChartOfAccounts.SCHEME_RECEIVABLE, currency), fee,
                        "deducted by the scheme");
        if (borneByMerchant) {
            draft.debit(ChartOfAccounts.merchantPayable(merchantId, currency), fee,
                    "arbitration fee passed on to merchant " + merchantId);
        } else {
            draft.debit(ChartOfAccounts.in(ChartOfAccounts.EXPENSE_SCHEME_FEES, currency), fee,
                    "arbitration lost — fee borne by us");
        }
        return ledger.post(draft);
    }

    /**
     * Books a settlement movement: the receivable becomes cash.
     *
     * <p>This is the entry that closes the loop opened at capture, and the reason
     * captures are held in a receivable rather than booked straight to the bank.
     *
     * <p>A settlement can run the other way. When a batch's refunds and disputes
     * exceed its sales, the net is negative and we pay the acquirer rather than
     * receiving from them — on a quiet day with a large refund this is entirely
     * ordinary. The direction is therefore derived from the sign rather than assumed,
     * because a posting amount is always positive and the sign lives in the side it
     * is posted to.
     */
    public JournalEntry recordSettlementReceipt(String batchId, Money netReceived) {
        String currency = netReceived.currency();
        if (netReceived.isZero()) {
            throw new IllegalArgumentException(
                    "Settlement for batch " + batchId + " nets to zero; there is nothing to book");
        }

        String bank = ChartOfAccounts.in(ChartOfAccounts.BANK_OPERATING, currency);
        String receivable = ChartOfAccounts.in(ChartOfAccounts.SCHEME_RECEIVABLE, currency);
        Money amount = netReceived.abs();

        if (netReceived.isPositive()) {
            return ledger.post(JournalEntryDraft.of("Settlement received for batch " + batchId)
                    .reference(batchId)
                    .debit(bank, amount, "funds received from acquirer")
                    .credit(receivable, amount, "receivable cleared"));
        }
        return ledger.post(JournalEntryDraft.of("Settlement paid away for batch " + batchId)
                .reference(batchId)
                .debit(receivable, amount, "negative settlement — refunds and disputes exceeded sales")
                .credit(bank, amount, "paid to acquirer"));
    }

    /** Books a payout: the merchant liability is discharged with cash. */
    public JournalEntry recordMerchantPayout(String batchId, String merchantId, Money amount) {
        String currency = amount.currency();
        ensureAccounts(currency, merchantId);

        return ledger.post(JournalEntryDraft.of("Payout to " + merchantId + " for batch " + batchId)
                .reference(batchId)
                .debit(ChartOfAccounts.merchantPayable(merchantId, currency), amount,
                        "liability discharged")
                .credit(ChartOfAccounts.in(ChartOfAccounts.BANK_OPERATING, currency), amount,
                        "paid out to merchant " + merchantId));
    }

    /**
     * Unwinds an entry by posting its mirror image. Used as saga compensation
     * when a later step fails after the books were already updated; the original
     * entry stays in the journal, as it must.
     */
    public JournalEntry ledgerReversal(JournalEntry entry, String reason) {
        return ledger.reverse(entry, reason);
    }

    public Money merchantBalance(String merchantId, String currency) {
        ensureAccounts(currency, merchantId);
        return ledger.balanceOf(ChartOfAccounts.merchantPayable(merchantId, currency));
    }
}
