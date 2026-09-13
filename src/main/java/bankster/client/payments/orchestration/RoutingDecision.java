package bankster.client.payments.orchestration;

import java.util.List;
import java.util.Optional;

import bankster.client.payments.cards.AcquirerProcessor;

/**
 * Which processor was chosen, what the alternatives were, and why the rest were
 * not.
 *
 * <p>The rejected list is the valuable part. When a merchant asks why their
 * transaction went to the expensive acquirer, or why traffic moved at 3am, the
 * answer has to be recoverable after the fact — and a router that only records
 * its winner cannot give it.
 */
public record RoutingDecision(
        Optional<AcquirerProcessor> chosen,
        List<AcquirerProcessor> fallbacks,
        RoutingStrategy strategy,
        List<Rejection> rejected,
        String explanation) {

    public RoutingDecision {
        fallbacks = List.copyOf(fallbacks);
        rejected = List.copyOf(rejected);
    }

    public record Rejection(String processorId, String reason) {
    }

    public boolean hasRoute() {
        return chosen.isPresent();
    }

    /** The chosen processor followed by the fallbacks, in the order to try them. */
    public List<AcquirerProcessor> attemptOrder() {
        if (chosen.isEmpty()) {
            return List.of();
        }
        List<AcquirerProcessor> order = new java.util.ArrayList<>();
        order.add(chosen.get());
        order.addAll(fallbacks);
        return List.copyOf(order);
    }
}
