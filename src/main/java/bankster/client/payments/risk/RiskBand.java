package bankster.client.payments.risk;

/** Score buckets. Bands rather than raw numbers keep policy readable. */
public enum RiskBand {

    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    public static RiskBand forScore(int score) {
        if (score >= 80) {
            return CRITICAL;
        }
        if (score >= 50) {
            return HIGH;
        }
        if (score >= 25) {
            return MEDIUM;
        }
        return LOW;
    }
}
