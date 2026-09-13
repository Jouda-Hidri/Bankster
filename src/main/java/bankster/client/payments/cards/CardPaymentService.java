package bankster.client.payments.cards;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import bankster.client.payments.Money;
import bankster.client.payments.core.AuditTrail;
import bankster.client.payments.core.IdempotencyStore;
import bankster.client.payments.core.Outbox;
import bankster.client.payments.core.Saga;
import bankster.client.payments.ledger.JournalEntry;
import bankster.client.payments.orchestration.PaymentBookkeeper;
import bankster.client.payments.orchestration.PaymentRepository;
import bankster.client.payments.orchestration.PaymentRouter;
import bankster.client.payments.orchestration.PaymentRouter.RoutingRequest;
import bankster.client.payments.orchestration.RoutingDecision;
import bankster.client.payments.risk.FraudEngine;
import bankster.client.payments.risk.RiskAssessment;
import bankster.client.payments.risk.RiskContext;
import bankster.client.payments.risk.RiskDecision;

/**
 * Orchestrates a card payment across every component it touches.
 *
 * <p>This is the layer commonly called payment orchestration, and the reason it
 * is worth naming is that the steps are not independent. Risk has to run before
 * authentication, because the risk score decides whether an exemption may be
 * claimed or a challenge is warranted. Authentication has to run before routing,
 * because the authentication result travels in the authorization message.
 * Routing has to produce an ordered list rather than a single choice, because
 * the first processor may be unreachable. And nothing may be booked until the
 * issuer has answered, because an authorization is not money.
 *
 * <p>Three cross-cutting guarantees wrap all of it:
 *
 * <ul>
 *   <li><b>Idempotency</b> on every state-changing entry point, so a client
 *       retrying after a timeout cannot double-charge.</li>
 *   <li><b>Compensation</b> where an external effect has to be undone —
 *       {@link #capture} is run as a saga, because a capture that succeeds at the
 *       processor and then fails to book leaves real money in the wrong
 *       place.</li>
 *   <li><b>Evidence</b>: every decision appends to the audit trail and every
 *       outcome to the outbox, so the payment's history survives and the rest of
 *       the system learns about it without a distributed commit.</li>
 * </ul>
 */
@Service
public class CardPaymentService {

    private static final Logger log = LoggerFactory.getLogger(CardPaymentService.class);

    /** What the merchant is charged when a dispute is raised against them. */
    public static final Money CHARGEBACK_FEE = Money.of("EUR", "15.00");

    private final Clock clock;
    private final TokenVault tokenVault;
    private final BinTable binTable;
    private final FraudEngine fraudEngine;
    private final ThreeDSecureService threeDSecure;
    private final PaymentRouter router;
    private final PaymentRepository payments;
    private final PaymentBookkeeper bookkeeper;
    private final IssuerSimulator issuer;
    private final Outbox outbox;
    private final AuditTrail auditTrail;
    private final IdempotencyStore idempotency;

    public CardPaymentService(Clock clock, TokenVault tokenVault, BinTable binTable,
                              FraudEngine fraudEngine, ThreeDSecureService threeDSecure,
                              PaymentRouter router, PaymentRepository payments,
                              PaymentBookkeeper bookkeeper, IssuerSimulator issuer,
                              Outbox outbox, AuditTrail auditTrail, IdempotencyStore idempotency) {
        this.clock = clock;
        this.tokenVault = tokenVault;
        this.binTable = binTable;
        this.fraudEngine = fraudEngine;
        this.threeDSecure = threeDSecure;
        this.router = router;
        this.payments = payments;
        this.bookkeeper = bookkeeper;
        this.issuer = issuer;
        this.outbox = outbox;
        this.auditTrail = auditTrail;
        this.idempotency = idempotency;
    }

