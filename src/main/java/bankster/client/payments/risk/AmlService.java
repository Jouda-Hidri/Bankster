package bankster.client.payments.risk;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

import bankster.client.payments.Money;
import bankster.client.payments.risk.SanctionsList.SanctionsMatch;

/**
 * Anti-money-laundering controls: KYC gating, sanctions screening and
 * transaction monitoring.
 *
 * <p>Fraud and AML are often lumped together and are not the same problem.
 * Fraud asks "is this person who they claim to be, and will this transaction
 * cost us money" — the firm is the victim. AML asks "is this money clean, and
 * are we being used to move it" — the firm is the instrument, the loss is
 * society's, and the obligation is to detect and report rather than merely to
 * block. That is why this service produces suspicious activity reports as well
 * as decisions: under AMLD, filing is mandatory, and tipping off the customer
 * that a report was filed is itself an offence.
 *
 * <p>The monitoring rules implemented here are the classic typologies:
 *
 * <ul>
 *   <li><b>Structuring.</b> Large sums broken into amounts that individually sit
 *       just under a reporting threshold. Any single one is unremarkable; the
 *       pattern is the evidence.</li>
 *   <li><b>Pass-through.</b> Money arriving and leaving almost immediately and
 *       almost entirely — an account being used as a conduit rather than for
 *       economic activity.</li>
 *   <li><b>Profile departure.</b> Volume far beyond what the customer declared
 *       at onboarding, which is what the declaration is collected for.</li>
 * </ul>
 */
@Component
public class AmlService {

    /** The cash-reporting threshold structuring tries to stay under. */
    public static final Money REPORTING_THRESHOLD = Money.of("EUR", "10000.00");

    /** Amounts within this fraction of the threshold look deliberately placed. */
    private static final double STRUCTURING_BAND = 0.80;

    private static final Duration STRUCTURING_WINDOW = Duration.ofDays(7);
    private static final Duration PASS_THROUGH_WINDOW = Duration.ofHours(24);

    private final Clock clock;
    private final SanctionsList sanctionsList;

    private final Map<String, CustomerProfile> customers = new ConcurrentHashMap<>();
    private final List<MonitoredTransaction> transactions = new CopyOnWriteArrayList<>();
    private final List<SuspiciousActivityReport> reports = new CopyOnWriteArrayList<>();

    public AmlService(Clock clock, SanctionsList sanctionsList) {
        this.clock = clock;
        this.sanctionsList = sanctionsList;
    }

    public enum Direction {
        CREDIT,
        DEBIT
    }

    public record MonitoredTransaction(
            String customerId,
            Direction direction,
            Money amount,
            String counterpartyName,
            String counterpartyCountry,
            Instant at) {
    }

    public enum AmlDecision {
        /** Nothing to answer; the payment proceeds. */
        ALLOW,
        /** Proceeds, but an analyst must look — enhanced due diligence. */
        REVIEW,
        /** Must not proceed. A sanctions hit or an unverified customer. */
        BLOCK
    }

    public record AmlAssessment(
            AmlDecision decision,
            List<String> flags,
            Optional<SanctionsMatch> sanctionsMatch,
            Optional<SuspiciousActivityReport> report) {

        public AmlAssessment {
            flags = List.copyOf(flags);
        }

        public boolean isBlocked() {
            return decision == AmlDecision.BLOCK;
        }
    }

    /**
     * A filed report. The narrative matters as much as the flag: the financial
     * intelligence unit receiving it needs to understand the pattern, not just
     * that a threshold tripped.
     */
    public record SuspiciousActivityReport(
            String reportId,
            String customerId,
            List<String> typologies,
            String narrative,
            Instant filedAt) {

        public SuspiciousActivityReport {
            typologies = List.copyOf(typologies);
        }
    }

    // --- Customer records ------------------------------------------------

    public CustomerProfile register(CustomerProfile profile) {
        customers.put(profile.customerId(), profile);
        return profile;
    }

    public Optional<CustomerProfile> customer(String customerId) {
        return Optional.ofNullable(customers.get(customerId));
    }

