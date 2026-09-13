package bankster.client.payments.rails;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import bankster.client.payments.Money;
import bankster.client.payments.rails.RailDecision.Rejection;

/**
 * Smart routing for credit transfers: picks the best rail a transfer can
 * actually travel on, and falls back in a defined order when it cannot have the
 * one it asked for.
 *
 * <p>The motivating case is the one customers hit constantly. A payer asks for an
 * instant transfer, and instant is not possible — because the amount is over the
 * sending institution's limit, because the beneficiary's bank is not reachable on
 * the instant scheme, or because the instant rail is down. The wrong behaviours
 * are to reject the payment, which loses a transfer that could perfectly well
 * have gone by standard transfer, and to silently downgrade it, which leaves the
 * payer believing money has arrived when it has not. The right behaviour is to
 * downgrade deliberately, record why, and say so.
 *
 * <p>Fallback order, most preferred first:
 *
 * <ol>
 *   <li><b>SEPA Instant</b> when the beneficiary is reachable, the amount is
 *       within the configured limit, and the rail is up.</li>
 *   <li><b>TARGET2</b> when the amount is large enough that same-day settlement
 *       in central bank money is worth its fee — which is also the natural home
 *       for an urgent transfer too big for instant.</li>
 *   <li><b>SEPA Credit Transfer</b> as the dependable default, with the value
 *       date computed from the cut-off and the TARGET calendar.</li>
 *   <li><b>SWIFT</b> when the payment leaves SEPA or the currency is not euro,
 *       where there is no alternative.</li>
 * </ol>
 *
 * <p>On the amount limit: the EPC removed the scheme-level €100,000 ceiling on
 * SEPA Instant in October 2025 under the Instant Payments Regulation, but
 * individual institutions still apply their own per-transaction limits for
 * liquidity and fraud reasons. {@link #instantTransactionLimit} is that
 * institution-level limit, which is why it is configurable rather than a
 * constant.
 */
@Component
public class SepaRouter {

    private static final Logger log = LoggerFactory.getLogger(SepaRouter.class);

    /** Central European Time, which is what the SEPA cut-offs are expressed in. */
    private static final ZoneId SCHEME_ZONE = ZoneId.of("Europe/Brussels");

    /** Last moment a standard transfer makes the same day's clearing cycle. */
    private static final LocalTime SCT_CUT_OFF = LocalTime.of(15, 0);

    /** TARGET2 closes for customer payments at 17:00 CET. */
    private static final LocalTime TARGET2_CUT_OFF = LocalTime.of(17, 0);

    private final Clock clock;
    private final ReachabilityDirectory reachability;
    private final IbanBicDirectory bicDirectory;

    /** This institution's own per-transaction ceiling for instant payments. */
    private volatile Money instantTransactionLimit = Money.of("EUR", "100000.00");

    /** Above this, same-day RTGS is the sensible rail. */
    private volatile Money highValueThreshold = Money.of("EUR", "250000.00");

    /** Set false to simulate the instant scheme being unavailable. */
    private volatile boolean instantRailAvailable = true;

    public SepaRouter(Clock clock, ReachabilityDirectory reachability, IbanBicDirectory bicDirectory) {
        this.clock = clock;
        this.reachability = reachability;
        this.bicDirectory = bicDirectory;
    }

    /** What the payer asked for. */
    public enum Urgency {
        /** Arrive in seconds if at all possible. */
        INSTANT,
        /** Ordinary transfer; cost matters more than speed. */
        STANDARD,
        /** Must settle today, whatever it costs. */
        SAME_DAY
    }

    public record RailRequest(
            Iban debtorIban,
            Iban creditorIban,
            Optional<Bic> creditorBic,
            Money amount,
            Urgency urgency) {

        public static RailRequest of(Iban debtorIban, Iban creditorIban, Money amount, Urgency urgency) {
            return new RailRequest(debtorIban, creditorIban, Optional.empty(), amount, urgency);
        }
    }