    /**
     * A request to take a payment.
     *
     * @param captureImmediately true for the single-message flow most online
     *                           retail uses; false to authorize now and capture
     *                           on dispatch, which is required where goods ship
     *                           later
     */
    public record AuthorizeCommand(
            String merchantId,
            String orderReference,
            CardDetails card,
            Money amount,
            String mcc,
            boolean cardholderPresent,
            boolean recurring,
            boolean merchantInitiated,
            boolean trustedBeneficiary,
            boolean corporateCard,
            String ipAddress,
            String ipCountry,
            String billingCountry,
            String idempotencyKey,
            boolean captureImmediately) {

        /** The common case: an online purchase, authorized and captured at once. */
        public static AuthorizeCommand ecommerce(String merchantId, String orderReference,
                                                 CardDetails card, Money amount, String mcc,
                                                 String idempotencyKey) {
            return new AuthorizeCommand(merchantId, orderReference, card, amount, mcc,
                    false, false, false, false, false,
                    "127.0.0.1", "EE", "EE", idempotencyKey, true);
        }

        /** A stable digest of the request, so a reused key with changed content is caught. */
        String fingerprint() {
            return merchantId + "|" + orderReference + "|" + amount.currency() + amount.minorUnits()
                    + "|" + card.pan().last4() + "|" + captureImmediately;
        }
    }

    /** The outcome of an operation, with enough context to explain itself. */
    public record PaymentResult(
            Payment payment,
            boolean approved,
            String message,
            Optional<RoutingDecision> routing,
            List<String> processorsAttempted,
            boolean replayed) {

        public PaymentResult {
            processorsAttempted = List.copyOf(processorsAttempted);
        }

        /** True when the shopper still has to complete a 3-D Secure challenge. */
        public boolean requiresAuthentication() {
            return payment.status() == PaymentStatus.REQUIRES_AUTHENTICATION;
        }
    }

    // --- Authorization ----------------------------------------------------

    public PaymentResult authorize(AuthorizeCommand command) {
        IdempotencyStore.Outcome<PaymentResult> outcome = idempotency.execute(
                command.idempotencyKey(), command.fingerprint(), () -> doAuthorize(command));
        PaymentResult result = outcome.value();
        if (!outcome.replayed()) {
            return result;
        }
        // Replay: the original response, verbatim, with the flag set so the
        // caller can tell.
        return new PaymentResult(result.payment(), result.approved(), result.message(),
                result.routing(), result.processorsAttempted(), true);
    }

