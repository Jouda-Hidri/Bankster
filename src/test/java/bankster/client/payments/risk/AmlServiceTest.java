package bankster.client.payments.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import bankster.client.payments.Money;
import bankster.client.payments.TestClock;
import bankster.client.payments.risk.AmlService.AmlDecision;
import bankster.client.payments.risk.AmlService.AmlAssessment;
import bankster.client.payments.risk.AmlService.Direction;
import bankster.client.payments.risk.AmlService.MonitoredTransaction;

/**
 * KYC gating, sanctions screening and transaction monitoring. The obligation here is
 * to detect and report, not merely to block, which is why the filed reports are
 * asserted on as much as the decisions.
 */
class AmlServiceTest {

    private static final String CUSTOMER = "cust-1";

    private TestClock clock;
    private SanctionsList sanctions;
    private AmlService aml;

    @BeforeEach
    void setUp() {
        clock = TestClock.businessDayMorning();
        sanctions = new SanctionsList();
        aml = new AmlService(clock, sanctions);
    }

    private CustomerProfile profile(String name, String country, boolean pep) {
        return new CustomerProfile(CUSTOMER, name, country, country,
                KycStatus.PENDING, pep, Money.of("EUR", "20000.00"), null);
    }

    private MonitoredTransaction debit(String amount, String counterparty, String country) {
        return new MonitoredTransaction(CUSTOMER, Direction.DEBIT,
                Money.of("EUR", amount), counterparty, country, clock.instant());
    }

    private MonitoredTransaction credit(String amount) {
        return new MonitoredTransaction(CUSTOMER, Direction.CREDIT,
                Money.of("EUR", amount), "Some Payer", "EE", clock.instant());
    }

    // --- Onboarding -------------------------------------------------------

    @Test
    void aCleanCustomerIsVerifiedAndMayTransact() {
        aml.register(profile("Liis-Mari Männik", "EE", false));

        AmlAssessment assessment = aml.verifyCustomer(CUSTOMER);

        assertEquals(AmlDecision.ALLOW, assessment.decision());
        assertEquals(KycStatus.VERIFIED, aml.customer(CUSTOMER).orElseThrow().kycStatus());
        assertTrue(aml.customer(CUSTOMER).orElseThrow().kycStatus().canTransact());
        assertEquals(clock.instant(), aml.customer(CUSTOMER).orElseThrow().verifiedAt());
    }

    @Test
    void aListedApplicantIsRefusedAndReported() {
        aml.register(profile("Ivan Petrov", "EE", false));

        AmlAssessment assessment = aml.verifyCustomer(CUSTOMER);

        assertEquals(AmlDecision.BLOCK, assessment.decision());
        assertEquals(KycStatus.REJECTED, aml.customer(CUSTOMER).orElseThrow().kycStatus());
        assertTrue(assessment.sanctionsMatch().isPresent());
        assertEquals("EU Consolidated", assessment.sanctionsMatch().orElseThrow().listName());
        // Filing is mandatory, not discretionary.
        assertTrue(assessment.report().isPresent());
        assertEquals(1, aml.reports().size());
        assertTrue(assessment.report().orElseThrow().narrative().contains("Onboarding refused"));
    }

    @Test
    void aPoliticallyExposedPersonIsAllowedUnderEnhancedDueDiligence() {
        aml.register(profile("Some Minister", "EE", true));

        AmlAssessment assessment = aml.verifyCustomer(CUSTOMER);

        // A PEP is not presumed to be a criminal; the obligation is extra scrutiny.
        assertEquals(AmlDecision.REVIEW, assessment.decision());
        assertEquals(KycStatus.VERIFIED, aml.customer(CUSTOMER).orElseThrow().kycStatus());
        assertTrue(assessment.flags().contains("politically-exposed-person"));
    }