    /**
     * Completes onboarding. Screening runs here as well as at payment time,
     * because a customer must not be taken on at all if they are listed.
     */
    public AmlAssessment verifyCustomer(String customerId) {
        CustomerProfile profile = require(customerId);
        Optional<SanctionsMatch> match = sanctionsList.screenName(profile.fullName());
        if (match.isPresent()) {
            customers.put(customerId, profile.withKycStatus(KycStatus.REJECTED, clock.instant()));
            SuspiciousActivityReport report = file(customerId, List.of("sanctions-match"),
                    "Onboarding refused: name matches " + match.get().listedName()
                            + " on " + match.get().listName() + " at similarity "
                            + String.format(java.util.Locale.ROOT, "%.2f", match.get().similarity()) + ".");
            return new AmlAssessment(AmlDecision.BLOCK, List.of("sanctions-match"), match, Optional.of(report));
        }
        if (sanctionsList.isProhibitedCountry(profile.countryOfResidence())) {
            customers.put(customerId, profile.withKycStatus(KycStatus.REJECTED, clock.instant()));
            return new AmlAssessment(AmlDecision.BLOCK, List.of("prohibited-jurisdiction"),
                    Optional.empty(), Optional.empty());
        }

        customers.put(customerId, profile.withKycStatus(KycStatus.VERIFIED, clock.instant()));
        List<String> flags = new ArrayList<>();
        if (profile.politicallyExposed()) {
            // Not a refusal: a PEP may bank, under enhanced due diligence and
            // senior approval.
            flags.add("politically-exposed-person");
        }
        if (sanctionsList.isHighRiskCountry(profile.countryOfResidence())) {
            flags.add("high-risk-jurisdiction");
        }
        return new AmlAssessment(flags.isEmpty() ? AmlDecision.ALLOW : AmlDecision.REVIEW,
                flags, Optional.empty(), Optional.empty());
    }

    // --- Transaction screening -------------------------------------------

    /**
     * Screens one transaction and, if it fits a typology, files a report.
     * Recording happens only when the transaction is allowed, so a blocked
     * attempt does not pollute the customer's behavioural history.
     */
    public AmlAssessment screenTransaction(MonitoredTransaction transaction) {
        CustomerProfile profile = require(transaction.customerId());
        List<String> flags = new ArrayList<>();

        if (!profile.kycStatus().canTransact()) {
            return new AmlAssessment(AmlDecision.BLOCK,
                    List.of("kyc-" + profile.kycStatus().name().toLowerCase()),
                    Optional.empty(), Optional.empty());
        }

        Optional<SanctionsMatch> counterpartyMatch = sanctionsList.screenName(transaction.counterpartyName());
        if (counterpartyMatch.isPresent()) {
            SuspiciousActivityReport report = file(transaction.customerId(), List.of("sanctions-match"),
                    "Payment of " + transaction.amount() + " to counterparty '"
                            + transaction.counterpartyName() + "' matching "
                            + counterpartyMatch.get().listedName() + " on "
                            + counterpartyMatch.get().listName() + ". Funds frozen pending investigation.");
            return new AmlAssessment(AmlDecision.BLOCK, List.of("sanctions-match"),
                    counterpartyMatch, Optional.of(report));
        }

        if (sanctionsList.isProhibitedCountry(transaction.counterpartyCountry())) {
            return new AmlAssessment(AmlDecision.BLOCK, List.of("prohibited-jurisdiction"),
                    Optional.empty(), Optional.empty());
        }
        if (sanctionsList.isHighRiskCountry(transaction.counterpartyCountry())) {
            flags.add("high-risk-jurisdiction");
        }
        if (profile.politicallyExposed()) {
            flags.add("politically-exposed-person");
        }

        transactions.add(transaction);

        flags.addAll(detectStructuring(transaction));
        flags.addAll(detectPassThrough(transaction));
        flags.addAll(detectProfileDeparture(transaction, profile));

        List<String> typologies = flags.stream()
                .filter(flag -> flag.equals("structuring") || flag.equals("pass-through")
                        || flag.equals("volume-far-above-declared"))
                .toList();

        Optional<SuspiciousActivityReport> report = typologies.isEmpty()
                ? Optional.empty()
                : Optional.of(file(transaction.customerId(), typologies,
                narrativeFor(transaction, typologies)));

        AmlDecision decision = flags.isEmpty() ? AmlDecision.ALLOW : AmlDecision.REVIEW;
        return new AmlAssessment(decision, flags, Optional.empty(), report);
    }