    private PaymentResult doAuthorize(AuthorizeCommand command) {
        Instant now = clock.instant();
        CardToken card = tokenVault.tokenize(command.card(), command.merchantId());
        BinTable.BinInfo bin = binTable.lookup(card);

        Payment payment = new Payment(
                "pay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                command.merchantId(), command.orderReference(), card, command.amount(),
                command.mcc(), command.cardholderPresent(), command.recurring(), now);
        payments.save(payment);

        auditTrail.record(command.merchantId(), "payment.created", payment.paymentId(), Map.of(
                "amount", command.amount().toString(),
                "card", card.masked(),
                "scheme", card.scheme().name(),
                "issuer", bin.issuerName() + " (" + bin.issuerCountry() + ")"));

        // 1. Risk. Runs first because it informs the authentication decision.
        RiskContext riskContext = new RiskContext(
                payment.paymentId(), command.merchantId(), card.par(), card.bin(), command.amount(),
                bin.issuerCountry(), command.ipCountry(), command.billingCountry(),
                command.ipAddress(), command.mcc(), command.cardholderPresent(),
                command.recurring(), now);
        RiskAssessment risk = fraudEngine.evaluate(riskContext);
        payment.setRisk(risk);
        auditTrail.record("fraud-engine", "payment.risk_scored", payment.paymentId(), Map.of(
                "score", String.valueOf(risk.score()),
                "band", risk.band().name(),
                "decision", risk.decision().name(),
                "reasons", risk.explain()));

        if (risk.decision() == RiskDecision.DECLINE) {
            fraudEngine.record(riskContext, false);
            return declineLocally(payment, DeclineCode.DO_NOT_HONOUR,
                    "blocked by fraud rules: " + risk.explain(), now);
        }

        // 2. Authentication, or an exemption from it.
        ThreeDSecureResult authentication = threeDSecure.authenticate(
                new ThreeDSecureService.AuthenticationRequest(
                        payment.paymentId(), card, command.amount(), risk,
                        command.recurring(), command.merchantInitiated(),
                        command.trustedBeneficiary(), command.corporateCard(),
                        issuerParticipates(bin)));
        payment.setAuthentication(authentication);
        auditTrail.record("3ds", "payment.authenticated", payment.paymentId(), Map.of(
                "outcome", authentication.outcome().name(),
                "exemption", authentication.exemption().name(),
                "liabilityShift", String.valueOf(authentication.liabilityShift()),
                "reason", authentication.reason()));

        if (authentication.requiresChallenge()) {
            // Recorded even though nothing has been decided. A challenged attempt
            // still counts towards velocity, and an attack whose attempts all trip
            // the challenge rule would otherwise generate no evidence at all.
            fraudEngine.record(riskContext, FraudEngine.Outcome.CHALLENGED);
            payment.transitionTo(PaymentStatus.REQUIRES_AUTHENTICATION, authentication.reason(), now);
            outbox.append("payment.authentication_required", payment.paymentId(), Map.of(
                    "dsTransactionId", authentication.dsTransactionId(),
                    "reason", authentication.reason()));
            return new PaymentResult(payment, false,
                    "cardholder must complete a 3-D Secure challenge",
                    Optional.empty(), List.of(), false);
        }
        if (!authentication.canProceedToAuthorization()) {
            fraudEngine.record(riskContext, false);
            return declineLocally(payment, DeclineCode.DO_NOT_HONOUR,
                    "authentication failed: " + authentication.reason(), now);
        }

        payment.transitionTo(PaymentStatus.AUTHENTICATED, authentication.reason(), now);
        return sendForAuthorization(payment, riskContext, command.captureImmediately());
    }

    /**
     * Resumes a payment after the shopper has answered a 3-D Secure challenge.
     *
     * <p>The risk score and the routing context are recovered from the stored
     * payment rather than being passed back in by the caller, because anything
     * the browser hands back is attacker-controlled.
     */
    public PaymentResult completeAuthentication(String paymentId, boolean passed) {
        Payment payment = payments.require(paymentId);
        Instant now = clock.instant();

        if (payment.status() != PaymentStatus.REQUIRES_AUTHENTICATION) {
            return new PaymentResult(payment, false,
                    "payment is in " + payment.status() + ", not awaiting authentication",
                    Optional.empty(), List.of(), false);
        }

        ThreeDSecureResult result = threeDSecure.completeChallenge(
                payment.authentication().dsTransactionId(), passed);
        payment.setAuthentication(result);
        auditTrail.record("3ds", "payment.challenge_completed", paymentId, Map.of(
                "outcome", result.outcome().name(),
                "liabilityShift", String.valueOf(result.liabilityShift())));

        if (!result.canProceedToAuthorization()) {
            return declineLocally(payment, DeclineCode.DO_NOT_HONOUR,
                    "challenge not completed: " + result.reason(), now);
        }

        payment.transitionTo(PaymentStatus.AUTHENTICATED, result.reason(), now);
        BinTable.BinInfo bin = binTable.lookup(payment.card());
        RiskContext riskContext = new RiskContext(
                paymentId, payment.merchantId(), payment.card().par(), payment.card().bin(),
                payment.requestedAmount(), bin.issuerCountry(), null, null, null,
                payment.mcc(), payment.cardholderPresent(), payment.recurring(), now);
        return sendForAuthorization(payment, riskContext, false);
    }

