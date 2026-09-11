package bankster.client.psd2;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.web.servlet.MockMvc;

import bankster.client.psd2.Psd2Config.Psd2TppIdentity;
import bankster.client.psd2.Psd2Dtos.Account;
import bankster.client.psd2.Psd2Dtos.AccountReference;
import bankster.client.psd2.Psd2Dtos.AccountTransactions;
import bankster.client.psd2.Psd2Dtos.Amount;
import bankster.client.psd2.Psd2Dtos.Balance;
import bankster.client.psd2.Psd2Dtos.ConsentResponse;
import bankster.client.psd2.Psd2Dtos.Link;
import bankster.client.psd2.Psd2Dtos.TokenResponse;
import bankster.client.psd2.Psd2Dtos.Transaction;
import bankster.client.psd2.Psd2Dtos.TransactionsResponse;

/**
 * Renders the PSD2 screens and walks the redirect flow. {@link Psd2Config} is
 * excluded because it would try to open the TPP keystore, which only exists on a
 * machine that has run the sandbox certificate script.
 */
@WebMvcTest(controllers = Psd2Controller.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = Psd2Config.class))
class Psd2ControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private Psd2OAuthService oauthService;

    @MockBean
    private Psd2AisService aisService;

    @MockBean
    private Psd2Session session;

    @MockBean
    private Psd2Properties properties;

    @MockBean
    private Psd2TppIdentity identity;

    @BeforeEach
    void defaults() {
        given(identity.clientId()).willReturn("PSDEE-LHVTEST-000000");
        given(properties.getBaseUrl()).willReturn("https://api.sandbox.lhv.eu/psd2");
        given(properties.getTransactionHistoryDays()).willReturn(90);
    }

    @Test
    void overviewShowsTheTppIdentityBeforeAnyConnection() throws Exception {
        given(session.isAuthenticated()).willReturn(false);

        mockMvc.perform(get("/psd2"))
                .andExpect(status().isOk())
                .andExpect(view().name("psd2/overview"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("PSDEE-LHVTEST-000000")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/psd2/connect")));
    }

    @Test
    void connectRedirectsToTheAspspAuthorizationEndpoint() throws Exception {
        given(oauthService.authorizationUrl(anyString()))
                .willReturn("https://api.sandbox.lhv.eu/psd2/oauth/authorize?scope=psd2");

        mockMvc.perform(get("/psd2/connect"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("https://api.sandbox.lhv.eu/psd2/oauth/authorize?scope=psd2"));
    }

    @Test
    void oauthCallbackExchangesCodeCreatesConsentAndSendsPsuToSignIt() throws Exception {
        given(session.getOauthState()).willReturn("state-1");
        given(oauthService.exchangeAuthorizationCode("code-1"))
                .willReturn(new TokenResponse("access-1", "refresh-1", "Bearer", 3599L, "psd2"));
        given(aisService.createConsent("access-1")).willReturn(new ConsentResponse(
                "consent-1", "received",
                java.util.Map.of("scaRedirect", new Link("https://aspsp.example/sign/consent-1"))));

        mockMvc.perform(get("/psd2/callback/oauth").param("code", "code-1").param("state", "state-1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("https://aspsp.example/sign/consent-1"));
    }

    @Test
    void oauthCallbackRejectsAMismatchedState() throws Exception {
        given(session.getOauthState()).willReturn("state-1");

        mockMvc.perform(get("/psd2/callback/oauth").param("code", "code-1").param("state", "forged"))
                .andExpect(status().isOk())
                .andExpect(view().name("psd2/error"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("state mismatch")));
    }

    @Test
    void consentCallbackGoesToAccountsOnceTheConsentIsValid() throws Exception {
        given(session.isAuthenticated()).willReturn(true);
        given(session.getConsentId()).willReturn("consent-1");
        given(session.getAccessToken()).willReturn("access-1");
        given(aisService.consentStatus("access-1", "consent-1")).willReturn("valid");

        mockMvc.perform(get("/psd2/callback/consent"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/psd2/accounts"));
    }

    @Test
    void consentCallbackReportsAnUnsignedConsent() throws Exception {
        given(session.isAuthenticated()).willReturn(true);
        given(session.getConsentId()).willReturn("consent-1");
        given(session.getAccessToken()).willReturn("access-1");
        given(aisService.consentStatus("access-1", "consent-1")).willReturn("received");

        mockMvc.perform(get("/psd2/callback/consent"))
                .andExpect(view().name("psd2/error"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("received")));
    }

    @Test
    void accountsPageRendersAccountsWithBalances() throws Exception {
        given(session.isAuthenticated()).willReturn(true);
        given(session.getAccessToken()).willReturn("access-1");
        given(session.getConsentId()).willReturn("consent-1");
        given(aisService.accounts("access-1", "consent-1")).willReturn(List.of(
                new Account("res-1", "EE717700771001735865", "Liis-Mari Mannik", "EUR",
                        "Account 1", "Multi currency account", "CACC", "enabled")));
        given(aisService.balances("access-1", "consent-1", "res-1")).willReturn(List.of(
                new Balance("interimAvailable", new Amount("EUR", "1234.56"))));

        mockMvc.perform(get("/psd2/accounts"))
                .andExpect(status().isOk())
                .andExpect(view().name("psd2/accounts"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("EE717700771001735865")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("1234.56")))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("/psd2/accounts/res-1/transactions")));
    }

    @Test
    void accountsPageStillRendersWhenABalanceCallFails() throws Exception {
        given(session.isAuthenticated()).willReturn(true);
        given(session.getAccessToken()).willReturn("access-1");
        given(session.getConsentId()).willReturn("consent-1");
        given(aisService.accounts("access-1", "consent-1")).willReturn(List.of(
                new Account("res-1", "EE717700771001735865", null, "EUR", null, null, "CACC", "enabled")));
        given(aisService.balances(anyString(), anyString(), anyString()))
                .willThrow(new Psd2Exception(429, "ACCESS_EXCEEDED: too many requests"));

        mockMvc.perform(get("/psd2/accounts"))
                .andExpect(status().isOk())
                .andExpect(view().name("psd2/accounts"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("EE717700771001735865")));
    }

    @Test
    void transactionsPageRendersBookedAndPendingEntries() throws Exception {
        given(session.isAuthenticated()).willReturn(true);
        given(session.getAccessToken()).willReturn("access-1");
        given(session.getConsentId()).willReturn("consent-1");
        given(aisService.recentTransactions("access-1", "consent-1", "res-1")).willReturn(
                new TransactionsResponse(
                        new AccountReference("EE717700771001735865"),
                        new AccountTransactions(
                                List.of(new Transaction("e-1", LocalDate.of(2026, 9, 1),
                                        new Amount("EUR", "-25.00"), "Coffee Bar", null, null, null,
                                        "Card payment", null, "PMNT")),
                                List.of())));

        mockMvc.perform(get("/psd2/accounts/res-1/transactions"))
                .andExpect(status().isOk())
                .andExpect(view().name("psd2/transactions"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Coffee Bar")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("-25.00")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No pending transactions")));
    }

    @Test
    void anXs2aFailureIsRenderedOnTheErrorPage() throws Exception {
        given(session.isAuthenticated()).willReturn(true);
        given(session.getAccessToken()).willReturn("access-1");
        given(session.getConsentId()).willReturn("stale");
        given(aisService.accounts(anyString(), any()))
                .willThrow(new Psd2Exception(400, "CONSENT_UNKNOWN: consent-id did not match"));

        mockMvc.perform(get("/psd2/accounts"))
                .andExpect(status().isOk())
                .andExpect(view().name("psd2/error"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("CONSENT_UNKNOWN")));
    }
}