    private List<String> detectStructuring(MonitoredTransaction transaction) {
        String currency = transaction.amount().currency();
        if (!currency.equals(REPORTING_THRESHOLD.currency())) {
            return List.of();
        }
        long lowerBound = (long) (REPORTING_THRESHOLD.minorUnits() * STRUCTURING_BAND);
        Instant cutoff = transaction.at().minus(STRUCTURING_WINDOW);

        List<MonitoredTransaction> nearThreshold = transactions.stream()
                .filter(candidate -> candidate.customerId().equals(transaction.customerId()))
                .filter(candidate -> !candidate.at().isBefore(cutoff))
                .filter(candidate -> candidate.amount().currency().equals(currency))
                .filter(candidate -> candidate.amount().minorUnits() >= lowerBound
                        && candidate.amount().minorUnits() < REPORTING_THRESHOLD.minorUnits())
                .toList();

        long total = nearThreshold.stream().mapToLong(candidate -> candidate.amount().minorUnits()).sum();

        // Three or more deliberately-sized amounts that together clear the
        // threshold they individually avoid.
        if (nearThreshold.size() >= 3 && total >= REPORTING_THRESHOLD.minorUnits()) {
            return List.of("structuring");
        }
        return List.of();
    }

    private List<String> detectPassThrough(MonitoredTransaction transaction) {
        if (transaction.direction() != Direction.DEBIT) {
            return List.of();
        }
        Instant cutoff = transaction.at().minus(PASS_THROUGH_WINDOW);
        long credited = sumWhere(transaction.customerId(), Direction.CREDIT, cutoff, transaction.amount().currency());
        long debited = sumWhere(transaction.customerId(), Direction.DEBIT, cutoff, transaction.amount().currency());

        // Almost everything that arrived has already left again.
        if (credited > 0 && debited >= credited * 0.9 && credited >= REPORTING_THRESHOLD.minorUnits() / 2) {
            return List.of("pass-through");
        }
        return List.of();
    }

    private List<String> detectProfileDeparture(MonitoredTransaction transaction, CustomerProfile profile) {
        Money expected = profile.expectedMonthlyVolume();
        if (expected == null || !expected.currency().equals(transaction.amount().currency())) {
            return List.of();
        }
        Instant cutoff = transaction.at().minus(Duration.ofDays(30));
        long monthly = sumWhere(transaction.customerId(), null, cutoff, transaction.amount().currency());
        if (expected.minorUnits() > 0 && monthly > expected.minorUnits() * 3) {
            return List.of("volume-far-above-declared");
        }
        return List.of();
    }

    private long sumWhere(String customerId, Direction direction, Instant cutoff, String currency) {
        return transactions.stream()
                .filter(candidate -> candidate.customerId().equals(customerId))
                .filter(candidate -> direction == null || candidate.direction() == direction)
                .filter(candidate -> !candidate.at().isBefore(cutoff))
                .filter(candidate -> candidate.amount().currency().equals(currency))
                .mapToLong(candidate -> candidate.amount().minorUnits())
                .sum();
    }

    private String narrativeFor(MonitoredTransaction transaction, List<String> typologies) {
        StringBuilder narrative = new StringBuilder("Customer ")
                .append(transaction.customerId())
                .append(": ");
        if (typologies.contains("structuring")) {
            narrative.append("multiple transfers sized just below the ")
                    .append(REPORTING_THRESHOLD)
                    .append(" reporting threshold within seven days, together exceeding it. ");
        }
        if (typologies.contains("pass-through")) {
            narrative.append("funds received were transferred out almost in full within 24 hours, "
                    + "consistent with conduit use rather than economic activity. ");
        }
        if (typologies.contains("volume-far-above-declared")) {
            narrative.append("30-day volume exceeds the volume declared at onboarding by more than threefold. ");
        }
        narrative.append("Most recent transaction: ")
                .append(transaction.amount())
                .append(" to '")
                .append(transaction.counterpartyName())
                .append("'.");
        return narrative.toString();
    }

    private SuspiciousActivityReport file(String customerId, List<String> typologies, String narrative) {
        SuspiciousActivityReport report = new SuspiciousActivityReport(
                "SAR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                customerId, typologies, narrative, clock.instant());
        reports.add(report);
        return report;
    }

    private CustomerProfile require(String customerId) {
        CustomerProfile profile = customers.get(customerId);
        if (profile == null) {
            throw new IllegalArgumentException("Unknown customer: " + customerId);
        }
        return profile;
    }

    public List<SuspiciousActivityReport> reports() {
        return List.copyOf(reports);
    }

    public List<MonitoredTransaction> monitoredTransactions() {
        return List.copyOf(transactions);
    }
}
