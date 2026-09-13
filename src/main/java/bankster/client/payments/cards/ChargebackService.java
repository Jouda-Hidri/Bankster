package bankster.client.payments.cards;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import bankster.client.payments.Money;
import bankster.client.payments.core.AuditTrail;
import bankster.client.payments.core.Outbox;
import bankster.client.payments.orchestration.PaymentBookkeeper;
import bankster.client.payments.orchestration.PaymentRepository;

/**
 * Runs the dispute lifecycle.
 *
 * <p>A chargeback is the cardholder's right to reverse a payment through their
 * issuer rather than through the merchant, and it is the mechanism that makes
 * cards trustworthy to use. Its cost lands on the merchant in three separate
 * ways, which this service models because only the first is obvious: the sale is
 * reversed, a per-case fee is charged, and the case counts towards a ratio that
 * — if it crosses the scheme's threshold — puts the merchant into a monitoring
 * programme with escalating fines and, eventually, loss of card acceptance. That
 * third cost is why a merchant at 0.8% cares enormously about disputes that are
 * individually trivial.
 *
 * <p>The claw-back is immediate and unconditional: the issuer takes the money
 * first and the argument happens afterwards. If the merchant's balance cannot
 * cover it, the acquirer is still liable to the issuer and absorbs the
 * difference — which is the credit risk an acquirer is actually paid to take,
 * and the reason acquirers hold reserves against new merchants.
 */
@Service
public class ChargebackService {

    /** The issuer's window to raise a dispute, from the transaction date. */
    public static final Duration ISSUER_FILING_WINDOW = Duration.ofDays(120);

    /** The merchant's window to defend it. */
    public static final Duration REPRESENTMENT_WINDOW = Duration.ofDays(30);

    /** Levied on the losing side when the scheme has to rule. */
    public static final Money ARBITRATION_FEE = Money.of("EUR", "500.00");

    /** Visa's dispute-ratio threshold; above it a merchant enters monitoring. */
    public static final double MONITORING_THRESHOLD = 0.009;

    private final Clock clock;
    private final PaymentRepository payments;
    private final PaymentBookkeeper bookkeeper;
    private final AuditTrail auditTrail;
    private final Outbox outbox;

    private final Map<String, Chargeback> cases = new ConcurrentHashMap<>();

    public ChargebackService(Clock clock, PaymentRepository payments, PaymentBookkeeper bookkeeper,
                             AuditTrail auditTrail, Outbox outbox) {
        this.clock = clock;
        this.payments = payments;
        this.bookkeeper = bookkeeper;
        this.auditTrail = auditTrail;
        this.outbox = outbox;
    }

    /**
     * Records an incoming dispute and pulls the funds back from the merchant.
     *
     * <p>The money moves here, before anyone has assessed the merits, because
     * that is how the scheme rules work.
     */
    public Chargeback receive(String paymentId, ChargebackReason reason, Money disputedAmount) {
        Payment payment = payments.require(paymentId);
        Instant now = clock.instant();

        if (!payment.status().isCaptured()) {
            throw new IllegalStateException(
                    "Cannot charge back payment " + paymentId + " in state " + payment.status()
                            + " — nothing was captured");
        }
        if (disputedAmount.isGreaterThan(payment.capturedAmount())) {
            throw new IllegalArgumentException(
                    "Disputed " + disputedAmount + " exceeds captured " + payment.capturedAmount());
        }
        if (now.isAfter(payment.createdAt().plus(ISSUER_FILING_WINDOW))) {
            throw new IllegalStateException("The issuer's 120-day filing window has closed");
        }

        Money fee = CardPaymentService.CHARGEBACK_FEE.currency().equals(disputedAmount.currency())
                ? CardPaymentService.CHARGEBACK_FEE
                : Money.zero(disputedAmount.currency());

        Chargeback chargeback = new Chargeback(
                "cb_" + UUID.randomUUID().toString().substring(0, 12),
                paymentId, payment.merchantId(), disputedAmount, fee, reason, now,
                now.plus(REPRESENTMENT_WINDOW));
        cases.put(chargeback.caseId(), chargeback);

        // Claw the funds back, applying what the merchant can cover to the disputed
        // amount before the fee: we would rather recover the money we owe the issuer
        // than the fee we would like to earn. Whatever is still short on the disputed
        // amount is our loss.
        Money currencyZero = Money.zero(disputedAmount.currency());
        Money merchantBalance = bookkeeper.merchantBalance(payment.merchantId(), disputedAmount.currency());
        Money available = merchantBalance.isPositive() ? merchantBalance : currencyZero;
        Money required = disputedAmount.plus(fee);

        Money recovered = available.isLessThan(required) ? available : required;
        Money recoveredDisputed = recovered.isLessThan(disputedAmount) ? recovered : disputedAmount;
        Money recoveredFee = recovered.minus(recoveredDisputed);
        Money lossAbsorbed = disputedAmount.minus(recoveredDisputed);

        bookkeeper.recordChargeback(chargeback.caseId(), paymentId, payment.merchantId(),
                disputedAmount, recovered, recoveredFee, lossAbsorbed);
        chargeback.recordClawBack(recovered, recoveredFee, lossAbsorbed);

        if (lossAbsorbed.isPositive()) {
            auditTrail.record("system", "chargeback.loss_absorbed", chargeback.caseId(), Map.of(
                    "merchantId", payment.merchantId(),
                    "shortfall", lossAbsorbed.toString(),
                    "merchantBalance", merchantBalance.toString(),
                    "recovered", recovered.toString()));
        }

        if (payment.status() != PaymentStatus.CHARGED_BACK) {
            payment.transitionTo(PaymentStatus.CHARGED_BACK,
                    "disputed under " + reason.code() + " (" + chargeback.caseId() + ")", now);
        }

        auditTrail.record("issuer", "chargeback.received", chargeback.caseId(), Map.of(
                "paymentId", paymentId,
                "merchantId", payment.merchantId(),
                "reasonCode", reason.code(),
                "amount", disputedAmount.toString(),
                "fee", fee.toString(),
                "representmentDeadline", chargeback.representmentDeadline().toString(),
                "evidenceExpected", reason.evidenceExpected()));
        outbox.append("chargeback.received", chargeback.caseId(), Map.of(
                "paymentId", paymentId,
                "merchantId", payment.merchantId(),
                "reasonCode", reason.code(),
                "amount", disputedAmount.toString(),
                "deadline", chargeback.representmentDeadline().toString()));

        return chargeback;
    }

