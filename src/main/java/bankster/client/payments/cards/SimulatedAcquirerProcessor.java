package bankster.client.payments.cards;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import bankster.client.payments.Money;

/**
 * A processor backed by {@link IssuerSimulator} rather than a real scheme
 * connection.
 *
 * <p>It exists so that the whole lifecycle — authorize, partially capture,
 * refund, void, fail over — can be exercised end to end and asserted on in
 * tests. Two behaviours are controllable from the outside because they are the
 * ones worth demonstrating: {@link #setHealthy(boolean)} takes the processor
 * down so routing has to fail over, and {@link #failNextAuthorizations(int)}
 * injects transient faults without introducing randomness, so the tests stay
 * deterministic.
 *
 * <p>The state kept per authorization mirrors what a processor really tracks:
 * how much was authorized, how much has been captured so far, and how much
 * refunded. Those three numbers are what make over-capture and over-refund
 * detectable, and this is the layer that has to detect them — the issuer will
 * not.
 */
public class SimulatedAcquirerProcessor implements AcquirerProcessor {

    private final String id;
    private final String displayName;
    private final Set<CardScheme> supportedSchemes;
    private final Set<String> supportedCurrencies;
    private final IssuerSimulator issuer;
    private final FeeSchedule feeSchedule;
    private final Duration settlementDelay;
    private final double approvalRate;

    private volatile boolean healthy = true;
    private volatile int authorizationsToFail;

    private final Map<String, AuthorizationState> authorizations = new ConcurrentHashMap<>();

    public SimulatedAcquirerProcessor(String id, String displayName,
                                      Set<CardScheme> supportedSchemes, Set<String> supportedCurrencies,
                                      IssuerSimulator issuer, FeeSchedule feeSchedule,
                                      Duration settlementDelay, double approvalRate) {
        this.id = id;
        this.displayName = displayName;
        this.supportedSchemes = Set.copyOf(supportedSchemes);
        this.supportedCurrencies = Set.copyOf(supportedCurrencies);
        this.issuer = issuer;
        this.feeSchedule = feeSchedule;
        this.settlementDelay = settlementDelay;
        this.approvalRate = approvalRate;
    }

    private static final class AuthorizationState {
        private final String holdReference;
        private final String par;
        private final Money authorized;
        private Money captured;
        private Money refunded;
        private boolean voided;

        AuthorizationState(String holdReference, String par, Money authorized) {
            this.holdReference = holdReference;
            this.par = par;
            this.authorized = authorized;
            this.captured = Money.zero(authorized.currency());
            this.refunded = Money.zero(authorized.currency());
        }
    }

    @Override
    public ProcessorResponse authorize(ProcessorRequest request) {
        if (!healthy) {
            return ProcessorResponse.unavailable(id, displayName + " is not reachable", request.amount());
        }
        if (authorizationsToFail > 0) {
            authorizationsToFail--;
            return ProcessorResponse.unavailable(id, "transient fault at " + displayName, request.amount());
        }
        if (!canHandle(request.card().scheme(), request.amount().currency())) {
            return ProcessorResponse.declined(id, DeclineCode.TRANSACTION_NOT_PERMITTED, request.amount());
        }

        IssuerSimulator.IssuerResponse issuerResponse =
                issuer.authorize(request.card(), request.amount(), request.authentication());
        if (!issuerResponse.isApproved()) {
            return ProcessorResponse.declined(id, issuerResponse.responseCode(), request.amount());
        }

        String reference = id + "-" + UUID.randomUUID().toString().substring(0, 12);
        authorizations.put(reference,
                new AuthorizationState(issuerResponse.holdReference(), request.card().par(), request.amount()));
        return ProcessorResponse.approved(id, reference, issuerResponse.authorizationCode(), request.amount());
    }

