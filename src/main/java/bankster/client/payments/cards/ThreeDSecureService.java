package bankster.client.payments.cards;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import bankster.client.payments.Money;
import bankster.client.payments.cards.ThreeDSecureResult.Outcome;
import bankster.client.payments.risk.RiskAssessment;
import bankster.client.payments.risk.RiskDecision;

/**
 * 3-D Secure 2 authentication, including the PSD2 exemption logic that decides
 * whether it is needed at all.
 *
 * <p>3DS lets the issuer decide who the shopper is before the merchant asks for
 * money. Version 2 changed the economics: instead of redirecting everyone to a
 * password page, the merchant sends device and transaction context up front and
 * the issuer authenticates most shoppers silently. Roughly nine in ten
 * transactions come back frictionless, and only the remainder are challenged.
 *
 * <p>The decision this service makes has three inputs pulling in different
 * directions:
 *
 * <ul>
 *   <li><b>Regulation.</b> In the EEA, SCA is mandatory unless an exemption
 *       applies.</li>
 *   <li><b>Conversion.</b> Every challenge loses shoppers, so exemptions are
 *       worth claiming when they are available.</li>
 *   <li><b>Liability.</b> An exemption keeps the fraud risk with the acquirer;
 *       authentication moves it to the issuer. On a risky transaction that
 *       inverts the calculation — the challenge is worth its conversion cost.</li>
 * </ul>
 *
 * <p>So the order matters: risk is consulted first, and a high-risk transaction
 * is authenticated even where an exemption was technically available.
 */
public class ThreeDSecureService {

    private static final String PROTOCOL_VERSION = "2.2.0";

    /** Per-transaction ceiling for the low-value exemption. */
    private static final Money LOW_VALUE_CEILING = Money.of("EUR", "30.00");

    /** Cumulative spend since the last SCA before low-value lapses. */
    private static final Money LOW_VALUE_CUMULATIVE_CEILING = Money.of("EUR", "100.00");

    /** Consecutive low-value exemptions before SCA is required again. */
    private static final int LOW_VALUE_COUNT_CEILING = 5;

    /**
     * The provider's measured fraud rate in basis points, which sets how high
     * the transaction-risk-analysis exemption may go. The bands are from the
     * RTS: 13 bps buys a €100 ceiling, 6 bps a €250 ceiling, 1 bp a €500 one.
     */
    private final int acquirerFraudRateBasisPoints;

    /** Low-value exemption counters, keyed by payment account reference. */
    private final Map<String, LowValueCounter> lowValueCounters = new ConcurrentHashMap<>();

    /** Challenges handed out and not yet resolved. */
    private final Map<String, PendingChallenge> pendingChallenges = new ConcurrentHashMap<>();

    public ThreeDSecureService() {
        this(5);
    }

    public ThreeDSecureService(int acquirerFraudRateBasisPoints) {
        this.acquirerFraudRateBasisPoints = acquirerFraudRateBasisPoints;
    }

    private static final class LowValueCounter {
        private long cumulativeMinorUnits;
        private int count;
    }

    private record PendingChallenge(String cardPar, Money amount) {
    }

    /** What the merchant knows about the transaction when authentication starts. */
    public record AuthenticationRequest(
            String paymentId,
            CardToken card,
            Money amount,
            RiskAssessment risk,
            boolean recurring,
            boolean merchantInitiated,
            boolean trustedBeneficiary,
            boolean corporateCard,
            boolean issuerParticipates) {
    }

    /**
     * Decides whether to authenticate, exempt or challenge, and produces the
     * values the authorization will carry.
     */
    public ThreeDSecureResult authenticate(AuthenticationRequest request) {
        // A transaction the fraud engine wants refused is not worth
        // authenticating; the issuer would almost certainly reject it anyway.
        if (request.risk() != null && request.risk().decision() == RiskDecision.DECLINE) {
            return rejected("fraud engine scored " + request.risk().score()
                    + " (" + request.risk().band() + "): " + request.risk().explain());
        }

        Optional<ScaExemption> exemption = availableExemption(request);
        if (exemption.isPresent()) {
            claimLowValue(request, exemption.get());
            return new ThreeDSecureResult(Outcome.EXEMPTED, PROTOCOL_VERSION,
                    // ECI 07 — not authenticated. Liability stays with us, which
                    // is the price of the exemption.
                    "07", null, dsTransactionId(), false, exemption.get(),
                    "SCA exemption claimed: " + exemption.get());
        }

        if (!request.issuerParticipates()) {
            // The issuer is not in the programme. The attempt is recorded by the
            // directory server, and the schemes still shift liability for it.
            return new ThreeDSecureResult(Outcome.ATTEMPTED, PROTOCOL_VERSION,
                    "06", cryptogram(request), dsTransactionId(), true, ScaExemption.NONE,
                    "issuer not enrolled; authentication attempted");
        }

        if (needsChallenge(request)) {
            String dsTransactionId = dsTransactionId();
            pendingChallenges.put(dsTransactionId,
                    new PendingChallenge(request.card().par(), request.amount()));
            return new ThreeDSecureResult(Outcome.CHALLENGE_REQUIRED, PROTOCOL_VERSION,
                    null, null, dsTransactionId, false, ScaExemption.NONE,
                    challengeReason(request));
        }

        resetLowValueCounter(request.card().par());
        return new ThreeDSecureResult(Outcome.FRICTIONLESS, PROTOCOL_VERSION,
                "05", cryptogram(request), dsTransactionId(), true, ScaExemption.NONE,
                "issuer authenticated the cardholder without interaction");
    }

