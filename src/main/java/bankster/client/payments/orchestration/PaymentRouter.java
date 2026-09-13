package bankster.client.payments.orchestration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

import bankster.client.payments.Money;
import bankster.client.payments.cards.AcquirerProcessor;
import bankster.client.payments.cards.CardScheme;
import bankster.client.payments.orchestration.RoutingDecision.Rejection;

/**
 * Chooses which processor a card transaction is sent to, and in what order to
 * try the rest if that one fails.
 *
 * <p>Routing is where a multi-acquirer setup earns its keep. The eligibility
 * filter is the part that cannot be skipped — a processor that does not support
 * the scheme or cannot settle the currency will simply reject the transaction,
 * and a processor that is down will time out, costing a second or more of
 * checkout latency for nothing. Only once the ineligible are removed does the
 * strategy get to express a preference.
 *
 * <p>Failover is the other half, and it has a sharp constraint: only
 * infrastructure failures may be retried elsewhere. If the issuer has declined,
 * the transaction has been decided, and sending it to a second acquirer will
 * produce the same decline while counting as another attempt — something the
 * schemes monitor and fine for. So the fallback chain exists for "we could not
 * reach anyone", never for "we were told no".
 */
@Component
public class PaymentRouter {

    private final List<AcquirerProcessor> processors = new CopyOnWriteArrayList<>();
    private volatile RoutingStrategy strategy = RoutingStrategy.HIGHEST_APPROVAL;

    /**
     * Every processor the application has been configured with. Injected as a
     * list so that adding an acquirer is a matter of declaring a bean, not of
     * editing the router.
     */
    public PaymentRouter(List<AcquirerProcessor> processors) {
        this.processors.addAll(processors);
    }

    /** What the router needs to know to pick a processor. */
    public record RoutingRequest(
            CardScheme scheme,
            Money amount,
            String merchantId,
            Set<String> excludedProcessorIds) {

        public static RoutingRequest of(CardScheme scheme, Money amount, String merchantId) {
            return new RoutingRequest(scheme, amount, merchantId, Set.of());
        }

        /**
         * Excludes a processor that has already been tried, so a retry does not
         * land back where it just failed.
         */
        public RoutingRequest excluding(String processorId) {
            Set<String> excluded = new LinkedHashSet<>(excludedProcessorIds);
            excluded.add(processorId);
            return new RoutingRequest(scheme, amount, merchantId, excluded);
        }
    }

    public void register(AcquirerProcessor processor) {
        processors.add(processor);
    }

    public List<AcquirerProcessor> processors() {
        return List.copyOf(processors);
    }

    public RoutingStrategy strategy() {
        return strategy;
    }

    public void setStrategy(RoutingStrategy strategy) {
        this.strategy = strategy;
    }

    public RoutingDecision route(RoutingRequest request) {
        List<Rejection> rejected = new ArrayList<>();
        List<AcquirerProcessor> eligible = new ArrayList<>();

        for (AcquirerProcessor processor : processors) {
            if (request.excludedProcessorIds().contains(processor.id())) {
                rejected.add(new Rejection(processor.id(), "already attempted for this payment"));
                continue;
            }
            if (!processor.supportedSchemes().contains(request.scheme())) {
                rejected.add(new Rejection(processor.id(),
                        "does not support " + request.scheme().displayName()));
                continue;
            }
            if (!processor.supportedCurrencies().contains(request.amount().currency())) {
                rejected.add(new Rejection(processor.id(),
                        "cannot settle " + request.amount().currency()));
                continue;
            }
            if (!processor.isHealthy()) {
                rejected.add(new Rejection(processor.id(), "currently unhealthy"));
                continue;
            }
            eligible.add(processor);
        }

        if (eligible.isEmpty()) {
            return new RoutingDecision(Optional.empty(), List.of(), strategy, rejected,
                    "no processor can handle " + request.scheme().displayName() + " in "
                            + request.amount().currency());
        }

        eligible.sort(comparatorFor(strategy, request.amount()));
        AcquirerProcessor chosen = eligible.get(0);
        List<AcquirerProcessor> fallbacks = eligible.subList(1, eligible.size());

        return new RoutingDecision(Optional.of(chosen), fallbacks, strategy, rejected,
                explain(chosen, request, fallbacks));
    }

    private Comparator<AcquirerProcessor> comparatorFor(RoutingStrategy strategy, Money amount) {
        Comparator<AcquirerProcessor> primary = switch (strategy) {
            case HIGHEST_APPROVAL -> Comparator.comparingDouble(AcquirerProcessor::approvalRate).reversed();
            case LOWEST_COST -> Comparator.comparingLong(
                    (AcquirerProcessor processor) -> processor.feeSchedule().margin(amount).minorUnits()).reversed();
            case FASTEST_SETTLEMENT -> Comparator.comparing(AcquirerProcessor::settlementDelay);
        };
        // Deterministic tie-break, so identical configurations route identically
        // and a test asserting on the choice does not flake.
        return primary.thenComparing(AcquirerProcessor::id);
    }

    private String explain(AcquirerProcessor chosen, RoutingRequest request, List<AcquirerProcessor> fallbacks) {
        String basis = switch (strategy) {
            case HIGHEST_APPROVAL -> "approval rate " + String.format(java.util.Locale.ROOT, "%.1f%%", chosen.approvalRate() * 100);
            case LOWEST_COST -> "margin " + chosen.feeSchedule().margin(request.amount()) + " on this amount";
            case FASTEST_SETTLEMENT -> "settles in " + chosen.settlementDelay().toHours() + "h";
        };
        String fallbackNote = fallbacks.isEmpty()
                ? "no fallback available"
                : "fallback: " + String.join(", ", fallbacks.stream().map(AcquirerProcessor::id).toList());
        return chosen.id() + " chosen on " + strategy + " (" + basis + "); " + fallbackNote;
    }
}
