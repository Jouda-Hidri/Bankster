package bankster.client.payments.rails;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import bankster.client.payments.Money;

/**
 * The correspondent banking network a cross-border payment travels through.
 *
 * <p>This is the part of international payments that surprises people. SWIFT does
 * not move money — it carries instructions. The money moves because banks hold
 * accounts with each other: a <b>nostro</b> is our account held at a foreign
 * bank, a <b>vostro</b> is their account held with us, and the same balance is
 * both depending on whose books you are reading. A payment from Estonia to Japan
 * is settled by debiting our nostro at a correspondent who has a relationship
 * with the beneficiary's bank, possibly through a further intermediary.
 *
 * <p>Three consequences follow, and they explain nearly every complaint about
 * international payments:
 *
 * <ul>
 *   <li><b>It is slow.</b> Each hop is a separate bookkeeping operation in a
 *       separate institution, in a separate time zone, with its own cut-off.</li>
 *   <li><b>Fees appear from nowhere.</b> Every institution in the chain may
 *       deduct its charge from the principal as it passes, so the beneficiary
 *       receives less than was sent and the sender was never told by whom.</li>
 *   <li><b>Liquidity is expensive.</b> Money has to be pre-funded in every
 *       nostro, sitting idle, in every currency the bank wants to pay in.</li>
 * </ul>
 */
@Component
public class CorrespondentNetwork {

    /**
     * @param nostroBalance  what we hold with them — the payment's source of funds
     * @param deductsFees    whether this institution takes its charge out of the
     *                       principal in transit rather than billing us
     */
    public static final class Correspondent {

        private final Bic bic;
        private final String name;
        private final String country;
        private final Set<String> currencies;
        private final Money fixedFee;
        private final Duration transitTime;
        private final boolean deductsFees;
        private Money nostroBalance;

        public Correspondent(Bic bic, String name, String country, Set<String> currencies,
                             Money nostroBalance, Money fixedFee, Duration transitTime,
                             boolean deductsFees) {
            this.bic = bic;
            this.name = name;
            this.country = country;
            this.currencies = Set.copyOf(currencies);
            this.nostroBalance = nostroBalance;
            this.fixedFee = fixedFee;
            this.transitTime = transitTime;
            this.deductsFees = deductsFees;
        }

        public Bic bic() {
            return bic;
        }

        public String name() {
            return name;
        }

        public String country() {
            return country;
        }

        public Set<String> currencies() {
            return currencies;
        }

        public Money nostroBalance() {
            return nostroBalance;
        }

        public Money fixedFee() {
            return fixedFee;
        }

        public Duration transitTime() {
            return transitTime;
        }

        public boolean deductsFees() {
            return deductsFees;
        }

        /** Debits our nostro. Fails when the account is not pre-funded enough. */
        synchronized boolean debitNostro(Money amount) {
            if (!amount.currency().equals(nostroBalance.currency())
                    || amount.isGreaterThan(nostroBalance)) {
                return false;
            }
            nostroBalance = nostroBalance.minus(amount);
            return true;
        }

        synchronized void fundNostro(Money amount) {
            nostroBalance = nostroBalance.plus(amount);
        }
    }

    private final Map<String, Correspondent> correspondents = new ConcurrentHashMap<>();

    /** Which correspondent to use to reach each country. */
    private final Map<String, List<String>> routesByCountry = new ConcurrentHashMap<>();

    public CorrespondentNetwork() {
        add(new Correspondent(new Bic("CHASUS33"), "JPMorgan Chase", "US",
                Set.of("USD"), Money.of("USD", "2500000.00"), Money.of("USD", "15.00"),
                Duration.ofHours(24), true));
        add(new Correspondent(new Bic("DEUTDEFF"), "Deutsche Bank", "DE",
                Set.of("EUR"), Money.of("EUR", "5000000.00"), Money.of("EUR", "8.00"),
                Duration.ofHours(6), false));
        add(new Correspondent(new Bic("MHCBJPJT"), "Mizuho Bank", "JP",
                Set.of("JPY"), Money.ofMinor("JPY", 300_000_000L), Money.ofMinor("JPY", 2000),
                Duration.ofHours(36), true));
        add(new Correspondent(new Bic("BARCGB22"), "Barclays", "GB",
                Set.of("GBP"), Money.of("GBP", "1200000.00"), Money.of("GBP", "10.00"),
                Duration.ofHours(12), true));

        // Reaching Japan in yen goes through New York first — the dollar is the
        // vehicle currency for a great deal of trade that involves neither the
        // United States nor the dollar at either end.
        routesByCountry.put("US", List.of("CHASUS33"));
        routesByCountry.put("GB", List.of("BARCGB22"));
        routesByCountry.put("JP", List.of("CHASUS33", "MHCBJPJT"));
        routesByCountry.put("DE", List.of("DEUTDEFF"));
    }

    private void add(Correspondent correspondent) {
        correspondents.put(correspondent.bic().institutionBic(), correspondent);
    }

    public Optional<Correspondent> find(String institutionBic) {
        return Optional.ofNullable(correspondents.get(institutionBic));
    }

    public List<Correspondent> all() {
        return correspondents.values().stream()
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .toList();
    }

    /**
     * The chain of correspondents needed to reach a beneficiary.
     *
     * <p>Empty when no route exists, which for a real bank means the payment
     * cannot be made at all — there is no central directory that guarantees a
     * path, only the relationships the bank has gone out and established.
     */
    public List<Correspondent> chainTo(String beneficiaryCountry, String currency) {
        List<String> route = routesByCountry.get(beneficiaryCountry.toUpperCase());
        if (route == null) {
            return List.of();
        }
        List<Correspondent> chain = new ArrayList<>();
        for (String bic : route) {
            Correspondent correspondent = correspondents.get(bic);
            if (correspondent != null) {
                chain.add(correspondent);
            }
        }
        // The final hop has to be able to pay in the currency the beneficiary
        // wants; the earlier hops are only carrying it.
        if (chain.isEmpty() || !chain.get(chain.size() - 1).currencies().contains(currency)) {
            return List.of();
        }
        return List.copyOf(chain);
    }

    /** Total transit time across a chain — the reason these payments take days. */
    public Duration transitTimeFor(List<Correspondent> chain) {
        return chain.stream()
                .map(Correspondent::transitTime)
                .reduce(Duration.ZERO, Duration::plus);
    }

    /**
     * Charges that will be taken out of the principal as it passes, in the
     * currency the beneficiary is paid in.
     */
    public Money deductedFeesFor(List<Correspondent> chain, String currency) {
        Money total = Money.zero(currency);
        for (Correspondent correspondent : chain) {
            if (correspondent.deductsFees() && correspondent.fixedFee().currency().equals(currency)) {
                total = total.plus(correspondent.fixedFee());
            }
        }
        return total;
    }

    public void register(Correspondent correspondent, String countryReached) {
        add(correspondent);
        routesByCountry.put(countryReached.toUpperCase(), List.of(correspondent.bic().institutionBic()));
    }
}
