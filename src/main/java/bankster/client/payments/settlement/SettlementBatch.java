package bankster.client.payments.settlement;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import bankster.client.payments.Money;

/**
 * A batch of transactions settled together.
 *
 * <p>Card payments do not settle one by one. Everything captured before a cut-off
 * is swept into a batch, netted, and funded as a single amount — which is why a
 * merchant sees one deposit rather than a deposit per sale, and why that deposit
 * never equals the day's takings.
 *
 * <p>The reason the fees are split into two groups rather than one is that two
 * different net figures have to come out of the same batch, and confusing them is
 * how a payments balance sheet goes wrong:
 *
 * <ul>
 *   <li><b>Acquirer funding</b> — what actually arrives in our bank account. The
 *       acquirer deducts interchange and scheme fees at source, so the money that
 *       lands is gross less those, less anything that came back.</li>
 *   <li><b>Merchant payout</b> — what we pay the merchant: gross less the merchant
 *       discount we charge them, less refunds, chargebacks and dispute fees.</li>
 * </ul>
 *
 * <p>The difference between the two is the margin, and it is recognised as revenue
 * rather than being anybody's cash. Every component is stored separately because
 * the commonest merchant question in payments is "why is this number not the number
 * I expected", and only the decomposition answers it.
 */
public final class SettlementBatch {

    public enum Status {
        /** Accumulating; the cut-off has not passed. */
        OPEN,

        /** Cut off and totalled; submitted to the processor. */
        CLOSED,

        /** Funds have arrived from the acquirer. */
        FUNDED,

        /** Paid on to the merchant. */
        PAID_OUT,

        /** Reconciliation found a discrepancy; payout is held. */
        HELD
    }

    private final String batchId;
    private final String merchantId;
    private final String processorId;
    private final String currency;
    private final Instant cutOffAt;
    private final LocalDate expectedFundingDate;

    private final List<String> captureIds = new ArrayList<>();
    private final List<String> refundIds = new ArrayList<>();
    private final List<String> chargebackIds = new ArrayList<>();

    private Money grossSales;
    private Money refunds;
    private Money chargebacks;

    /** What we charge the merchant. Our revenue. */
    private Money merchantDiscount;

    /** What the acquirer deducts before funding us. Our cost. */
    private Money interchangeAndSchemeFees;

    /** Per-dispute fees charged on to the merchant. Our revenue. */
    private Money chargebackFees;

    private Status status = Status.OPEN;
    private Instant fundedAt;
    private Instant paidOutAt;
    private String holdReason;

    public SettlementBatch(String batchId, String merchantId, String processorId, String currency,
                           Instant cutOffAt, LocalDate expectedFundingDate) {
        this.batchId = batchId;
        this.merchantId = merchantId;
        this.processorId = processorId;
        this.currency = currency;
        this.cutOffAt = cutOffAt;
        this.expectedFundingDate = expectedFundingDate;
        this.grossSales = Money.zero(currency);
        this.refunds = Money.zero(currency);
        this.chargebacks = Money.zero(currency);
        this.merchantDiscount = Money.zero(currency);
        this.interchangeAndSchemeFees = Money.zero(currency);
        this.chargebackFees = Money.zero(currency);
    }

    void addSale(String captureId, Money gross, Money discount, Money acquirerDeduction) {
        captureIds.add(captureId);
        grossSales = grossSales.plus(gross);
        merchantDiscount = merchantDiscount.plus(discount);
        interchangeAndSchemeFees = interchangeAndSchemeFees.plus(acquirerDeduction);
    }

    void addRefund(String refundId, Money amount) {
        refundIds.add(refundId);
        refunds = refunds.plus(amount);
    }

    void addChargeback(String caseId, Money amount, Money fee) {
        chargebackIds.add(caseId);
        chargebacks = chargebacks.plus(amount);
        chargebackFees = chargebackFees.plus(fee);
    }

    /**
     * What the acquirer actually transfers to us — and therefore exactly what
     * should appear on the bank statement, and exactly what clears the scheme
     * receivable the captures created.
     */
    public Money acquirerFunding() {
        return grossSales.minus(interchangeAndSchemeFees).minus(refunds).minus(chargebacks);
    }

    /** What the merchant is paid. Can be negative on a bad day. */
    public Money netPayout() {
        return grossSales.minus(merchantDiscount).minus(refunds)
                .minus(chargebacks).minus(chargebackFees);
    }

    /** Revenue earned on this batch: what we charged less what we were charged. */
    public Money margin() {
        return merchantDiscount.plus(chargebackFees).minus(interchangeAndSchemeFees);
    }

    /**
     * True when refunds, chargebacks and fees exceed sales.
     *
     * <p>It happens, and it has to be handled rather than asserted away: a
     * merchant with a quiet day and a large refund owes us money, which is
     * collected by debiting them or by holding it against the next batch.
     */
    public boolean isNegative() {
        return netPayout().isNegative();
    }

    void close() {
        status = Status.CLOSED;
    }

    void markFunded(Instant at) {
        status = Status.FUNDED;
        fundedAt = at;
    }

    void markPaidOut(Instant at) {
        status = Status.PAID_OUT;
        paidOutAt = at;
    }

    void hold(String reason) {
        status = Status.HELD;
        holdReason = reason;
    }

    void release() {
        if (status == Status.HELD) {
            status = fundedAt == null ? Status.CLOSED : Status.FUNDED;
            holdReason = null;
        }
    }

    public String batchId() {
        return batchId;
    }

    public String merchantId() {
        return merchantId;
    }

    public String processorId() {
        return processorId;
    }

    public String currency() {
        return currency;
    }

    public Instant cutOffAt() {
        return cutOffAt;
    }

    public LocalDate expectedFundingDate() {
        return expectedFundingDate;
    }

    public Money grossSales() {
        return grossSales;
    }

    public Money refunds() {
        return refunds;
    }

    public Money chargebacks() {
        return chargebacks;
    }

    public Money merchantDiscount() {
        return merchantDiscount;
    }

    public Money interchangeAndSchemeFees() {
        return interchangeAndSchemeFees;
    }

    public Money chargebackFees() {
        return chargebackFees;
    }

    public Status status() {
        return status;
    }

    public Instant fundedAt() {
        return fundedAt;
    }

    public Instant paidOutAt() {
        return paidOutAt;
    }

    public String holdReason() {
        return holdReason;
    }

    public List<String> captureIds() {
        return Collections.unmodifiableList(captureIds);
    }

    public List<String> refundIds() {
        return Collections.unmodifiableList(refundIds);
    }

    public List<String> chargebackIds() {
        return Collections.unmodifiableList(chargebackIds);
    }

    public int transactionCount() {
        return captureIds.size() + refundIds.size() + chargebackIds.size();
    }
}