    @Override
    public ProcessorResponse capture(String processorReference, Money amount) {
        AuthorizationState state = authorizations.get(processorReference);
        if (state == null) {
            return ProcessorResponse.declined(id, DeclineCode.INVALID_TRANSACTION, amount);
        }
        if (!healthy) {
            return ProcessorResponse.unavailable(id, displayName + " is not reachable", amount);
        }
        synchronized (state) {
            if (state.voided) {
                return ProcessorResponse.declined(id, DeclineCode.INVALID_TRANSACTION, amount);
            }
            Money afterCapture = state.captured.plus(amount);
            // Capturing more than was authorized is not a rounding problem; the
            // issuer never agreed to it and the scheme would charge it back.
            if (afterCapture.isGreaterThan(state.authorized)) {
                return ProcessorResponse.declined(id, DeclineCode.EXCEEDS_LIMIT, amount);
            }
            boolean captured = issuer.capture(state.holdReference, amount);
            if (!captured && state.captured.isZero()) {
                // The hold has lapsed — the issuer released it before we claimed it.
                return ProcessorResponse.declined(id, DeclineCode.INVALID_TRANSACTION, amount);
            }
            state.captured = afterCapture;
        }
        return ProcessorResponse.approved(id, processorReference, null, amount);
    }

    @Override
    public ProcessorResponse refund(String processorReference, Money amount) {
        AuthorizationState state = authorizations.get(processorReference);
        if (state == null) {
            return ProcessorResponse.declined(id, DeclineCode.INVALID_TRANSACTION, amount);
        }
        if (!healthy) {
            return ProcessorResponse.unavailable(id, displayName + " is not reachable", amount);
        }
        synchronized (state) {
            Money afterRefund = state.refunded.plus(amount);
            // Only captured money can be returned. Refunding more is how a
            // merchant accidentally pays out money it never took.
            if (afterRefund.isGreaterThan(state.captured)) {
                return ProcessorResponse.declined(id, DeclineCode.EXCEEDS_LIMIT, amount);
            }
            issuer.credit(state.par, amount);
            state.refunded = afterRefund;
        }
        return ProcessorResponse.approved(id, processorReference, null, amount);
    }

    @Override
    public ProcessorResponse voidAuthorization(String processorReference) {
        AuthorizationState state = authorizations.get(processorReference);
        if (state == null) {
            return ProcessorResponse.declined(id, DeclineCode.INVALID_TRANSACTION, Money.zero("EUR"));
        }
        synchronized (state) {
            if (!state.captured.isZero()) {
                // Once money has been claimed the remedy is a refund, not a void.
                return ProcessorResponse.declined(id, DeclineCode.INVALID_TRANSACTION, state.captured);
            }
            issuer.releaseHold(state.holdReference);
            state.voided = true;
            return ProcessorResponse.approved(id, processorReference, null, state.authorized);
        }
    }

    /** What has been captured so far against an authorization. */
    public Money capturedAmount(String processorReference) {
        AuthorizationState state = authorizations.get(processorReference);
        return state == null ? Money.zero("EUR") : state.captured;
    }

    // --- Test and demo controls -------------------------------------------

    /** Takes the processor down, so routing has to fail over. */
    public void setHealthy(boolean healthy) {
        this.healthy = healthy;
    }

    /** Injects a fixed number of transient authorization failures. */
    public void failNextAuthorizations(int count) {
        this.authorizationsToFail = count;
    }

    // --- Capability declaration -------------------------------------------

    @Override
    public String id() {
        return id;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public Set<CardScheme> supportedSchemes() {
        return supportedSchemes;
    }

    @Override
    public Set<String> supportedCurrencies() {
        return supportedCurrencies;
    }

    @Override
    public boolean isHealthy() {
        return healthy;
    }

    @Override
    public Duration settlementDelay() {
        return settlementDelay;
    }

    @Override
    public FeeSchedule feeSchedule() {
        return feeSchedule;
    }

    @Override
    public double approvalRate() {
        return approvalRate;
    }
}
