package bankster.client.psd2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import bankster.client.psd2.Psd2Dtos.Account;
import bankster.client.psd2.Psd2Dtos.Balance;
import bankster.client.psd2.Psd2Dtos.ConsentResponse;
import bankster.client.psd2.Psd2Dtos.TransactionsResponse;

/**
 * Exercises the AIS client against a stub ASPSP that replies with the payloads
 * the Berlin Group XS2A spec defines, so request shaping and response parsing
 * are covered without needing a PSU to sign a consent interactively.
 */
class Psd2AisServiceTest {

    private HttpServer server;
    private Psd2AisService service;

    /** Last request seen per path, so tests can assert on headers and query strings. */
    private final Map<String, RecordedRequest> requests = new ConcurrentHashMap<>();

    private final List<Stub> stubs = new ArrayList<>();

    private record RecordedRequest(String query, Map<String, String> headers, String body) {
    }

    private record Stub(String path, int status, String body) {
    }

    @BeforeEach
    void startStubAspsp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        Psd2Properties properties = new Psd2Properties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setConsentRedirectUri("http://localhost:8099/psd2/callback/consent");
        properties.setTransactionHistoryDays(30);
        properties.setConsentValidityDays(90);
        properties.setFrequencyPerDay(4);

        WebClient webClient = WebClient.builder().baseUrl(properties.getBaseUrl()).build();
        service = new Psd2AisService(webClient, properties);
    }

    @AfterEach
    void stopStubAspsp() {
        server.stop(0);
    }

    @Test
    void createConsentSendsRedirectHeadersAndReadsScaLink() {
        stub("/v1/consents", 201, """
                {
                  "consentStatus": "received",
                  "consentId": "abc-123",
                  "_links": {
                    "scaRedirect": { "href": "https://aspsp.example/sign/abc-123" }
                  }
                }
                """);

        ConsentResponse consent = service.createConsent("token-1");

        assertEquals("abc-123", consent.consentId());
        assertEquals("received", consent.consentStatus());
        assertEquals("https://aspsp.example/sign/abc-123", consent.scaRedirectUrl());

        RecordedRequest request = requests.get("/v1/consents");
        assertEquals("Bearer token-1", request.headers().get("authorization"));
        assertEquals("http://localhost:8099/psd2/callback/consent", request.headers().get("tpp-redirect-uri"));
        assertEquals("true", request.headers().get("tpp-redirect-preferred"));
        assertNotNull(request.headers().get("x-request-id"), "XS2A requires a per-request correlation id");
        assertTrue(request.body().contains("\"availableAccounts\":\"allAccounts\""));
        assertTrue(request.body().contains("\"frequencyPerDay\":4"));
    }

    @Test
    void accountsAreParsedAndConsentIdIsSent() {
        stub("/v1/accounts", 200, """
                {
                  "accounts": [
                    {
                      "resourceId": "res-1",
                      "iban": "EE717700771001735865",
                      "ownerName": "Liis-Mari Mannik",
                      "currency": "EUR",
                      "name": "Account 1",
                      "product": "Multi currency account",
                      "cashAccountType": "CACC",
                      "status": "enabled"
                    }
                  ]
                }
                """);

        List<Account> accounts = service.accounts("token-1", "abc-123");

        assertEquals(1, accounts.size());
        assertEquals("res-1", accounts.get(0).resourceId());
        assertEquals("Account 1", accounts.get(0).displayName());
        assertEquals("abc-123", requests.get("/v1/accounts").headers().get("consent-id"));
    }

    @Test
    void accountWithoutNicknameFallsBackToIban() {
        stub("/v1/accounts", 200, """
                {"accounts":[{"resourceId":"res-2","iban":"EE277700771001735881","currency":"EUR"}]}
                """);

        List<Account> accounts = service.accounts("token-1", "abc-123");

        assertEquals("EE277700771001735881", accounts.get(0).displayName());
    }

    @Test
    void balancesAreParsed() {
        stub("/v1/accounts/res-1/balances", 200, """
                {
                  "balances": [
                    { "balanceType": "interimAvailable", "balanceAmount": { "currency": "EUR", "amount": "1234.56" } }
                  ]
                }
                """);

        List<Balance> balances = service.balances("token-1", "abc-123", "res-1");

        assertEquals(1, balances.size());
        assertEquals("interimAvailable", balances.get(0).balanceType());
        assertEquals("1234.56", balances.get(0).balanceAmount().amount());
    }

    @Test
    void recentTransactionsRequestsTheConfiguredWindowAndParsesBothLists() {
        stub("/v1/accounts/res-1/transactions", 200, """
                {
                  "account": { "iban": "EE717700771001735865" },
                  "transactions": {
                    "booked": [
                      {
                        "entryReference": "e-1",
                        "bookingDate": "2026-09-01",
                        "transactionAmount": { "currency": "EUR", "amount": "-25.00" },
                        "creditorName": "Coffee Bar",
                        "remittanceInformationUnstructured": "Card payment"
                      }
                    ],
                    "pending": [
                      {
                        "reservationDate": "2026-09-10",
                        "transactionAmount": { "currency": "EUR", "amount": "-9.99" },
                        "remittanceInformationUnstructured": "Reserved"
                      }
                    ]
                  }
                }
                """);

        TransactionsResponse response = service.recentTransactions("token-1", "abc-123", "res-1");

        assertEquals("EE717700771001735865", response.account().iban());
        assertEquals(1, response.booked().size());
        assertEquals(LocalDate.of(2026, 9, 1), response.booked().get(0).bookingDate());
        assertEquals("Coffee Bar", response.booked().get(0).counterparty());
        assertEquals("Card payment", response.booked().get(0).description());
        assertEquals(1, response.pending().size());

        String query = requests.get("/v1/accounts/res-1/transactions").query();
        LocalDate today = LocalDate.now();
        assertTrue(query.contains("bookingStatus=both"), query);
        assertTrue(query.contains("dateTo=" + today), query);
        assertTrue(query.contains("dateFrom=" + today.minusDays(30)), query);
    }

    @Test
    void emptyTransactionBlockYieldsEmptyListsRatherThanNull() {
        stub("/v1/accounts/res-1/transactions", 200, """
                {"account":{"iban":"EE717700771001735865"}}
                """);

        TransactionsResponse response = service.recentTransactions("token-1", "abc-123", "res-1");

        assertTrue(response.booked().isEmpty());
        assertTrue(response.pending().isEmpty());
    }

    @Test
    void tppMessageIsSurfacedAsPsd2Exception() {
        stub("/v1/accounts", 400, """
                {"tppMessages":[{"category":"ERROR","code":"CONSENT_UNKNOWN","text":"We were not able to match the provided consent-id"}]}
                """);

        Psd2Exception exception =
                assertThrows(Psd2Exception.class, () -> service.accounts("token-1", "stale-consent"));

        assertEquals(400, exception.getStatus());
        assertTrue(exception.getMessage().contains("CONSENT_UNKNOWN"), exception.getMessage());
        assertTrue(exception.getMessage().contains("not able to match"), exception.getMessage());
    }

    @Test
    void nonXs2aErrorBodyStillFailsWithTheHttpStatus() {
        stub("/v1/accounts", 503, "<html>gateway down</html>");

        Psd2Exception exception =
                assertThrows(Psd2Exception.class, () -> service.accounts("token-1", "abc-123"));

        assertEquals(503, exception.getStatus());
    }

    @Test
    void consentStatusIsRead() {
        stub("/v1/consents/abc-123/status", 200, "{\"consentStatus\":\"valid\"}");

        assertEquals("valid", service.consentStatus("token-1", "abc-123"));
    }

    private void stub(String path, int status, String body) {
        stubs.add(new Stub(path, status, body));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        Map<String, String> headers = new ConcurrentHashMap<>();
        exchange.getRequestHeaders()
                .forEach((name, values) -> headers.put(name.toLowerCase(), String.join(",", values)));
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.put(path, new RecordedRequest(
                exchange.getRequestURI().getQuery() == null ? "" : exchange.getRequestURI().getQuery(),
                headers, body));

        Stub stub = stubs.stream().filter(s -> s.path().equals(path)).findFirst().orElse(null);
        byte[] payload = (stub == null ? "{}" : stub.body()).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(stub == null ? 404 : stub.status(), payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
