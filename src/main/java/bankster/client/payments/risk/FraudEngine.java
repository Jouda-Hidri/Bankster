package bankster.client.payments.risk;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.springframework.stereotype.Component;

import bankster.client.payments.Money;
import bankster.client.payments.risk.RiskAssessment.RiskReason;

/**
 * Rule-based fraud scoring for card attempts.
 *
 * <p>Rules, not a model, and deliberately so for this codebase: every decision
 * has to be explainable to a disputing customer and to a regulator, the
 * thresholds are the things an analyst actually tunes, and a rule engine can be
 * changed the morning an attack starts rather than after the next training run.
 * Real deployments run both — rules for the hard constraints and explainability,
 * a model for the ranking — and this is the half that carries the policy.
 *
 * <p>Most of the signal is in <em>velocity</em> rather than in any single
 * transaction. One €9 purchase is unremarkable; forty of them across thirty
 * different cards from one IP address in ten minutes is card testing, where a
 * fraudster with a stolen card list probes which numbers are still live. That
 * attack is invisible to per-transaction checks and obvious to a windowed
 * counter, which is why the engine keeps short rolling histories keyed by card
 * reference and by IP.
 *
 * <p>The cross-merchant view works on the payment account reference rather than
 * the card number, so velocity survives tokenization without anyone storing a
 * PAN.
 */
@Component
public class FraudEngine {

    private static final Duration SHORT_WINDOW = Duration.ofMinutes(10);
    private static final Duration LONG_WINDOW = Duration.ofHours(24);
    private static final Duration HISTORY_RETENTION = Duration.ofDays(2);

    /** Categories with structurally elevated fraud rates. */
    private static final Map<String, String> HIGH_RISK_MCC = Map.of(
            "7995", "gambling",
            "6051", "quasi-cash and crypto",
            "5967", "inbound teleservices",
            "5122", "pharmaceuticals",
            "4816", "computer network services");

    /**
     * How an attempt ended.
     *
     * <p>{@link #CHALLENGED} has to be a third value rather than being folded into
     * a declined/approved boolean. A challenged attempt counts towards velocity —
     * it is an attempt, and an attack that trips the challenge rule would otherwise
     * leave no trace at all — but it is not a decline, so it must not feed the
     * decline-probing rule, and it is not an approval, so it must not feed the
     * amount-anomaly baseline.
     */
    public enum Outcome {
        APPROVED,
        DECLINED,
        CHALLENGED
    }

    /**
     * Mutable so that a challenged attempt can be updated in place when the
     * cardholder completes authentication, rather than being counted twice.
     */
    private static final class Attempt {

        private final String paymentId;
        private final String par;
        private final String ip;
        private final String merchantId;
        private final Money amount;
        private final Instant at;
        private volatile Outcome outcome;

        Attempt(String paymentId, String par, String ip, String merchantId, Money amount,
                Instant at, Outcome outcome) {
            this.paymentId = paymentId;
            this.par = par;
            this.ip = ip;
            this.merchantId = merchantId;
            this.amount = amount;
            this.at = at;
            this.outcome = outcome;
        }

        String par() {
            return par;
        }

        String ip() {
            return ip;
        }

        String merchantId() {
            return merchantId;
        }

        Money amount() {
            return amount;
        }

        Instant at() {
            return at;
        }

        boolean approved() {
            return outcome == Outcome.APPROVED;
        }

        boolean declined() {
            return outcome == Outcome.DECLINED;
        }
    }

    private final Clock clock;
    private final Deque<Attempt> history = new ConcurrentLinkedDeque<>();
    private final Map<String, Integer> attemptsSeenByPar = new ConcurrentHashMap<>();

    public FraudEngine(Clock clock) {
        this.clock = clock;
    }

    /**
     * Scores an attempt. Pure with respect to the caller — recording the
     * attempt is a separate call, so evaluating the same context twice does not
     * inflate its own velocity counters.
     */
    public RiskAssessment evaluate(RiskContext context) {
        prune();
        List<RiskReason> reasons = new ArrayList<>();
        int score = 0;

        score += cardNotPresent(context, reasons);
        score += velocityOnCard(context, reasons);
        score += cardTestingFromIp(context, reasons);
        score += declineProbing(context, reasons);
        score += amountAnomaly(context, reasons);
        score += geographyMismatch(context, reasons);
        score += merchantCategory(context, reasons);
        score += crossMerchantSpread(context, reasons);
        score += firstSighting(context, reasons);

        score = Math.min(100, score);
        RiskBand band = RiskBand.forScore(score);
        return new RiskAssessment(score, band, decisionFor(band), reasons);
    }