    /**
     * Routes the authorization and works down the fallback chain.
     *
     * <p>The chain is only followed for technical failures. An issuer decline
     * ends the attempt: the answer would be the same from another acquirer, and
     * the schemes treat repeated re-attempts of a declined transaction as abuse.
     */
    private PaymentResult sendForAuthorization(Payment payment, RiskContext riskContext,
                                               boolean captureImmediately) {
        Instant now = clock.instant();
        RoutingDecision decision = router.route(RoutingRequest.of(
                payment.card().scheme(), payment.requestedAmount(), payment.merchantId()));
        payment.setRoutingExplanation(decision.explanation());
        auditTrail.record("router", "payment.routed", payment.paymentId(), Map.of(
                "strategy", decision.strategy().name(),
                "explanation", decision.explanation()));

        if (!decision.hasRoute()) {
            fraudEngine.record(riskContext, false);
            return declineLocally(payment, DeclineCode.ISSUER_UNAVAILABLE, decision.explanation(), now);
        }

        ProcessorRequest request = new ProcessorRequest(
                payment.paymentId(), payment.card(), payment.requestedAmount(),
                payment.authentication(), payment.merchantId(), payment.mcc(),
                payment.orderReference(), payment.cardholderPresent(), payment.recurring());

        List<String> attempted = new ArrayList<>();
        ProcessorResponse lastResponse = null;

        for (AcquirerProcessor processor : decision.attemptOrder()) {
            attempted.add(processor.id());
            lastResponse = processor.authorize(request);

            if (lastResponse.approved()) {
                payment.recordAuthorization(lastResponse, now, now.plus(IssuerSimulator.HOLD_LIFETIME));
                payment.transitionTo(PaymentStatus.AUTHORIZED,
                        "authorized by " + processor.id() + ", code " + lastResponse.authorizationCode(), now);
                fraudEngine.record(riskContext, true);

                auditTrail.record(processor.id(), "payment.authorized", payment.paymentId(), Map.of(
                        "authorizationCode", String.valueOf(lastResponse.authorizationCode()),
                        "processorReference", lastResponse.processorReference(),
                        "amount", lastResponse.amount().toString()));
                outbox.append("payment.authorized", payment.paymentId(), Map.of(
                        "merchantId", payment.merchantId(),
                        "amount", lastResponse.amount().toString(),
                        "processor", processor.id()));

                if (captureImmediately) {
                    return capture(payment.paymentId(), payment.authorizedAmount(),
                            "auto-capture-" + payment.paymentId());
                }
                return new PaymentResult(payment, true,
                        "authorized by " + processor.displayName(),
                        Optional.of(decision), attempted, false);
            }

            if (!lastResponse.retryable()) {
                break;
            }
            log.info("Payment {} failed over from {} ({}), trying the next processor",
                    payment.paymentId(), processor.id(), lastResponse.message());
            auditTrail.record(processor.id(), "payment.failover", payment.paymentId(),
                    Map.of("reason", lastResponse.message()));
        }

        fraudEngine.record(riskContext, false);
        DeclineCode code = lastResponse == null ? DeclineCode.ISSUER_UNAVAILABLE : lastResponse.responseCode();
        String message = lastResponse == null ? "no processor attempted" : lastResponse.message();
        payment.setDeclineCode(code);
        payment.transitionTo(PaymentStatus.AUTHORIZATION_DECLINED,
                code.code() + " " + code.description() + " — " + message, now);

        auditTrail.record("acquirer", "payment.declined", payment.paymentId(), Map.of(
                "responseCode", code.code(),
                "description", code.description(),
                "softDecline", String.valueOf(code.isSoftDecline())));
        outbox.append("payment.declined", payment.paymentId(), Map.of(
                "responseCode", code.code(),
                "softDecline", String.valueOf(code.isSoftDecline()),
                "retryWithAuthentication", String.valueOf(code.isRetryableWithAuthentication())));

        return new PaymentResult(payment, false,
                code.description() + " (" + code.code() + ")",
                Optional.of(decision), attempted, false);
    }

    // --- Capture ----------------------------------------------------------