    /** The merchant concedes. Cheaper than defending a case it would lose. */
    public Chargeback accept(String caseId) {
        Chargeback chargeback = require(caseId);
        Instant now = clock.instant();
        chargeback.transitionTo(ChargebackStatus.ACCEPTED, "merchant accepted liability", now);
        chargeback.transitionTo(ChargebackStatus.LOST, "accepted without representment", now);
        chargeback.setOutcomeReason("merchant accepted liability");

        auditTrail.record(chargeback.merchantId(), "chargeback.accepted", caseId, Map.of());
        outbox.append("chargeback.resolved", caseId, Map.of(
                "outcome", "LOST", "reason", "accepted by merchant"));
        return chargeback;
    }

    /**
     * Files a defence.
     *
     * <p>Where the disputed transaction was authenticated with 3-D Secure and the
     * reason code is a fraud code, the issuer had no right to raise it: liability
     * had already shifted. That case is decided immediately and in the merchant's
     * favour rather than waiting for the issuer, which is the concrete payoff of
     * having authenticated in the first place.
     */
    public Chargeback represent(String caseId, List<String> evidence) {
        Chargeback chargeback = require(caseId);
        Payment payment = payments.require(chargeback.paymentId());
        Instant now = clock.instant();

        if (now.isAfter(chargeback.representmentDeadline())) {
            return expire(chargeback, now);
        }

        chargeback.addEvidence(evidence);
        chargeback.transitionTo(ChargebackStatus.REPRESENTED,
                "defended with " + evidence.size() + " item(s) of evidence", now);
        auditTrail.record(chargeback.merchantId(), "chargeback.represented", caseId, Map.of(
                "evidence", String.join("; ", evidence),
                "reasonCode", chargeback.reason().code()));

        boolean liabilityShifted = payment.authentication() != null
                && payment.authentication().liabilityShift();
        if (chargeback.reason().isFraud() && liabilityShifted) {
            return resolve(chargeback, true,
                    "3-D Secure liability shift: the transaction was authenticated (ECI "
                            + payment.authentication().eci() + ", DS reference "
                            + payment.authentication().dsTransactionId()
                            + "), so a fraud dispute is not chargeable to the merchant");
        }

        outbox.append("chargeback.represented", caseId, Map.of(
                "paymentId", chargeback.paymentId(),
                "reasonCode", chargeback.reason().code(),
                "evidenceCount", String.valueOf(evidence.size())));
        return chargeback;
    }

    /** The issuer's answer to a representment. */
    public Chargeback resolveRepresentment(String caseId, boolean merchantWins, String note) {
        Chargeback chargeback = require(caseId);
        return resolve(chargeback, merchantWins, note);
    }

    /** The issuer is not satisfied and escalates. */
    public Chargeback escalateToPreArbitration(String caseId, String note) {
        Chargeback chargeback = require(caseId);
        chargeback.transitionTo(ChargebackStatus.PRE_ARBITRATION, note, clock.instant());
        auditTrail.record("issuer", "chargeback.pre_arbitration", caseId, Map.of("note", note));
        return chargeback;
    }

    public Chargeback escalateToArbitration(String caseId) {
        Chargeback chargeback = require(caseId);
        chargeback.transitionTo(ChargebackStatus.ARBITRATION,
                "referred to the scheme; the loser pays " + ARBITRATION_FEE, clock.instant());
        auditTrail.record("scheme", "chargeback.arbitration_filed", caseId,
                Map.of("fee", ARBITRATION_FEE.toString()));
        return chargeback;
    }

