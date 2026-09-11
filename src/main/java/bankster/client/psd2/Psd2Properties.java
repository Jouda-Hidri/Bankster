package bankster.client.psd2;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Berlin Group NextGenPSD2 (XS2A) connection.
 *
 * <p>Defaults point at the LHV NextGenPSD2 sandbox, which implements the Berlin
 * Group XS2A framework and can be used without a commercial agreement. Any other
 * NextGenPSD2-conformant ASPSP can be targeted by overriding {@code psd2.base-url}.
 */
@ConfigurationProperties(prefix = "psd2")
public class Psd2Properties {

    /** Root of the ASPSP's XS2A API, without the {@code /v1} version segment. */
    private String baseUrl = "https://api.sandbox.lhv.eu/psd2";

    /**
     * OAuth2 {@code client_id}. For eIDAS-based APIs this is the TPP
     * authorisation number. Left blank it is read from the QWAC's subject
     * (organizationIdentifier, OID 2.5.4.97), which is how the sandbox issues it.
     */
    private String clientId = "";

    /** Where the ASPSP sends the PSU back after the OAuth2 login. */
    private String redirectUri = "http://localhost:8099/psd2/callback/oauth";

    /** Where the ASPSP sends the PSU back after signing the AIS consent. */
    private String consentRedirectUri = "http://localhost:8099/psd2/callback/consent";

    /** PKCS#12 keystore holding the QWAC/transport certificate and its private key. */
    private String keystore = "file:./psd2-sandbox/tpp-keystore.p12";

    private String keystorePassword = "changeit";

    /** Value sent in the {@code PSU-IP-Address} header, required by XS2A for PSU-initiated calls. */
    private String psuIpAddress = "127.0.0.1";

    /** How many days of transaction history to request by default. */
    private int transactionHistoryDays = 90;

    /** Days the requested consent stays valid. */
    private int consentValidityDays = 90;

    /** Requests the TPP is allowed to make per day under the consent. */
    private int frequencyPerDay = 50;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
    }

    public String getConsentRedirectUri() {
        return consentRedirectUri;
    }

    public void setConsentRedirectUri(String consentRedirectUri) {
        this.consentRedirectUri = consentRedirectUri;
    }

    public String getKeystore() {
        return keystore;
    }

    public void setKeystore(String keystore) {
        this.keystore = keystore;
    }

    public String getKeystorePassword() {
        return keystorePassword;
    }

    public void setKeystorePassword(String keystorePassword) {
        this.keystorePassword = keystorePassword;
    }

    public String getPsuIpAddress() {
        return psuIpAddress;
    }

    public void setPsuIpAddress(String psuIpAddress) {
        this.psuIpAddress = psuIpAddress;
    }

    public int getTransactionHistoryDays() {
        return transactionHistoryDays;
    }

    public void setTransactionHistoryDays(int transactionHistoryDays) {
        this.transactionHistoryDays = transactionHistoryDays;
    }

    public int getConsentValidityDays() {
        return consentValidityDays;
    }

    public void setConsentValidityDays(int consentValidityDays) {
        this.consentValidityDays = consentValidityDays;
    }

    public int getFrequencyPerDay() {
        return frequencyPerDay;
    }

    public void setFrequencyPerDay(int frequencyPerDay) {
        this.frequencyPerDay = frequencyPerDay;
    }
}