    public RailDecision route(RailRequest request) {
        List<Rejection> rejections = new ArrayList<>();
        PaymentRail requested = requestedRailFor(request.urgency());

        // Leaving SEPA, or not in euro: correspondent banking is the only option,
        // so none of the SEPA reasoning applies.
        if (!isSepaEligible(request, rejections)) {
            return decide(PaymentRail.SWIFT, requested, rejections, request);
        }

        Optional<Bic> creditorBic = request.creditorBic()
                .or(() -> bicDirectory.resolve(request.creditorIban()));

        if (request.urgency() == Urgency.INSTANT) {
            Optional<String> blocker = instantBlocker(request, creditorBic);
            if (blocker.isEmpty()) {
                return decide(PaymentRail.SEPA_INST, requested, rejections, request);
            }
            rejections.add(new Rejection(PaymentRail.SEPA_INST, blocker.get()));
            log.info("Instant transfer of {} downgraded: {}", request.amount(), blocker.get());

            // An instant payment that was too large is still urgent. RTGS settles
            // it today and has no upper limit, which is exactly the gap it fills.
            if (!request.amount().isLessThan(instantTransactionLimit)
                    && canUseTarget2(creditorBic, rejections)) {
                return decide(PaymentRail.TARGET2, requested, rejections, request);
            }
        }

        if (request.urgency() == Urgency.SAME_DAY) {
            if (canUseTarget2(creditorBic, rejections)) {
                return decide(PaymentRail.TARGET2, requested, rejections, request);
            }
        }

        if (!request.amount().isLessThan(highValueThreshold)
                && request.urgency() != Urgency.STANDARD
                && canUseTarget2(creditorBic, rejections)) {
            return decide(PaymentRail.TARGET2, requested, rejections, request);
        }

        return decide(PaymentRail.SEPA_SCT, requested, rejections, request);
    }

    /**
     * Why instant is not possible, or empty when it is.
     *
     * <p>Ordered so the most useful explanation wins: a payer whose amount is
     * over the limit wants to hear about the limit, not about reachability.
     */
    private Optional<String> instantBlocker(RailRequest request, Optional<Bic> creditorBic) {
        if (!instantRailAvailable) {
            return Optional.of("the SEPA Instant rail is currently unavailable");
        }
        if (request.amount().isGreaterThan(instantTransactionLimit)) {
            return Optional.of("the amount " + request.amount()
                    + " is above this institution's instant transfer limit of " + instantTransactionLimit);
        }
        if (creditorBic.isEmpty()) {
            return Optional.of("the beneficiary's institution could not be identified from the IBAN, "
                    + "so instant reachability cannot be confirmed");
        }
        Bic bic = creditorBic.get();
        if (!reachability.isReachable(bic, PaymentRail.SEPA_INST)) {
            String name = reachability.institutionName(bic).orElse(bic.value());
            return Optional.of("the beneficiary's bank (" + name
                    + ") is not reachable on SEPA Instant");
        }
        return Optional.empty();
    }