    private RiskDecision decisionFor(RiskBand band) {
        return switch (band) {
            case LOW -> RiskDecision.APPROVE;
            // Step up rather than refuse: a challenge converts most genuine
            // shoppers and moves chargeback liability to the issuer.
            case MEDIUM, HIGH -> RiskDecision.CHALLENGE;
            case CRITICAL -> RiskDecision.DECLINE;
        };
    }

    /**
     * Feeds an attempt back so later attempts can be scored against it.
     *
     * <p>Must be called for every attempt, including those that were challenged
     * rather than decided. An engine that only records decided attempts cannot see
     * a card-testing burst whose attempts all trip its own challenge rule — the
     * attack would suppress exactly the evidence it generates.
     */
    public void record(RiskContext context, Outcome outcome) {
        // Update-if-present rather than append, so that a payment which was
        // challenged and then decided is one attempt with a final outcome. Calling
        // this more than once for the same payment is therefore safe.
        if (updateOutcome(context.paymentId(), outcome)) {
            return;
        }
        history.add(new Attempt(context.paymentId(), context.cardPar(), context.ipAddress(),
                context.merchantId(), context.amount(), context.at(), outcome));
        attemptsSeenByPar.merge(context.cardPar(), 1, Integer::sum);
        prune();
    }

    /** Convenience for the common approved/declined case. */
    public void record(RiskContext context, boolean approved) {
        record(context, approved ? Outcome.APPROVED : Outcome.DECLINED);
    }

    /**
     * Updates the outcome of an attempt already recorded for this payment, used
     * when a challenged attempt is later resolved.
     *
     * <p>Updating rather than appending is what keeps velocity honest: a genuine
     * shopper who passes a challenge has made one attempt, not two, and counting it
     * twice would push ordinary customers towards the velocity thresholds.
     */
    public boolean updateOutcome(String paymentId, Outcome outcome) {
        for (Attempt attempt : history) {
            if (paymentId.equals(attempt.paymentId)) {
                attempt.outcome = outcome;
                return true;
            }
        }
        return false;
    }

    // --- Rules -----------------------------------------------------------

    private int cardNotPresent(RiskContext context, List<RiskReason> reasons) {
        if (context.cardholderPresent() || context.recurring()) {
            return 0;
        }
        reasons.add(new RiskReason("card-not-present", 5,
                "e-commerce attempt with no cardholder present"));
        return 5;
    }

    private int velocityOnCard(RiskContext context, List<RiskReason> reasons) {
        long recent = countWhere(SHORT_WINDOW, attempt -> attempt.par().equals(context.cardPar()));
        long daily = countWhere(LONG_WINDOW, attempt -> attempt.par().equals(context.cardPar()));

        if (recent >= 5) {
            int points = (int) Math.min(35, 10 + (recent - 5) * 5);
            reasons.add(new RiskReason("card-velocity", points,
                    recent + " attempts on this card in the last 10 minutes"));
            return points;
        }
        if (daily >= 20) {
            reasons.add(new RiskReason("card-velocity-daily", 15,
                    daily + " attempts on this card in 24 hours"));
            return 15;
        }
        return 0;
    }

    private int cardTestingFromIp(RiskContext context, List<RiskReason> reasons) {
        if (context.ipAddress() == null) {
            return 0;
        }
        Set<String> cardsFromIp = new HashSet<>();
        long smallAmounts = 0;
        Instant cutoff = clock.instant().minus(SHORT_WINDOW);
        for (Attempt attempt : history) {
            if (attempt.at().isBefore(cutoff) || !context.ipAddress().equals(attempt.ip())) {
                continue;
            }
            cardsFromIp.add(attempt.par());
            if (attempt.amount().minorUnits() <= 500) {
                smallAmounts++;
            }
        }
        cardsFromIp.add(context.cardPar());

        // Many different cards from one address is the signature of an
        // enumeration attack; low values make it near-certain, because the
        // point is to find live numbers cheaply, not to buy anything.
        if (cardsFromIp.size() >= 5 && smallAmounts >= 3) {
            reasons.add(new RiskReason("card-testing", 45,
                    cardsFromIp.size() + " distinct cards and " + smallAmounts
                            + " low-value attempts from " + context.ipAddress() + " in 10 minutes"));
            return 45;
        }
        if (cardsFromIp.size() >= 5) {
            reasons.add(new RiskReason("ip-card-spread", 25,
                    cardsFromIp.size() + " distinct cards from " + context.ipAddress() + " in 10 minutes"));
            return 25;
        }
        return 0;
    }

