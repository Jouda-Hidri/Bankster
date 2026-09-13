package bankster.client.payments;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the payments and banking modules.
 *
 * <p>The values that are configurable here are the ones that genuinely differ
 * between institutions, rather than everything that could in principle be a
 * setting. A per-transaction instant transfer limit, an acquirer's measured fraud
 * rate, and a settlement cut-off are all commercial or operational decisions that
 * change without code changing. Scheme rules — the interchange caps, the
 * representment window, the low-value exemption ceiling — are not configurable,
 * because they are imposed from outside and a deployment that sets them
 * differently is simply wrong.
 */
@ConfigurationProperties(prefix = "payments")
public class PaymentsProperties {

    /**
     * The acquirer's measured fraud rate in basis points. Sets how high the
     * transaction risk analysis exemption may be claimed: 13 bps buys a €100
     * ceiling, 6 bps €250, 1 bp €500.
     */
    private int acquirerFraudRateBasisPoints = 5;

    /** This institution's per-transaction ceiling for SEPA Instant, as a decimal amount. */
    private String instantTransferLimit = "100000.00";

    /** Above this amount an urgent transfer goes to RTGS rather than the retail rail. */
    private String highValueThreshold = "250000.00";

    /** Whether to populate the demo data and run a full lifecycle at start-up. */
    private boolean seedDemoData = true;

    /** Currency the demo operates in. */
    private String demoCurrency = "EUR";

    public int getAcquirerFraudRateBasisPoints() {
        return acquirerFraudRateBasisPoints;
    }

    public void setAcquirerFraudRateBasisPoints(int acquirerFraudRateBasisPoints) {
        this.acquirerFraudRateBasisPoints = acquirerFraudRateBasisPoints;
    }

    public String getInstantTransferLimit() {
        return instantTransferLimit;
    }

    public void setInstantTransferLimit(String instantTransferLimit) {
        this.instantTransferLimit = instantTransferLimit;
    }

    public String getHighValueThreshold() {
        return highValueThreshold;
    }

    public void setHighValueThreshold(String highValueThreshold) {
        this.highValueThreshold = highValueThreshold;
    }

    public boolean isSeedDemoData() {
        return seedDemoData;
    }

    public void setSeedDemoData(boolean seedDemoData) {
        this.seedDemoData = seedDemoData;
    }

    public String getDemoCurrency() {
        return demoCurrency;
    }

    public void setDemoCurrency(String demoCurrency) {
        this.demoCurrency = demoCurrency;
    }

    public Money instantTransferLimitAsMoney() {
        return Money.of(demoCurrency, instantTransferLimit);
    }

    public Money highValueThresholdAsMoney() {
        return Money.of(demoCurrency, highValueThreshold);
    }
}