    private boolean canUseTarget2(Optional<Bic> creditorBic, List<Rejection> rejections) {
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), SCHEME_ZONE);
        if (!Target2Calendar.isSettlementDay(now.toLocalDate())) {
            rejections.add(new Rejection(PaymentRail.TARGET2,
                    "TARGET2 is closed today (" + now.toLocalDate() + ")"));
            return false;
        }
        if (now.toLocalTime().isAfter(TARGET2_CUT_OFF)) {
            rejections.add(new Rejection(PaymentRail.TARGET2,
                    "past the 17:00 CET TARGET2 cut-off for customer payments"));
            return false;
        }
        if (creditorBic.isPresent() && !reachability.isReachable(creditorBic.get(), PaymentRail.TARGET2)) {
            rejections.add(new Rejection(PaymentRail.TARGET2,
                    "the beneficiary's institution is not a TARGET2 participant"));
            return false;
        }
        return true;
    }

    private boolean isSepaEligible(RailRequest request, List<Rejection> rejections) {
        if (!request.amount().currency().equals("EUR")) {
            rejections.add(new Rejection(PaymentRail.SEPA_SCT,
                    "SEPA carries euro only; this transfer is in " + request.amount().currency()));
            return false;
        }
        if (!request.creditorIban().isSepa()) {
            rejections.add(new Rejection(PaymentRail.SEPA_SCT,
                    "the beneficiary IBAN is outside the SEPA area"));
            return false;
        }
        return true;
    }

    private PaymentRail requestedRailFor(Urgency urgency) {
        return switch (urgency) {
            case INSTANT -> PaymentRail.SEPA_INST;
            case SAME_DAY -> PaymentRail.TARGET2;
            case STANDARD -> PaymentRail.SEPA_SCT;
        };
    }

    private RailDecision decide(PaymentRail rail, PaymentRail requested,
                                List<Rejection> rejections, RailRequest request) {
        Instant settlement = expectedSettlement(rail);
        Money fee = feeFor(rail, request.amount());
        boolean downgraded = rail != requested;

        String explanation = downgraded
                ? rail.displayName() + " chosen instead of " + requested.displayName() + ": "
                + rejections.stream().map(Rejection::reason).reduce((a, b) -> a + "; " + b).orElse("")
                : rail.displayName() + " chosen as requested";

        return new RailDecision(rail, requested, downgraded, rejections, settlement, fee, explanation);
    }

    /**
     * When the beneficiary will have the money.
     *
     * <p>The instant rail is a few seconds from now whatever the day. The batch
     * rails depend on the cut-off and the TARGET calendar, which is why a Friday
     * 16:00 transfer and a Tuesday 10:00 transfer of the same amount have very
     * different value dates.
     */
    public Instant expectedSettlement(PaymentRail rail) {
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), SCHEME_ZONE);

        return switch (rail) {
            case SEPA_INST -> clock.instant().plus(rail.typicalSettlement());
            case TARGET2 -> clock.instant().plus(rail.typicalSettlement());
            case SEPA_SCT -> Target2Calendar.nextSettlementDay(executionDate())
                    .atTime(LocalTime.of(9, 0)).atZone(SCHEME_ZONE).toInstant();
            case SWIFT -> Target2Calendar
                    .thisOrNextSettlementDay(now.toLocalDate().plusDays(2))
                    .atTime(LocalTime.of(12, 0)).atZone(SCHEME_ZONE).toInstant();
        };
    }

    /**
     * The day a batch-rail transfer instructed now will actually enter clearing.
     *
     * <p>Today if today is a settlement day and the cut-off has not passed;
     * otherwise the next settlement day. Missing the cut-off costs a whole cycle,
     * so an instruction at 15:01 on a Friday does not enter clearing until Monday —
     * and the value date is a day after that again.
     */
    public LocalDate executionDate() {
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), SCHEME_ZONE);
        boolean makesTodaysCycle = Target2Calendar.isSettlementDay(now.toLocalDate())
                && now.toLocalTime().isBefore(SCT_CUT_OFF);
        return makesTodaysCycle
                ? now.toLocalDate()
                : Target2Calendar.thisOrNextSettlementDay(now.toLocalDate().plusDays(1));
    }

    /**
     * What the rail costs. Instant carries a small premium over batch, RTGS a
     * large one, and correspondent banking the largest because several
     * institutions take a cut.
     */
    public Money feeFor(PaymentRail rail, Money amount) {
        return switch (rail) {
            case SEPA_SCT -> Money.of(amount.currency(), "0.20");
            case SEPA_INST -> Money.of(amount.currency(), "0.50");
            case TARGET2 -> Money.of(amount.currency(), "15.00");
            case SWIFT -> Money.of(amount.currency(), "25.00")
                    .plus(amount.percentageBasisPoints(10));
        };
    }

    // --- Configuration ----------------------------------------------------

    public Money instantTransactionLimit() {
        return instantTransactionLimit;
    }

    public void setInstantTransactionLimit(Money limit) {
        this.instantTransactionLimit = limit;
    }

    public Money highValueThreshold() {
        return highValueThreshold;
    }

    public void setHighValueThreshold(Money threshold) {
        this.highValueThreshold = threshold;
    }

    public boolean isInstantRailAvailable() {
        return instantRailAvailable;
    }

    /** Takes the instant rail down, so routing has to fall back. */
    public void setInstantRailAvailable(boolean available) {
        this.instantRailAvailable = available;
    }
}