    @Test
    void aProhibitedJurisdictionIsRefusedOutright() {
        aml.register(profile("Someone", "IR", false));

        AmlAssessment assessment = aml.verifyCustomer(CUSTOMER);

        assertEquals(AmlDecision.BLOCK, assessment.decision());
        assertTrue(assessment.flags().contains("prohibited-jurisdiction"));
    }

    @Test
    void aHighRiskJurisdictionIsFlaggedButPermitted() {
        aml.register(profile("Someone", "MM", false));

        AmlAssessment assessment = aml.verifyCustomer(CUSTOMER);

        assertEquals(AmlDecision.REVIEW, assessment.decision());
        assertTrue(assessment.flags().contains("high-risk-jurisdiction"));
    }

    @Test
    void anUnknownCustomerIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> aml.verifyCustomer("nobody"));
        assertThrows(IllegalArgumentException.class,
                () -> aml.screenTransaction(debit("10.00", "X", "EE")));
    }

    // --- KYC gate ---------------------------------------------------------

    @Test
    void anUnverifiedCustomerCannotTransact() {
        aml.register(profile("Liis-Mari Männik", "EE", false));

        AmlAssessment assessment = aml.screenTransaction(debit("100.00", "Klaus Weber", "DE"));

        assertEquals(AmlDecision.BLOCK, assessment.decision());
        assertTrue(assessment.flags().contains("kyc-pending"));
    }

    @Test
    void expiredDueDiligenceAlsoBlocks() {
        aml.register(profile("Liis-Mari Männik", "EE", false));
        aml.verifyCustomer(CUSTOMER);
        aml.register(aml.customer(CUSTOMER).orElseThrow()
                .withKycStatus(KycStatus.EXPIRED, clock.instant()));

        AmlAssessment assessment = aml.screenTransaction(debit("100.00", "Klaus Weber", "DE"));

        assertEquals(AmlDecision.BLOCK, assessment.decision());
        assertFalse(KycStatus.EXPIRED.canTransact());
    }

    // --- Counterparty screening ------------------------------------------

    @Test
    void aPaymentToAListedCounterpartyIsBlockedAndReported() {
        aml.register(profile("Liis-Mari Männik", "EE", false));
        aml.verifyCustomer(CUSTOMER);

        AmlAssessment assessment = aml.screenTransaction(debit("5000.00", "Ivan Petrov", "EE"));

        assertEquals(AmlDecision.BLOCK, assessment.decision());
        assertTrue(assessment.sanctionsMatch().isPresent());
        assertTrue(assessment.report().isPresent());
        assertTrue(assessment.report().orElseThrow().narrative().contains("frozen"));
    }

    @Test
    void screeningIsFuzzyEnoughToCatchTransliterationDifferences() {
        // Sanctioned parties do not spell their names helpfully, and the threshold is
        // tuned towards false positives on purpose.
        assertTrue(sanctions.screenName("Ivan Petroff").isPresent());
        assertTrue(sanctions.screenName("Petrov, Ivan").isPresent(), "word order varies");
        assertTrue(sanctions.screenName("nadia al-rashid").isPresent());
        assertTrue(sanctions.screenName("Nadia Al Rashid").isPresent());
    }

    @Test
    void screeningDoesNotMatchUnrelatedNames() {
        assertTrue(sanctions.screenName("Klaus Weber").isEmpty());
        assertTrue(sanctions.screenName("").isEmpty());
        assertTrue(sanctions.screenName(null).isEmpty());
    }

    @Test
    void aMatchReportsWhichListAndProgrammeItCameFrom() {
        SanctionsList.SanctionsMatch match = sanctions.screenName("Global Trade Holdings LLC")
                .orElseThrow();

        assertEquals("OFAC SDN", match.listName());
        assertEquals("Trade sanctions", match.programme());
        assertTrue(match.similarity() >= SanctionsList.MATCH_THRESHOLD);
    }

    @Test
    void listsCanBeRefreshedAndTakeEffectImmediately() {
        aml.register(profile("Liis-Mari Männik", "EE", false));
        aml.verifyCustomer(CUSTOMER);
        assertEquals(AmlDecision.ALLOW, aml.screenTransaction(debit("10.00", "Klaus Weber", "DE"))
                .decision());

        // A customer who was clean yesterday may be listed today.
        sanctions.addEntry("Klaus Weber", "EU Consolidated", "Asset freeze");

        assertEquals(AmlDecision.BLOCK, aml.screenTransaction(debit("10.00", "Klaus Weber", "DE"))
                .decision());
    }

    @Test
    void aPaymentToAProhibitedCountryIsBlocked() {
        aml.register(profile("Liis-Mari Männik", "EE", false));
        aml.verifyCustomer(CUSTOMER);

        assertEquals(AmlDecision.BLOCK,
                aml.screenTransaction(debit("100.00", "Someone", "KP")).decision());
    }

    @Test
    void aBlockedAttemptDoesNotPolluteTheBehaviouralHistory() {
        aml.register(profile("Liis-Mari Männik", "EE", false));
        aml.verifyCustomer(CUSTOMER);

        aml.screenTransaction(debit("5000.00", "Ivan Petrov", "EE"));

        assertTrue(aml.monitoredTransactions().isEmpty());
    }

    // --- Typologies -------------------------------------------------------

    @Test
    void structuringIsDetectedFromThePatternNotAnyOneAmount() {
        aml.register(profile("Liis-Mari Männik", "EE", false));
        aml.verifyCustomer(CUSTOMER);

        // Each amount sits just under the 10,000 reporting threshold and would pass on
        // its own. Three of them together clear it.
        aml.screenTransaction(debit("9500.00", "Klaus Weber", "DE"));
        aml.screenTransaction(debit("9400.00", "Klaus Weber", "DE"));
        AmlAssessment third = aml.screenTransaction(debit("9300.00", "Klaus Weber", "DE"));

        assertEquals(AmlDecision.REVIEW, third.decision());
        assertTrue(third.flags().contains("structuring"));
        assertTrue(third.report().isPresent());
        assertTrue(third.report().orElseThrow().narrative().contains("just below"));
    }

    @Test
    void twoNearThresholdAmountsAreNotYetAPattern() {
        aml.register(profile("Liis-Mari Männik", "EE", false));
        aml.verifyCustomer(CUSTOMER);

        aml.screenTransaction(debit("9500.00", "Klaus Weber", "DE"));
        AmlAssessment second = aml.screenTransaction(debit("9400.00", "Klaus Weber", "DE"));

        assertFalse(second.flags().contains("structuring"));
    }

    @Test
    void amountsWellBelowTheBandAreNotStructuring() {
        aml.register(profile("Liis-Mari Männik", "EE", false));
        aml.verifyCustomer(CUSTOMER);

        for (int i = 0; i < 5; i++) {
            AmlAssessment assessment = aml.screenTransaction(debit("500.00", "Klaus Weber", "DE"));
            assertFalse(assessment.flags().contains("structuring"));
        }
    }

    @Test
    void structuringFallsOutsideTheSevenDayWindow() {
        aml.register(profile("Liis-Mari Männik", "EE", false));
        aml.verifyCustomer(CUSTOMER);

        aml.screenTransaction(debit("9500.00", "Klaus Weber", "DE"));
        aml.screenTransaction(debit("9400.00", "Klaus Weber", "DE"));
        clock.advance(Duration.ofDays(8));
        AmlAssessment later = aml.screenTransaction(debit("9300.00", "Klaus Weber", "DE"));

        assertFalse(later.flags().contains("structuring"));
    }

    @Test
    void passThroughIsMoneyArrivingAndLeavingAlmostEntirely() {
        aml.register(profile("Liis-Mari Männik", "EE", false));
        aml.verifyCustomer(CUSTOMER);

        aml.screenTransaction(credit("8000.00"));
        AmlAssessment out = aml.screenTransaction(debit("7800.00", "Klaus Weber", "DE"));

        assertTrue(out.flags().contains("pass-through"), out.flags().toString());
        assertTrue(out.report().orElseThrow().narrative().contains("conduit"));
    }

    @Test
    void keepingMostOfWhatArrivedIsNotPassThrough() {
        aml.register(profile("Liis-Mari Männik", "EE", false));
        aml.verifyCustomer(CUSTOMER);

        aml.screenTransaction(credit("8000.00"));
        AmlAssessment out = aml.screenTransaction(debit("1000.00", "Klaus Weber", "DE"));

        assertFalse(out.flags().contains("pass-through"));
    }

    @Test
    void volumeFarBeyondWhatWasDeclaredAtOnboardingIsFlagged() {
        aml.register(profile("Liis-Mari Männik", "EE", false));
        aml.verifyCustomer(CUSTOMER);

        // Declared 20,000 a month; this is over three times that.
        for (int i = 0; i < 4; i++) {
            aml.screenTransaction(debit("18000.00", "Klaus Weber", "DE"));
        }
        AmlAssessment assessment = aml.screenTransaction(debit("1000.00", "Klaus Weber", "DE"));

        assertTrue(assessment.flags().contains("volume-far-above-declared"),
                assessment.flags().toString());
    }

    @Test
    void eachTypologyProducesItsOwnFiledReport() {
        aml.register(profile("Liis-Mari Männik", "EE", false));
        aml.verifyCustomer(CUSTOMER);

        aml.screenTransaction(debit("9500.00", "Klaus Weber", "DE"));
        aml.screenTransaction(debit("9400.00", "Klaus Weber", "DE"));
        aml.screenTransaction(debit("9300.00", "Klaus Weber", "DE"));

        assertEquals(1, aml.reports().size());
        AmlService.SuspiciousActivityReport report = aml.reports().get(0);
        assertTrue(report.reportId().startsWith("SAR-"));
        assertEquals(CUSTOMER, report.customerId());
        assertEquals(clock.instant(), report.filedAt());
        assertFalse(report.typologies().isEmpty());
    }

    @Test
    void anOrdinaryPaymentIsAllowedWithNothingFlagged() {
        aml.register(profile("Liis-Mari Männik", "EE", false));
        aml.verifyCustomer(CUSTOMER);

        AmlAssessment assessment = aml.screenTransaction(debit("250.00", "Klaus Weber", "DE"));

        assertEquals(AmlDecision.ALLOW, assessment.decision());
        assertTrue(assessment.flags().isEmpty());
        assertTrue(assessment.report().isEmpty());
        assertEquals(1, aml.monitoredTransactions().size());
    }

    // --- Name matching ----------------------------------------------------

    @Test
    void normalisationCollapsesAccentsCaseAndWordOrder() {
        assertEquals(NameMatching.normalise("Al-Rashid, Nadia"),
                NameMatching.normalise("nadia al rashid"));
        assertEquals(NameMatching.normalise("Liis-Mari Männik"),
                NameMatching.normalise("mannik liis mari"));
    }

    @Test
    void similarityIsOneForIdenticalNamesAndFallsWithEdits() {
        assertEquals(1.0, NameMatching.similarityOf("John Smith", "smith, john"));
        assertTrue(NameMatching.similarityOf("John Smith", "Jon Smith") > 0.85);
        assertTrue(NameMatching.similarityOf("John Smith", "Klaus Weber") < 0.4);
    }

    @Test
    void levenshteinCountsTheEditsItShould() {
        assertEquals(0, NameMatching.levenshtein("abc", "abc"));
        assertEquals(1, NameMatching.levenshtein("abc", "abd"));
        assertEquals(1, NameMatching.levenshtein("abc", "ab"), "one deletion");
        assertEquals(2, NameMatching.levenshtein("abc", "a"));
        assertEquals(3, NameMatching.levenshtein("", "abc"));
    }
}
