package bankster.client.payments.cards;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import bankster.client.payments.Money;

/**
 * A dispute case, from the issuer's first claim to a final ruling.
 *
 * <p>Disputes are fought on a clock. The issuer has a window from the
 * transaction date to raise one — 120 days for most reason codes — and the
 * merchant then has a short window, 30 days under the current scheme rules, to
 * defend it. Missing the merchant deadline loses the case regardless of the
 * merits, which is why {@code representmentDeadline} is stored on the case
 * rather than computed when someone remembers to look.
 */
public final class Chargeback {

    private final String caseId;
    private final String paymentId;
    private final String merchantId;
    private final Money disputedAmount;
    private final Money fee;
    private final ChargebackReason reason;
    private final Instant receivedAt;
    private final Instant representmentDeadline;

    private ChargebackStatus status = ChargebackStatus.RECEIVED;
    private final List<String> evidence = new ArrayList<>();
    private final List<Event> history = new ArrayList<>();
    private String outcomeReason;

    /**
     * How the claw-back was actually funded. Recorded because a successful
     * representment has to restore exactly what was taken, not the headline
     * disputed amount.
     */
    private Money recoveredFromMerchant;
    private Money recoveredFee;
    private Money lossAbsorbed;

    public Chargeback(String caseId, String paymentId, String merchantId, Money disputedAmount,
                      Money fee, ChargebackReason reason, Instant receivedAt,
                      Instant representmentDeadline) {
        this.caseId = caseId;
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.disputedAmount = disputedAmount;
        this.fee = fee;
        this.reason = reason;
        this.receivedAt = receivedAt;
        this.representmentDeadline = representmentDeadline;
        this.recoveredFromMerchant = Money.zero(disputedAmount.currency());
        this.recoveredFee = Money.zero(disputedAmount.currency());
        this.lossAbsorbed = Money.zero(disputedAmount.currency());
        this.history.add(new Event(null, ChargebackStatus.RECEIVED,
                "dispute raised under " + reason.code() + " " + reason.description(), receivedAt));
    }

    public record Event(ChargebackStatus from, ChargebackStatus to, String note, Instant at) {
    }

    void transitionTo(ChargebackStatus next, String note, Instant at) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Chargeback " + caseId + " cannot go from " + status + " to " + next);
        }
        history.add(new Event(status, next, note, at));
        status = next;
    }

    void addEvidence(List<String> items) {
        evidence.addAll(items);
    }

    void setOutcomeReason(String outcomeReason) {
        this.outcomeReason = outcomeReason;
    }

    void recordClawBack(Money recoveredFromMerchant, Money recoveredFee, Money lossAbsorbed) {
        this.recoveredFromMerchant = recoveredFromMerchant;
        this.recoveredFee = recoveredFee;
        this.lossAbsorbed = lossAbsorbed;
    }

    public boolean isOverdue(Instant now) {
        return status == ChargebackStatus.RECEIVED && now.isAfter(representmentDeadline);
    }

    public String caseId() {
        return caseId;
    }

    public String paymentId() {
        return paymentId;
    }

    public String merchantId() {
        return merchantId;
    }

    public Money disputedAmount() {
        return disputedAmount;
    }

    public Money fee() {
        return fee;
    }

    public ChargebackReason reason() {
        return reason;
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public Instant representmentDeadline() {
        return representmentDeadline;
    }

    public ChargebackStatus status() {
        return status;
    }

    public List<String> evidence() {
        return Collections.unmodifiableList(evidence);
    }

    public List<Event> history() {
        return Collections.unmodifiableList(history);
    }

    public String outcomeReason() {
        return outcomeReason;
    }

    /** What was actually debited from the merchant's balance. */
    public Money recoveredFromMerchant() {
        return recoveredFromMerchant;
    }

    /** The portion of the handling fee we managed to collect. */
    public Money recoveredFee() {
        return recoveredFee;
    }

    /**
     * The part of the disputed amount the merchant could not cover, which we
     * absorbed. This is the acquirer's real credit risk on the merchant.
     */
    public Money lossAbsorbed() {
        return lossAbsorbed;
    }

    public boolean hasAbsorbedLoss() {
        return lossAbsorbed.isPositive();
    }
}
