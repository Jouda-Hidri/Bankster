package bankster.client.web;

import lombok.val;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.web.reactive.function.client.WebClient;

public class FinnHubWebClient {
    private final WebClient webClient;

    public FinnHubWebClient() {
        this.webClient = WebClient.builder()
                .baseUrl("https://finnhub.io")
                .build();
    }

    public Map<LocalDate, List<String>> fetchNews(String symbol, LocalDate from, LocalDate to) {
        val token = "d328ve9r01qn0gi2u9q0d328ve9r01qn0gi2u9qg";
        String response = webClient.get()
                .uri("/api/v1/company-news?symbol={symbol}&from={from}&to={to}&token={token}",symbol, from, to, token)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        // Parse as JSONArray directly
        JSONArray items = new JSONArray(response);

        Map<LocalDate, List<String>> newsByDate = new HashMap<>();

        for (int i = 0; i < items.length(); i++) {
            JSONObject article = items.getJSONObject(i);

            // Extract headline
            String headline = article.optString("headline"); // Finnhub uses "headline" field

            // Extract timestamp
            long timestamp = article.optLong("datetime", 0); // Finnhub uses "datetime" in seconds
            LocalDate date = Instant.ofEpochSecond(timestamp)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate();

            // Add to map
            newsByDate.computeIfAbsent(date, d -> new ArrayList<>()).add(headline);
        }

        return newsByDate;
    }


}