    /**
     * Claims some or all of an authorized amount.
     *
     * <p>Run as a saga: the processor call and the ledger posting are separate
     * systems, and a capture that succeeds at the processor but fails to book
     * means money has moved with nothing recording it. The compensation for that
     * is a refund — a new forward transaction, not an erasure, which is exactly
     * what compensation means in a distributed transaction.
     */
    public PaymentResult capture(String paymentId, Money amount, String idempotencyKey) {
        String fingerprint = paymentId + "|capture|" + amount.currency() + amount.minorUnits();
        IdempotencyStore.Outcome<PaymentResult> outcome = idempotency.execute(
                idempotencyKey, fingerprint, () -> doCapture(paymentId, amount));
        PaymentResult result = outcome.value();
        return outcome.replayed()
                ? new PaymentResult(result.payment(), result.approved(), result.message(),
                result.routing(), result.processorsAttempted(), true)
                : result;
    }

    /** Captures the whole authorized amount. */
    public PaymentResult captureAll(String paymentId, String idempotencyKey) {
        Payment payment = payments.require(paymentId);
        return capture(paymentId, payment.uncapturedAmount(), idempotencyKey);
    }

    private PaymentResult doCapture(String paymentId, Money amount) {
        Payment payment = payments.require(paymentId);
        Instant now = clock.instant();

        if (payment.status() != PaymentStatus.AUTHORIZED
                && payment.status() != PaymentStatus.PARTIALLY_CAPTURED) {
            return new PaymentResult(payment, false,
                    "cannot capture a payment in " + payment.status(),
                    Optional.empty(), List.of(), false);
        }
        if (!amount.isPositive()) {
            return new PaymentResult(payment, false, "capture amount must be positive",
                    Optional.empty(), List.of(), false);
        }
        if (amount.isGreaterThan(payment.uncapturedAmount())) {
            return new PaymentResult(payment, false,
                    "capture of " + amount + " exceeds the uncaptured " + payment.uncapturedAmount(),
                    Optional.empty(), List.of(), false);
        }
        if (payment.isAuthorizationExpired(now)) {
            payment.transitionTo(PaymentStatus.EXPIRED, "authorization hold lapsed before capture", now);
            return new PaymentResult(payment, false, "the authorization has expired",
                    Optional.empty(), List.of(), false);
        }

        AcquirerProcessor processor = processorFor(payment);
        if (processor == null) {
            return new PaymentResult(payment, false,
                    "processor " + payment.processorId() + " is no longer registered",
                    Optional.empty(), List.of(), false);
        }

        FeeSchedule fees = binTable.feeScheduleFor(binTable.lookup(payment.card()), amount.currency());
        Capture capture = new Capture("cap_" + UUID.randomUUID().toString().substring(0, 12),
                paymentId, amount, now);

        List<ProcessorResponse> processorResult = new ArrayList<>();
        List<JournalEntry> booked = new ArrayList<>();

        Saga.Result saga = Saga.named("capture " + paymentId)
                .step("claim funds at processor",
                        () -> {
                            ProcessorResponse response = processor.capture(payment.processorReference(), amount);
                            if (!response.approved()) {
                                throw new IllegalStateException(response.message());
                            }
                            processorResult.add(response);
                        },
                        // Undoing a capture means sending the money back. There is
                        // no scheme message that un-captures.
                        () -> processor.refund(payment.processorReference(), amount))
                .step("book the capture",
                        () -> booked.add(bookkeeper.recordCapture(
                                paymentId, payment.merchantId(), amount, fees)),
                        () -> booked.forEach(entry -> bookkeeper.ledgerReversal(entry,
                                "capture saga compensated")))
                .stepWithoutCompensation("record and announce", () -> {
                    payment.recordCapture(capture);
                    payment.transitionTo(
                            payment.isFullyCaptured() ? PaymentStatus.CAPTURED : PaymentStatus.PARTIALLY_CAPTURED,
                            "captured " + amount, now);
                    auditTrail.record(payment.merchantId(), "payment.captured", paymentId, Map.of(
                            "captureId", capture.captureId(),
                            "amount", amount.toString(),
                            "capturedTotal", payment.capturedAmount().toString(),
                            "merchantDiscount", fees.merchantDiscount(amount).toString(),
                            "interchange", fees.interchange(amount).toString()));
                    outbox.append("payment.captured", paymentId, Map.of(
                            "merchantId", payment.merchantId(),
                            "captureId", capture.captureId(),
                            "amount", amount.toString()));
                })
                .execute();

        if (!saga.isSuccess()) {
            String message = "capture failed at '" + saga.failedStep() + "': " + saga.failureReason();
            if (saga.needsIntervention()) {
                message += " — COMPENSATION INCOMPLETE: " + String.join("; ", saga.compensationFailures());
                auditTrail.record("system", "payment.capture_stranded", paymentId,
                        Map.of("failures", String.join("; ", saga.compensationFailures())));
            }
            auditTrail.record("system", "payment.capture_failed", paymentId, Map.of(
                    "failedStep", String.valueOf(saga.failedStep()),
                    "reason", String.valueOf(saga.failureReason()),
                    "status", saga.status().name()));
            return new PaymentResult(payment, false, message, Optional.empty(), List.of(), false);
        }

        return new PaymentResult(payment, true, "captured " + amount,
                Optional.empty(), List.of(processor.id()), false);
    }

