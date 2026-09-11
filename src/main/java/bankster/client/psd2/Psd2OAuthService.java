package bankster.client.psd2;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

import bankster.client.psd2.Psd2Config.Psd2TppIdentity;
import bankster.client.psd2.Psd2Dtos.TokenResponse;

/**
 * OAuth2 authorisation-code flow used by NextGenPSD2 as the "pre-step" that
 * identifies the PSU to the ASPSP before any AIS call is made.
 */
@Service
public class Psd2OAuthService {

    private final WebClient webClient;
    private final Psd2Properties properties;
    private final Psd2TppIdentity identity;

    public Psd2OAuthService(WebClient psd2WebClient, Psd2Properties properties, Psd2TppIdentity identity) {
        this.webClient = psd2WebClient;
        this.properties = properties;
        this.identity = identity;
    }

    /** URL the PSU's browser is sent to in order to log in at the ASPSP. */
    public String authorizationUrl(String state) {
        return properties.getBaseUrl() + "/oauth/authorize"
                + "?scope=psd2"
                + "&response_type=code"
                + "&client_id=" + encode(identity.clientId())
                + "&redirect_uri=" + encode(properties.getRedirectUri())
                + "&state=" + encode(state);
    }

    public TokenResponse exchangeAuthorizationCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", properties.getRedirectUri());
        form.add("client_id", identity.clientId());
        return requestToken(form);
    }

    public TokenResponse refresh(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("client_id", identity.clientId());
        return requestToken(form);
    }

    private TokenResponse requestToken(MultiValueMap<String, String> form) {
        return webClient.post()
                .uri("/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(form)
                .retrieve()
                .onStatus(status -> status.isError(), Psd2Errors::toException)
                .bodyToMono(TokenResponse.class)
                .block();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
