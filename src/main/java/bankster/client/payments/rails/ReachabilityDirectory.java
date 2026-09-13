package bankster.client.payments.rails;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Which institutions can be reached on which rail.
 *
 * <p>Reachability is the constraint people forget when they assume instant
 * payments are simply available. Adherence to the SEPA Instant scheme is per
 * institution, and although the Instant Payments Regulation has made offering it
 * compulsory for euro-area payment service providers, the long tail — smaller
 * institutions, non-euro SEPA countries phasing in, and banks outside the EEA
 * that accept euro transfers — is still not uniformly reachable. A payment
 * instructed as instant to an unreachable beneficiary is rejected, not quietly
 * downgraded, so the sending institution has to know before it instructs.
 *
 * <p>In production this is the EPC's routing and reachability directory,
 * refreshed on a schedule. The entries here cover the institutions the demo and
 * the tests use, keyed on the 8-character institution BIC.
 */
@Component
public class ReachabilityDirectory {

    private record Institution(String name, String country, Set<PaymentRail> rails) {
    }

    private final Map<String, Institution> institutions = new ConcurrentHashMap<>();

    public ReachabilityDirectory() {
        // Reachable on both the standard and the instant rail.
        add("LHVBEE22", "LHV Pank", "EE", Set.of(PaymentRail.SEPA_SCT, PaymentRail.SEPA_INST, PaymentRail.TARGET2));
        add("DEUTDEFF", "Deutsche Bank", "DE", Set.of(PaymentRail.SEPA_SCT, PaymentRail.SEPA_INST, PaymentRail.TARGET2));
        add("BNPAFRPP", "BNP Paribas", "FR", Set.of(PaymentRail.SEPA_SCT, PaymentRail.SEPA_INST, PaymentRail.TARGET2));
        add("INGBNL2A", "ING Bank", "NL", Set.of(PaymentRail.SEPA_SCT, PaymentRail.SEPA_INST));
        add("REVOLT21", "Revolut", "LT", Set.of(PaymentRail.SEPA_SCT, PaymentRail.SEPA_INST));

        // Standard rail only — the case that forces a fallback.
        add("NDEASESS", "Nordea Sweden", "SE", Set.of(PaymentRail.SEPA_SCT));
        add("MTLCMT21", "Small Maltese Bank", "MT", Set.of(PaymentRail.SEPA_SCT));
        add("CRESCHZZ", "Swiss Institution", "CH", Set.of(PaymentRail.SEPA_SCT));

        // Outside SEPA entirely — correspondent banking only.
        add("CHASUS33", "JPMorgan Chase", "US", Set.of(PaymentRail.SWIFT));
        add("MHCBJPJT", "Mizuho Bank", "JP", Set.of(PaymentRail.SWIFT));
    }

    private void add(String bic, String name, String country, Set<PaymentRail> rails) {
        institutions.put(bic, new Institution(name, country, Set.copyOf(rails)));
    }

    public boolean isReachable(Bic bic, PaymentRail rail) {
        Institution institution = institutions.get(bic.institutionBic());
        if (institution == null) {
            // Unknown institution: assume the conservative rail only. Guessing
            // instant reachability produces a rejection at the clearing
            // mechanism, which is worse than routing standard.
            return rail == PaymentRail.SEPA_SCT || rail == PaymentRail.SWIFT;
        }
        return institution.rails().contains(rail);
    }

    public Set<PaymentRail> railsFor(Bic bic) {
        Institution institution = institutions.get(bic.institutionBic());
        return institution == null ? Set.of(PaymentRail.SEPA_SCT) : institution.rails();
    }

    public Optional<String> institutionName(Bic bic) {
        Institution institution = institutions.get(bic.institutionBic());
        return Optional.ofNullable(institution).map(Institution::name);
    }

    public boolean isKnown(Bic bic) {
        return institutions.containsKey(bic.institutionBic());
    }

    /** Registers an institution, as a directory refresh would. */
    public void register(String bic, String name, String country, Set<PaymentRail> rails) {
        add(bic, name, country, rails);
    }

    public int size() {
        return institutions.size();
    }
}