    private int declineProbing(RiskContext context, List<RiskReason> reasons) {
        long declines = countWhere(SHORT_WINDOW,
                attempt -> attempt.declined()
                        && (attempt.par().equals(context.cardPar())
                        || (context.ipAddress() != null && context.ipAddress().equals(attempt.ip()))));
        if (declines >= 3) {
            int points = (int) Math.min(30, 10 * declines);
            reasons.add(new RiskReason("repeated-declines", points,
                    declines + " declined attempts from the same card or address in 10 minutes"));
            return points;
        }
        return 0;
    }

    private int amountAnomaly(RiskContext context, List<RiskReason> reasons) {
        List<Attempt> forCard = history.stream()
                .filter(attempt -> attempt.par().equals(context.cardPar()) && attempt.approved())
                .filter(attempt -> attempt.amount().currency().equals(context.amount().currency()))
                .toList();
        if (forCard.size() < 3) {
            return 0;
        }
        long average = forCard.stream().mapToLong(attempt -> attempt.amount().minorUnits()).sum() / forCard.size();
        if (average > 0 && context.amount().minorUnits() > average * 10) {
            reasons.add(new RiskReason("amount-anomaly", 20,
                    context.amount() + " is more than ten times this card's usual "
                            + Money.ofMinor(context.amount().currency(), average)));
            return 20;
        }
        return 0;
    }

    private int geographyMismatch(RiskContext context, List<RiskReason> reasons) {
        int points = 0;
        if (differ(context.ipCountry(), context.issuerCountry())) {
            reasons.add(new RiskReason("geo-issuer-mismatch", 10,
                    "shopper appears to be in " + context.ipCountry()
                            + " but the card was issued in " + context.issuerCountry()));
            points += 10;
        }
        if (differ(context.ipCountry(), context.billingCountry())) {
            reasons.add(new RiskReason("geo-billing-mismatch", 15,
                    "shopper appears to be in " + context.ipCountry()
                            + " but billing address is in " + context.billingCountry()));
            points += 15;
        }
        return points;
    }

    private int merchantCategory(RiskContext context, List<RiskReason> reasons) {
        String category = HIGH_RISK_MCC.get(context.mcc());
        if (category == null) {
            return 0;
        }
        reasons.add(new RiskReason("high-risk-mcc", 10,
                "merchant category " + context.mcc() + " (" + category + ")"));
        return 10;
    }

    private int crossMerchantSpread(RiskContext context, List<RiskReason> reasons) {
        Set<String> merchants = new HashSet<>();
        Instant cutoff = clock.instant().minus(SHORT_WINDOW);
        for (Attempt attempt : history) {
            if (!attempt.at().isBefore(cutoff) && attempt.par().equals(context.cardPar())) {
                merchants.add(attempt.merchantId());
            }
        }
        merchants.add(context.merchantId());
        if (merchants.size() >= 4) {
            reasons.add(new RiskReason("cross-merchant-velocity", 20,
                    "same card used at " + merchants.size() + " merchants in 10 minutes"));
            return 20;
        }
        return 0;
    }

    private int firstSighting(RiskContext context, List<RiskReason> reasons) {
        if (attemptsSeenByPar.containsKey(context.cardPar())) {
            return 0;
        }
        reasons.add(new RiskReason("unknown-card", 5, "card has not been seen before"));
        return 5;
    }

    // --- Window bookkeeping ----------------------------------------------

    private long countWhere(Duration window, java.util.function.Predicate<Attempt> predicate) {
        Instant cutoff = clock.instant().minus(window);
        return history.stream()
                .filter(attempt -> !attempt.at().isBefore(cutoff))
                .filter(predicate)
                .count();
    }

    private void prune() {
        Instant cutoff = clock.instant().minus(HISTORY_RETENTION);
        while (!history.isEmpty() && history.peekFirst() != null && history.peekFirst().at().isBefore(cutoff)) {
            history.pollFirst();
        }
    }

    private static boolean differ(String a, String b) {
        return a != null && b != null && !a.equalsIgnoreCase(b);
    }

    /** Attempts currently inside the retention window — used by the console. */
    public int trackedAttempts() {
        prune();
        return history.size();
    }
}
