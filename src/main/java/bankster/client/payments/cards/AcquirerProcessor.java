package bankster.client.payments.cards;

import java.time.Duration;
import java.util.Set;

import bankster.client.payments.Money;

/**
 * A payment processor: the component that actually speaks to the card network.
 *
 * <p>The terms around this layer are used loosely, so it is worth being precise
 * about what sits where. A <b>gateway</b> collects card details and passes them
 * on — it is a transport and a tokenizer, and holds no licence. A
 * <b>processor</b> formats and sends the authorization message to the scheme
 * and returns the issuer's answer. An <b>acquirer</b> is the licensed bank that
 * holds the merchant agreement, carries the risk and settles the funds. One
 * company often does several of these, which is why the distinction blurs in
 * conversation and not in the contract.
 *
 * <p>Bankster integrates more than one processor on purpose. A single processor
 * is a single point of failure for all revenue, and processors differ in which
 * schemes and currencies they support, what they charge, how quickly they
 * settle, and — measurably — how often they get a given issuer to approve. The
 * capability and health information declared here is what
 * {@code PaymentRouter} uses to choose between them.
 */
public interface AcquirerProcessor {

    String id();

    String displayName();

    /** Schemes this processor can send to. */
    Set<CardScheme> supportedSchemes();

    /** Settlement currencies it can handle. */
    Set<String> supportedCurrencies();

    /**
     * Whether the processor is currently usable. Drives failover, so it has to
     * reflect live state rather than configuration.
     */
    boolean isHealthy();

    /** How long funds take to arrive after capture — a real differentiator. */
    Duration settlementDelay();

    FeeSchedule feeSchedule();

    /** Observed approval rate, used to break ties between eligible processors. */
    double approvalRate();

    ProcessorResponse authorize(ProcessorRequest request);

    ProcessorResponse capture(String processorReference, Money amount);

    ProcessorResponse refund(String processorReference, Money amount);

    /** Releases the hold without taking the money. */
    ProcessorResponse voidAuthorization(String processorReference);

    default boolean canHandle(CardScheme scheme, String currency) {
        return supportedSchemes().contains(scheme) && supportedCurrencies().contains(currency);
    }
}
