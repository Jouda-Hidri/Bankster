package bankster.client.payments;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import bankster.client.payments.cards.AcquirerProcessor;
import bankster.client.payments.cards.CardScheme;
import bankster.client.payments.cards.FeeSchedule;
import bankster.client.payments.cards.IssuerSimulator;
import bankster.client.payments.cards.SimulatedAcquirerProcessor;
import bankster.client.payments.cards.ThreeDSecureService;
import bankster.client.payments.core.EventHandler;
import bankster.client.payments.core.Outbox;
import bankster.client.payments.rails.SepaRouter;

/**
 * Wires the payments modules together.
 *
 * <p>Two processors are configured rather than one, and the difference between
 * them is the point: they support different schemes, charge differently, settle on
 * different schedules and approve at different rates, which is what gives
 * {@code PaymentRouter} something real to decide between and makes failover
 * observable rather than theoretical.
 */
@Configuration
@EnableConfigurationProperties(PaymentsProperties.class)
public class PaymentsConfig {

    /**
     * A single clock for the whole application.
     *
     * <p>Injected everywhere rather than calling {@code Instant.now()} in place,
     * because almost everything in payments is time-dependent — authorization
     * expiry, settlement cut-offs, representment deadlines, velocity windows — and
     * none of it is testable if the time source is a static method. Tests supply a
     * fixed clock and can then assert that an authorization expires after exactly
     * seven days without waiting seven days.
     */
    @Bean
    public Clock paymentsClock() {
        return Clock.systemUTC();
    }

    @Bean
    public ThreeDSecureService threeDSecureService(PaymentsProperties properties) {
        return new ThreeDSecureService(properties.getAcquirerFraudRateBasisPoints());
    }

    /**
     * The primary acquirer: broad scheme coverage, competitive pricing, next-day
     * settlement.
     */
    @Bean
    public AcquirerProcessor northboundProcessor(IssuerSimulator issuer) {
        return new SimulatedAcquirerProcessor(
                "northbound",
                "Northbound Payments",
                Set.of(CardScheme.VISA, CardScheme.MASTERCARD, CardScheme.AMEX),
                Set.of("EUR", "GBP", "USD"),
                issuer,
                FeeSchedule.standardEeaDebit("EUR"),
                Duration.ofDays(1),
                0.94);
    }

    /**
     * The secondary acquirer: narrower coverage and slower settlement, but a
     * genuinely independent connection — which is the only reason to keep it.
     */
    @Bean
    public AcquirerProcessor meridianProcessor(IssuerSimulator issuer) {
        return new SimulatedAcquirerProcessor(
                "meridian",
                "Meridian Acquiring",
                Set.of(CardScheme.VISA, CardScheme.MASTERCARD),
                Set.of("EUR"),
                issuer,
                FeeSchedule.standardEeaCredit("EUR"),
                Duration.ofDays(2),
                0.91);
    }

    /**
     * Subscribes the event handlers to the outbox.
     *
     * <p>Done here rather than in the {@link Outbox} constructor to avoid a
     * circular dependency: handlers legitimately want to publish events of their
     * own, so they cannot be constructor arguments of the thing they publish to.
     */
    @Bean
    public OutboxSubscriptions outboxSubscriptions(Outbox outbox, List<EventHandler> handlers) {
        handlers.forEach(outbox::subscribe);
        return new OutboxSubscriptions(handlers.size());
    }

    /** Marker for the wiring above, so the subscription is visible in the context. */
    public record OutboxSubscriptions(int handlerCount) {
    }

    /** Applies the configured institution-level limits to the rail router. */
    @Bean
    public SepaRouterConfigurer sepaRouterConfigurer(SepaRouter router, PaymentsProperties properties) {
        router.setInstantTransactionLimit(properties.instantTransferLimitAsMoney());
        router.setHighValueThreshold(properties.highValueThresholdAsMoney());
        return new SepaRouterConfigurer(router.instantTransactionLimit().toString());
    }

    public record SepaRouterConfigurer(String instantLimit) {
    }
}