    /**
     * Resolves a challenge once the shopper has responded.
     *
     * <p>A passed challenge is a successful SCA, so it also clears the low-value
     * counters — that is the mechanism by which the cumulative limits reset.
     */
    public ThreeDSecureResult completeChallenge(String dsTransactionId, boolean passed) {
        PendingChallenge challenge = pendingChallenges.remove(dsTransactionId);
        if (challenge == null) {
            return new ThreeDSecureResult(Outcome.UNAVAILABLE, PROTOCOL_VERSION,
                    null, null, dsTransactionId, false, ScaExemption.NONE,
                    "no challenge is outstanding for this reference");
        }
        if (!passed) {
            return new ThreeDSecureResult(Outcome.CHALLENGE_FAILED, PROTOCOL_VERSION,
                    "07", null, dsTransactionId, false, ScaExemption.NONE,
                    "cardholder failed or abandoned the challenge");
        }
        resetLowValueCounter(challenge.cardPar());
        return new ThreeDSecureResult(Outcome.CHALLENGE_PASSED, PROTOCOL_VERSION,
                "05", "cavv-" + dsTransactionId.substring(0, 8), dsTransactionId, true,
                ScaExemption.NONE, "cardholder completed the challenge");
    }

    public boolean hasPendingChallenge(String dsTransactionId) {
        return pendingChallenges.containsKey(dsTransactionId);
    }

    // --- Exemption logic --------------------------------------------------

    private Optional<ScaExemption> availableExemption(AuthenticationRequest request) {
        RiskAssessment risk = request.risk();
        boolean riskWantsAuthentication = risk != null && risk.decision() == RiskDecision.CHALLENGE;

        if (request.corporateCard()) {
            return Optional.of(ScaExemption.CORPORATE);
        }
        if (request.merchantInitiated()) {
            // No cardholder is present to authenticate, so SCA is impossible;
            // the mandate was authenticated when it was set up.
            return Optional.of(ScaExemption.MERCHANT_INITIATED);
        }
        if (request.recurring()) {
            return Optional.of(ScaExemption.RECURRING);
        }

        // The remaining exemptions are discretionary, so risk gets a veto.
        if (riskWantsAuthentication) {
            return Optional.empty();
        }

        if (request.trustedBeneficiary()) {
            return Optional.of(ScaExemption.TRUSTED_BENEFICIARY);
        }
        if (qualifiesForLowValue(request)) {
            return Optional.of(ScaExemption.LOW_VALUE);
        }
        if (qualifiesForRiskAnalysis(request)) {
            return Optional.of(ScaExemption.TRANSACTION_RISK_ANALYSIS);
        }
        return Optional.empty();
    }

    private boolean qualifiesForLowValue(AuthenticationRequest request) {
        Money amount = request.amount();
        if (!amount.currency().equals(LOW_VALUE_CEILING.currency())
                || amount.isGreaterThan(LOW_VALUE_CEILING)) {
            return false;
        }
        LowValueCounter counter = lowValueCounters.get(request.card().par());
        if (counter == null) {
            return true;
        }
        long cumulative = counter.cumulativeMinorUnits + amount.minorUnits();
        return counter.count < LOW_VALUE_COUNT_CEILING
                && cumulative <= LOW_VALUE_CUMULATIVE_CEILING.minorUnits();
    }

    private boolean qualifiesForRiskAnalysis(AuthenticationRequest request) {
        Money ceiling = riskAnalysisCeiling();
        return request.amount().currency().equals(ceiling.currency())
                && !request.amount().isGreaterThan(ceiling);
    }

    /** The TRA ceiling this provider has earned with its fraud rate. */
    public Money riskAnalysisCeiling() {
        if (acquirerFraudRateBasisPoints <= 1) {
            return Money.of("EUR", "500.00");
        }
        if (acquirerFraudRateBasisPoints <= 6) {
            return Money.of("EUR", "250.00");
        }
        if (acquirerFraudRateBasisPoints <= 13) {
            return Money.of("EUR", "100.00");
        }
        // Above 13 bps the exemption is not available at all.
        return Money.zero("EUR");
    }

    private void claimLowValue(AuthenticationRequest request, ScaExemption exemption) {
        if (exemption != ScaExemption.LOW_VALUE) {
            return;
        }
        LowValueCounter counter = lowValueCounters.computeIfAbsent(
                request.card().par(), ignored -> new LowValueCounter());
        synchronized (counter) {
            counter.cumulativeMinorUnits += request.amount().minorUnits();
            counter.count++;
        }
    }

    private void resetLowValueCounter(String cardPar) {
        lowValueCounters.remove(cardPar);
    }

    private boolean needsChallenge(AuthenticationRequest request) {
        RiskAssessment risk = request.risk();
        if (risk != null && risk.requiresChallenge()) {
            return true;
        }
        // Issuers habitually challenge large amounts regardless of merchant risk.
        return request.amount().isGreaterThan(Money.of(request.amount().currency(), "500.00"));
    }

    private String challengeReason(AuthenticationRequest request) {
        RiskAssessment risk = request.risk();
        if (risk != null && risk.requiresChallenge()) {
            return "risk score " + risk.score() + " (" + risk.band() + "): " + risk.explain();
        }
        return "amount " + request.amount() + " above the issuer's frictionless ceiling";
    }

    private ThreeDSecureResult rejected(String reason) {
        return new ThreeDSecureResult(Outcome.REJECTED, PROTOCOL_VERSION,
                "07", null, dsTransactionId(), false, ScaExemption.NONE, reason);
    }

    private String cryptogram(AuthenticationRequest request) {
        return "cavv-" + Integer.toHexString((request.paymentId() + request.card().token()).hashCode());
    }

    private String dsTransactionId() {
        return UUID.randomUUID().toString();
    }
}