    /**
     * The scheme's ruling. Final — there is no appeal, and the arbitration fee
     * falls on whoever lost.
     */
    public Chargeback ruleArbitration(String caseId, boolean merchantWins, String ruling) {
        Chargeback chargeback = require(caseId);
        Chargeback resolved = resolve(chargeback, merchantWins, "arbitration ruling: " + ruling);
        bookkeeper.recordArbitrationFee(caseId, chargeback.paymentId(), chargeback.merchantId(),
                ARBITRATION_FEE, !merchantWins);
        auditTrail.record("scheme", "chargeback.arbitration_ruled", caseId, Map.of(
                "outcome", merchantWins ? "merchant" : "issuer",
                "fee", ARBITRATION_FEE.toString(),
                "feeBorneBy", merchantWins ? "acquirer" : "merchant",
                "ruling", ruling));
        return resolved;
    }

    /**
     * Closes cases the merchant never answered. Scheme rules give the issuer the
     * funds by default, so silence is a loss.
     */
    public int expireOverdueCases() {
        Instant now = clock.instant();
        int expired = 0;
        for (Chargeback chargeback : cases.values()) {
            if (chargeback.isOverdue(now)) {
                expire(chargeback, now);
                expired++;
            }
        }
        return expired;
    }

    private Chargeback expire(Chargeback chargeback, Instant now) {
        chargeback.transitionTo(ChargebackStatus.EXPIRED,
                "representment window closed with no defence filed", now);
        chargeback.transitionTo(ChargebackStatus.LOST, "undefended", now);
        chargeback.setOutcomeReason("the 30-day representment window closed with no defence");
        auditTrail.record("system", "chargeback.expired", chargeback.caseId(),
                Map.of("deadline", chargeback.representmentDeadline().toString()));
        outbox.append("chargeback.resolved", chargeback.caseId(), Map.of(
                "outcome", "LOST", "reason", "representment deadline missed"));
        return chargeback;
    }

    private Chargeback resolve(Chargeback chargeback, boolean merchantWins, String note) {
        Instant now = clock.instant();
        chargeback.transitionTo(merchantWins ? ChargebackStatus.WON : ChargebackStatus.LOST, note, now);
        chargeback.setOutcomeReason(note);

        if (merchantWins) {
            // Restore exactly what was taken, including reversing any loss we
            // absorbed — not the headline disputed amount, which would leave the
            // merchant better off than before the dispute.
            bookkeeper.recordRepresentmentWon(chargeback.caseId(), chargeback.paymentId(),
                    chargeback.merchantId(), chargeback.disputedAmount(),
                    chargeback.recoveredFromMerchant(), chargeback.recoveredFee(),
                    chargeback.lossAbsorbed());
        }

        auditTrail.record(merchantWins ? "scheme" : "issuer",
                merchantWins ? "chargeback.won" : "chargeback.lost",
                chargeback.caseId(), Map.of("note", note));
        outbox.append("chargeback.resolved", chargeback.caseId(), Map.of(
                "outcome", merchantWins ? "WON" : "LOST",
                "reason", note,
                "amount", chargeback.disputedAmount().toString()));
        return chargeback;
    }

    // --- Reporting --------------------------------------------------------

    public Optional<Chargeback> find(String caseId) {
        return Optional.ofNullable(cases.get(caseId));
    }

    public List<Chargeback> all() {
        return cases.values().stream()
                .sorted((a, b) -> b.receivedAt().compareTo(a.receivedAt()))
                .toList();
    }

    public List<Chargeback> forMerchant(String merchantId) {
        return all().stream().filter(c -> c.merchantId().equals(merchantId)).toList();
    }

    /**
     * The merchant's dispute ratio — cases divided by captured transactions.
     *
     * <p>Schemes measure it monthly and act on it. Visa places a merchant into
     * its dispute monitoring programme above 0.9%, with fines per dispute
     * thereafter, so this number is watched far more closely than the absolute
     * loss it represents.
     */
    public ChargebackRatio ratioFor(String merchantId) {
        long capturedCount = payments.forMerchant(merchantId).stream()
                .filter(payment -> payment.status().isCaptured()
                        || payment.status() == PaymentStatus.CHARGED_BACK)
                .count();
        long disputeCount = forMerchant(merchantId).size();
        double ratio = capturedCount == 0 ? 0.0 : (double) disputeCount / capturedCount;
        return new ChargebackRatio(merchantId, disputeCount, capturedCount, ratio,
                ratio > MONITORING_THRESHOLD);
    }

    /** @param inMonitoringProgramme above the scheme threshold, so fines apply */
    public record ChargebackRatio(
            String merchantId,
            long disputes,
            long capturedTransactions,
            double ratio,
            boolean inMonitoringProgramme) {

        public String formattedRatio() {
            return String.format(java.util.Locale.ROOT, "%.2f%%", ratio * 100);
        }
    }

    private Chargeback require(String caseId) {
        Chargeback chargeback = cases.get(caseId);
        if (chargeback == null) {
            throw new IllegalArgumentException("Unknown chargeback case: " + caseId);
        }
        return chargeback;
    }
}