    // --- Void -------------------------------------------------------------

    /**
     * Releases an authorization without taking the money.
     *
     * <p>Always preferable to a refund where it is available: the cardholder
     * never sees a debit, so there is nothing to explain and no second
     * transaction to settle. It stops being available the moment anything has
     * been captured.
     */
    public PaymentResult voidAuthorization(String paymentId, String idempotencyKey) {
        IdempotencyStore.Outcome<PaymentResult> outcome = idempotency.execute(
                idempotencyKey, paymentId + "|void", () -> doVoid(paymentId));
        return outcome.value();
    }

    private PaymentResult doVoid(String paymentId) {
        Payment payment = payments.require(paymentId);
        Instant now = clock.instant();

        if (payment.status() != PaymentStatus.AUTHORIZED) {
            return new PaymentResult(payment, false,
                    "only a fully uncaptured authorization can be voided; this one is " + payment.status(),
                    Optional.empty(), List.of(), false);
        }

        AcquirerProcessor processor = processorFor(payment);
        if (processor == null) {
            return new PaymentResult(payment, false, "processor is no longer registered",
                    Optional.empty(), List.of(), false);
        }

        ProcessorResponse response = processor.voidAuthorization(payment.processorReference());
        if (!response.approved()) {
            return new PaymentResult(payment, false, "void refused: " + response.message(),
                    Optional.empty(), List.of(), false);
        }

        payment.transitionTo(PaymentStatus.VOIDED, "authorization released before capture", now);
        auditTrail.record(payment.merchantId(), "payment.voided", paymentId,
                Map.of("amount", payment.authorizedAmount().toString()));
        outbox.append("payment.voided", paymentId, Map.of(
                "merchantId", payment.merchantId(),
                "amount", payment.authorizedAmount().toString()));

        // Nothing is booked: no entry was ever made for the authorization, so
        // there is nothing to reverse.
        return new PaymentResult(payment, true, "authorization voided",
                Optional.empty(), List.of(processor.id()), false);
    }

    // --- Refund -----------------------------------------------------------

    public PaymentResult refund(String paymentId, Money amount, String reason, String idempotencyKey) {
        String fingerprint = paymentId + "|refund|" + amount.currency() + amount.minorUnits();
        IdempotencyStore.Outcome<PaymentResult> outcome = idempotency.execute(
                idempotencyKey, fingerprint, () -> doRefund(paymentId, amount, reason));
        PaymentResult result = outcome.value();
        return outcome.replayed()
                ? new PaymentResult(result.payment(), result.approved(), result.message(),
                result.routing(), result.processorsAttempted(), true)
                : result;
    }

