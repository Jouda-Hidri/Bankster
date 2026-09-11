package bankster.client.psd2;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;

import bankster.client.psd2.Psd2Dtos.Account;
import bankster.client.psd2.Psd2Dtos.AccountAccess;
import bankster.client.psd2.Psd2Dtos.AccountsResponse;
import bankster.client.psd2.Psd2Dtos.Balance;
import bankster.client.psd2.Psd2Dtos.BalancesResponse;
import bankster.client.psd2.Psd2Dtos.ConsentRequest;
import bankster.client.psd2.Psd2Dtos.ConsentResponse;
import bankster.client.psd2.Psd2Dtos.ConsentStatusResponse;
import bankster.client.psd2.Psd2Dtos.TransactionsResponse;

/**
 * Berlin Group NextGenPSD2 Account Information Service (AIS) client.
 *
 * <p>Implements the redirect SCA approach: a consent is created, the PSU signs it
 * in the ASPSP's own UI, and the resulting consent id then authorises reads of
 * accounts, balances and transactions.
 */
@Service
public class Psd2AisService {

    private final WebClient webClient;
    private final Psd2Properties properties;

    public Psd2AisService(WebClient psd2WebClient, Psd2Properties properties) {
        this.webClient = psd2WebClient;
        this.properties = properties;
    }

    /**
     * Creates an AIS consent covering all of the PSU's accounts. The returned
     * object carries the {@code scaRedirect} link the PSU must visit to sign it.
     */
    public ConsentResponse createConsent(String accessToken) {
        ConsentRequest request = new ConsentRequest(
                AccountAccess.allAccounts(),
                true,
                LocalDate.now().plusDays(properties.getConsentValidityDays()),
                properties.getFrequencyPerDay(),
                false);

        return webClient.post()
                .uri("/v1/consents")
                .headers(commonHeaders(accessToken))
                .header("TPP-Redirect-URI", properties.getConsentRedirectUri())
                .header("TPP-Redirect-Preferred", "true")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(status -> status.isError(), Psd2Errors::toException)
                .bodyToMono(ConsentResponse.class)
                .block();
    }

    /** Polls the consent lifecycle status; {@code valid} means the PSU has signed it. */
    public String consentStatus(String accessToken, String consentId) {
        ConsentStatusResponse response = webClient.get()
                .uri("/v1/consents/{consentId}/status", consentId)
                .headers(commonHeaders(accessToken))
                .retrieve()
                .onStatus(status -> status.isError(), Psd2Errors::toException)
                .bodyToMono(ConsentStatusResponse.class)
                .block();
        return response == null ? null : response.consentStatus();
    }

    /** Accounts covered by a signed consent, including the {@code resourceId} needed for detail calls. */
    public List<Account> accounts(String accessToken, String consentId) {
        AccountsResponse response = webClient.get()
                .uri(uri -> uri.path("/v1/accounts").queryParam("onlyActive", true).build())
                .headers(commonHeaders(accessToken))
                .header("Consent-ID", consentId)
                .retrieve()
                .onStatus(status -> status.isError(), Psd2Errors::toException)
                .bodyToMono(AccountsResponse.class)
                .block();
        return response == null || response.accounts() == null ? List.of() : response.accounts();
    }

    public List<Balance> balances(String accessToken, String consentId, String resourceId) {
        BalancesResponse response = webClient.get()
                .uri("/v1/accounts/{resourceId}/balances", resourceId)
                .headers(commonHeaders(accessToken))
                .header("Consent-ID", consentId)
                .retrieve()
                .onStatus(status -> status.isError(), Psd2Errors::toException)
                .bodyToMono(BalancesResponse.class)
                .block();
        return response == null || response.balances() == null ? List.of() : response.balances();
    }

    public TransactionsResponse transactions(String accessToken, String consentId, String resourceId,
                                             LocalDate from, LocalDate to) {
        return webClient.get()
                .uri(uri -> transactionsUri(uri, resourceId, from, to))
                .headers(commonHeaders(accessToken))
                .header("Consent-ID", consentId)
                .retrieve()
                .onStatus(status -> status.isError(), Psd2Errors::toException)
                .bodyToMono(TransactionsResponse.class)
                .block();
    }

    /** Default window of history, used by the overview screen. */
    public TransactionsResponse recentTransactions(String accessToken, String consentId, String resourceId) {
        LocalDate to = LocalDate.now();
        return transactions(accessToken, consentId, resourceId,
                to.minusDays(properties.getTransactionHistoryDays()), to);
    }

    private java.net.URI transactionsUri(UriBuilder uri, String resourceId, LocalDate from, LocalDate to) {
        return uri.path("/v1/accounts/{resourceId}/transactions")
                .queryParam("dateFrom", from)
                .queryParam("dateTo", to)
                .queryParam("bookingStatus", "both")
                .build(resourceId);
    }

    /**
     * Headers every XS2A request carries: the bearer token from the OAuth pre-step,
     * a per-request correlation id, and the PSU's IP address.
     */
    private Consumer<HttpHeaders> commonHeaders(String accessToken) {
        return headers -> {
            headers.setBearerAuth(accessToken);
            headers.set("X-Request-ID", UUID.randomUUID().toString());
            headers.set("PSU-IP-Address", properties.getPsuIpAddress());
        };
    }
}
