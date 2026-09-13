package bankster.client.payments.rails;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import bankster.client.payments.Money;

/**
 * An outbound credit transfer.
 *
 * <p>Two identifiers are kept, and conflating them causes real trouble. The
 * {@code transferId} is ours. The {@code endToEndId} is the payer's reference,
 * which every institution in the chain must pass through unchanged — it is how
 * the payer reconciles their own records, and how a query about the payment is
 * matched weeks later. Overwriting it, or generating a new one on a retry, breaks
 * reconciliation at the far end.
 *
 * <p>{@code chargeBearer} decides who pays the fees. SEPA mandates SLEV — shared,
 * each side pays its own bank's charges, and the beneficiary receives the full
 * amount. Cross-border SWIFT payments allow DEBT and CRED, and under CRED the
 * correspondent banks deduct their fees from the principal in transit, which is
 * why a beneficiary can receive less than was sent.
 */
public final class CreditTransfer {

    public enum ChargeBearer {
        /** Shared, following service level — the SEPA rule. Beneficiary gets the full amount. */
        SLEV,
        /** Shared: sender pays its bank's fees, beneficiary pays the rest. */
        SHAR,
        /** Sender pays all charges. */
        DEBT,
        /** Beneficiary bears all charges, deducted from the amount in transit. */
        CRED
    }

    private final String transferId;
    private final String endToEndId;
    private final PartyDetails debtor;
    private final PartyDetails creditor;
    private final Money amount;
    private final Money fee;
    private final String remittanceInformation;
    private final ChargeBearer chargeBearer;
    private final LocalDate requestedExecutionDate;
    private final Instant createdAt;

    private TransferStatus status = TransferStatus.RECEIVED;
    private RailDecision routing;
    private Instant settledAt;
    private String rejectionCode;
    private String rejectionReason;
    private final List<Event> history = new ArrayList<>();

    public CreditTransfer(String transferId, String endToEndId, PartyDetails debtor, PartyDetails creditor,
                          Money amount, Money fee, String remittanceInformation, ChargeBearer chargeBearer,
                          LocalDate requestedExecutionDate, Instant createdAt) {
        this.transferId = transferId;
        this.endToEndId = endToEndId;
        this.debtor = debtor;
        this.creditor = creditor;
        this.amount = amount;
        this.fee = fee;
        this.remittanceInformation = remittanceInformation;
        this.chargeBearer = chargeBearer;
        this.requestedExecutionDate = requestedExecutionDate;
        this.createdAt = createdAt;
        this.history.add(new Event(null, TransferStatus.RECEIVED, "instruction received", createdAt));
    }

    public record Event(TransferStatus from, TransferStatus to, String note, Instant at) {
    }

    void transitionTo(TransferStatus next, String note, Instant at) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Transfer " + transferId + " cannot go from " + status + " to " + next);
        }
        history.add(new Event(status, next, note, at));
        status = next;
        if (next == TransferStatus.SETTLED) {
            settledAt = at;
        }
    }

    void reject(String code, String reason, Instant at) {
        this.rejectionCode = code;
        this.rejectionReason = reason;
        transitionTo(TransferStatus.REJECTED, code + ": " + reason, at);
    }

    void setRouting(RailDecision routing) {
        this.routing = routing;
    }

    /** What the beneficiary actually receives, once charges are accounted for. */
    public Money amountCredited() {
        return chargeBearer == ChargeBearer.CRED ? amount.minus(fee) : amount;
    }

    /** What leaves the payer's account. */
    public Money amountDebited() {
        return chargeBearer == ChargeBearer.CRED ? amount : amount.plus(fee);
    }

    public String transferId() {
        return transferId;
    }

    public String endToEndId() {
        return endToEndId;
    }

    public PartyDetails debtor() {
        return debtor;
    }

    public PartyDetails creditor() {
        return creditor;
    }

    public Money amount() {
        return amount;
    }

    public Money fee() {
        return fee;
    }

    public String remittanceInformation() {
        return remittanceInformation;
    }

    public ChargeBearer chargeBearer() {
        return chargeBearer;
    }

    public LocalDate requestedExecutionDate() {
        return requestedExecutionDate;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public TransferStatus status() {
        return status;
    }

    public RailDecision routing() {
        return routing;
    }

    public PaymentRail rail() {
        return routing == null ? null : routing.rail();
    }

    public Instant settledAt() {
        return settledAt;
    }

    public String rejectionCode() {
        return rejectionCode;
    }

    public String rejectionReason() {
        return rejectionReason;
    }

    public List<Event> history() {
        return Collections.unmodifiableList(history);
    }
}