    private PaymentResult doRefund(String paymentId, Money amount, String reason) {
        Payment payment = payments.require(paymentId);
        Instant now = clock.instant();

        if (!payment.status().isCaptured()) {
            return new PaymentResult(payment, false,
                    "nothing has been captured on this payment (" + payment.status() + ")",
                    Optional.empty(), List.of(), false);
        }
        if (amount.isGreaterThan(payment.refundableAmount())) {
            return new PaymentResult(payment, false,
                    "refund of " + amount + " exceeds the refundable " + payment.refundableAmount(),
                    Optional.empty(), List.of(), false);
        }

        AcquirerProcessor processor = processorFor(payment);
        if (processor == null) {
            return new PaymentResult(payment, false, "processor is no longer registered",
                    Optional.empty(), List.of(), false);
        }

        ProcessorResponse response = processor.refund(payment.processorReference(), amount);
        if (!response.approved()) {
            return new PaymentResult(payment, false, "refund refused: " + response.message(),
                    Optional.empty(), List.of(), false);
        }

        Refund refund = new Refund("ref_" + UUID.randomUUID().toString().substring(0, 12),
                paymentId, amount, reason, now);
        bookkeeper.recordRefund(paymentId, refund.refundId(), payment.merchantId(), amount);
        payment.recordRefund(refund);
        payment.transitionTo(payment.isFullyRefunded() ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED,
                "refunded " + amount + (reason == null ? "" : ": " + reason), now);

        auditTrail.record(payment.merchantId(), "payment.refunded", paymentId, Map.of(
                "refundId", refund.refundId(),
                "amount", amount.toString(),
                "reason", String.valueOf(reason),
                "refundedTotal", payment.refundedAmount().toString()));
        outbox.append("payment.refunded", paymentId, Map.of(
                "merchantId", payment.merchantId(),
                "refundId", refund.refundId(),
                "amount", amount.toString()));

        return new PaymentResult(payment, true, "refunded " + amount,
                Optional.empty(), List.of(processor.id()), false);
    }

    // --- Sweeps -----------------------------------------------------------

    /**
     * Marks authorizations whose hold has lapsed.
     *
     * <p>Worth running rather than discovering at capture time: an expired
     * authorization that is still shown as capturable leads a merchant to ship
     * goods it will not be paid for.
     */
    public int expireStaleAuthorizations() {
        Instant now = clock.instant();
        int expired = 0;
        for (Payment payment : payments.awaitingCapture()) {
            if (!payment.isAuthorizationExpired(now)) {
                continue;
            }
            payment.transitionTo(PaymentStatus.EXPIRED, "authorization hold lapsed unused", now);
            auditTrail.record("system", "payment.authorization_expired", payment.paymentId(),
                    Map.of("uncaptured", payment.uncapturedAmount().toString()));
            outbox.append("payment.authorization_expired", payment.paymentId(),
                    Map.of("uncaptured", payment.uncapturedAmount().toString()));
            expired++;
        }
        issuer.expireStaleHolds();
        return expired;
    }

    // --- Helpers ----------------------------------------------------------

    private PaymentResult declineLocally(Payment payment, DeclineCode code, String reason, Instant now) {
        payment.setDeclineCode(code);
        payment.transitionTo(PaymentStatus.AUTHORIZATION_DECLINED, reason, now);
        auditTrail.record("system", "payment.declined", payment.paymentId(), Map.of(
                "responseCode", code.code(),
                "reason", reason));
        outbox.append("payment.declined", payment.paymentId(), Map.of(
                "responseCode", code.code(),
                "reason", reason));
        return new PaymentResult(payment, false, reason, Optional.empty(), List.of(), false);
    }

    private AcquirerProcessor processorFor(Payment payment) {
        return router.processors().stream()
                .filter(processor -> processor.id().equals(payment.processorId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Whether the issuer takes part in 3-D Secure. Issuers outside the EEA are
     * less consistently enrolled, and an attempted authentication against a
     * non-participant still shifts liability.
     */
    private boolean issuerParticipates(BinTable.BinInfo bin) {
        return bin.eea();
    }
}
