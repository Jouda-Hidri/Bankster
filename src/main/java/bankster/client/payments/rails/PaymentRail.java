package bankster.client.payments.rails;

import java.time.Duration;
import java.util.Set;

/**
 * The rails a credit transfer can travel on.
 *
 * <p>They are not interchangeable, and the differences are what routing exists
 * to exploit:
 *
 * <ul>
 *   <li><b>SEPA Credit Transfer</b> is the default euro rail. Batched, cleared
 *       on business days, funds with the beneficiary by the next working day.
 *       Cheap, universally reachable, and closed at weekends.</li>
 *   <li><b>SEPA Instant</b> settles in seconds, any hour of any day, with the
 *       beneficiary's funds immediately available and the transfer irrevocable
 *       once accepted. The catch is reachability — the scheme is adhered to
 *       rather than mandated for every institution — and per-institution amount
 *       limits.</li>
 *   <li><b>TARGET2</b> is the Eurosystem's real-time gross settlement system:
 *       individual settlement in central bank money, same day, built for
 *       high-value interbank payments rather than retail ones. Expensive per
 *       transaction and worth it above a certain size, where settlement risk
 *       matters more than the fee.</li>
 *   <li><b>SWIFT</b> is not a payment system at all but a messaging network.
 *       The money moves through correspondent banks holding accounts with each
 *       other, which is why cross-border payments take days, cost more, and can
 *       have fees deducted in transit by institutions the sender never chose.</li>
 * </ul>
 */
public enum PaymentRail {

    SEPA_SCT("SEPA Credit Transfer", Duration.ofHours(24), false, Set.of("EUR")),

    SEPA_INST("SEPA Instant Credit Transfer", Duration.ofSeconds(10), true, Set.of("EUR")),

    TARGET2("TARGET2 RTGS", Duration.ofHours(2), false, Set.of("EUR")),

    SWIFT("SWIFT correspondent banking", Duration.ofDays(2), false,
            Set.of("EUR", "USD", "GBP", "CHF", "JPY", "SEK", "NOK", "DKK"));

    private final String displayName;
    private final Duration typicalSettlement;
    private final boolean alwaysOpen;
    private final Set<String> currencies;

    PaymentRail(String displayName, Duration typicalSettlement, boolean alwaysOpen, Set<String> currencies) {
        this.displayName = displayName;
        this.typicalSettlement = typicalSettlement;
        this.alwaysOpen = alwaysOpen;
        this.currencies = currencies;
    }

    public String displayName() {
        return displayName;
    }

    public Duration typicalSettlement() {
        return typicalSettlement;
    }

    /** Whether the rail runs at weekends and on bank holidays. */
    public boolean isAlwaysOpen() {
        return alwaysOpen;
    }

    public Set<String> supportedCurrencies() {
        return currencies;
    }

    public boolean supports(String currency) {
        return currencies.contains(currency);
    }

    public boolean isSepa() {
        return this == SEPA_SCT || this == SEPA_INST;
    }

    /**
     * Whether the transfer can still be recalled after acceptance. Instant
     * payments cannot, which is precisely why they are attractive to fraudsters
     * running authorised push payment scams.
     */
    public boolean isIrrevocable() {
        return this == SEPA_INST;
    }
}
