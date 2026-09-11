package bankster.client.psd2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import bankster.client.psd2.Psd2Config.Psd2TppIdentity;
import bankster.client.psd2.Psd2Dtos.Account;
import bankster.client.psd2.Psd2Dtos.Balance;
import bankster.client.psd2.Psd2Dtos.ConsentResponse;
import bankster.client.psd2.Psd2Dtos.TokenResponse;
import bankster.client.psd2.Psd2Dtos.TransactionsResponse;

/**
 * Drives the NextGenPSD2 redirect flow from the browser:
 *
 * <ol>
 *   <li>{@code /psd2/connect} sends the PSU to the ASPSP's OAuth login.</li>
 *   <li>{@code /psd2/callback/oauth} swaps the code for a token and creates an AIS consent.</li>
 *   <li>The PSU signs the consent in the ASPSP's UI and returns to {@code /psd2/callback/consent}.</li>
 *   <li>{@code /psd2/accounts} then reads accounts, balances and transactions.</li>
 * </ol>
 */
@Controller
@RequestMapping("/psd2")
public class Psd2Controller {

    private static final Logger log = LoggerFactory.getLogger(Psd2Controller.class);

    private final Psd2OAuthService oauthService;
    private final Psd2AisService aisService;
    private final Psd2Session session;
    private final Psd2Properties properties;
    private final Psd2TppIdentity identity;

    public Psd2Controller(Psd2OAuthService oauthService, Psd2AisService aisService,
                          Psd2Session session, Psd2Properties properties, Psd2TppIdentity identity) {
        this.oauthService = oauthService;
        this.aisService = aisService;
        this.session = session;
        this.properties = properties;
        this.identity = identity;
    }

    @GetMapping
    public String overview(Model model) {
        model.addAttribute("baseUrl", properties.getBaseUrl());
        model.addAttribute("clientId", identity.clientId());
        model.addAttribute("authenticated", session.isAuthenticated());
        model.addAttribute("consentId", session.getConsentId());
        model.addAttribute("consentStatus", session.getConsentStatus());
        model.addAttribute("consentValid", session.isConsentValid());
        return "psd2/overview";
    }

    @GetMapping("/connect")
    public String connect() {
        String state = UUID.randomUUID().toString();
        session.setOauthState(state);
        return "redirect:" + oauthService.authorizationUrl(state);
    }

    @GetMapping("/callback/oauth")
    public String oauthCallback(@RequestParam(required = false) String code,
                                @RequestParam(required = false) String state,
                                @RequestParam(required = false) String error,
                                Model model) {
        if (error != null) {
            return renderError(model, "The bank refused the login: " + error);
        }
        if (code == null) {
            return renderError(model, "The bank returned no authorization code.");
        }
        if (session.getOauthState() == null || !session.getOauthState().equals(state)) {
            return renderError(model, "OAuth state mismatch — the login was not started by this session.");
        }

        TokenResponse token = oauthService.exchangeAuthorizationCode(code);
        session.setAccessToken(token.accessToken());
        session.setRefreshToken(token.refreshToken());
        session.setOauthState(null);

        // With the PSU identified, ask for consent to read their accounts.
        ConsentResponse consent = aisService.createConsent(token.accessToken());
        session.setConsentId(consent.consentId());
        session.setConsentStatus(consent.consentStatus());

        String scaRedirect = consent.scaRedirectUrl();
        if (scaRedirect == null) {
            return renderError(model, "The bank did not supply an SCA redirect link for the consent.");
        }
        log.info("Consent {} created, sending PSU to sign it", consent.consentId());
        return "redirect:" + scaRedirect;
    }

    @GetMapping("/callback/consent")
    public String consentCallback(Model model) {
        if (!session.isAuthenticated() || session.getConsentId() == null) {
            return renderError(model, "No consent is in progress for this session.");
        }
        String status = aisService.consentStatus(session.getAccessToken(), session.getConsentId());
        session.setConsentStatus(status);
        if (!"valid".equals(status)) {
            return renderError(model,
                    "The consent was not signed (status: " + status + "). Start again to retry.");
        }
        return "redirect:/psd2/accounts";
    }

    @GetMapping("/accounts")
    public String accounts(Model model) {
        if (!session.isAuthenticated()) {
            return "redirect:/psd2";
        }
        List<Account> accounts = aisService.accounts(session.getAccessToken(), session.getConsentId());

        // Balances are a separate XS2A call per account.
        Map<String, List<Balance>> balances = new LinkedHashMap<>();
        for (Account account : accounts) {
            try {
                balances.put(account.resourceId(),
                        aisService.balances(session.getAccessToken(), session.getConsentId(), account.resourceId()));
            } catch (Psd2Exception e) {
                log.warn("Could not read balances for {}: {}", account.resourceId(), e.getMessage());
                balances.put(account.resourceId(), List.of());
            }
        }

        model.addAttribute("accounts", accounts);
        model.addAttribute("balances", balances);
        return "psd2/accounts";
    }

    @GetMapping("/accounts/{resourceId}/transactions")
    public String transactions(@PathVariable String resourceId, Model model) {
        if (!session.isAuthenticated()) {
            return "redirect:/psd2";
        }
        TransactionsResponse response =
                aisService.recentTransactions(session.getAccessToken(), session.getConsentId(), resourceId);

        model.addAttribute("iban", response.account() == null ? resourceId : response.account().iban());
        model.addAttribute("booked", response.booked());
        model.addAttribute("pending", response.pending());
        model.addAttribute("days", properties.getTransactionHistoryDays());
        return "psd2/transactions";
    }

    @GetMapping("/disconnect")
    public String disconnect() {
        session.clear();
        return "redirect:/psd2";
    }

    @ExceptionHandler(Psd2Exception.class)
    public String handlePsd2Failure(Psd2Exception exception, Model model) {
        log.warn("XS2A call failed: {}", exception.getMessage());
        return renderError(model, exception.getMessage());
    }

    private String renderError(Model model, String message) {
        model.addAttribute("message", message);
        model.addAttribute("details", new ArrayList<String>());
        return "psd2/error";
    }
}
