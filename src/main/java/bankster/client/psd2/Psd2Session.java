package bankster.client.psd2;

import java.io.Serializable;

import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

/**
 * Per-PSU state for one XS2A connection: the OAuth tokens obtained in the
 * pre-step and the consent the PSU signed. Held in the HTTP session so the
 * demo needs no database.
 */
@Component
@SessionScope(proxyMode = ScopedProxyMode.TARGET_CLASS)
public class Psd2Session implements Serializable {

    private String accessToken;
    private String refreshToken;
    private String consentId;
    private String consentStatus;
    /** CSRF guard for the OAuth redirect. */
    private String oauthState;

    public boolean isAuthenticated() {
        return accessToken != null;
    }

    /** True once the PSU has signed the consent, which is what unlocks account data. */
    public boolean isConsentValid() {
        return consentId != null && "valid".equals(consentStatus);
    }

    public void clear() {
        accessToken = null;
        refreshToken = null;
        consentId = null;
        consentStatus = null;
        oauthState = null;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public String getConsentId() {
        return consentId;
    }

    public void setConsentId(String consentId) {
        this.consentId = consentId;
    }

    public String getConsentStatus() {
        return consentStatus;
    }

    public void setConsentStatus(String consentStatus) {
        this.consentStatus = consentStatus;
    }

    public String getOauthState() {
        return oauthState;
    }

    public void setOauthState(String oauthState) {
        this.oauthState = oauthState;
    }
}
