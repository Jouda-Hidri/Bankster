package bankster.client.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.json.JSONObject;
import org.json.JSONArray;

public class YahooFinanceWebClient {
    private final WebClient webClient;

    public YahooFinanceWebClient() {
        this.webClient = WebClient.builder()
                .baseUrl("https://query1.finance.yahoo.com")
                .build();
    }

    public Map<LocalDate, List<String>> fetchNewsByDate(String symbol) {
        JSONArray newsItems = fetchNews(symbol);
        Map<LocalDate, List<String>> newsByDate = new HashMap<>();

        for (int i = 0; i < newsItems.length(); i++) {
            JSONObject item = newsItems.getJSONObject(i);
            String headline = item.getString("title");
            long ts = item.getLong("providerPublishTime");

            LocalDate date = toDate(ts);
            newsByDate.computeIfAbsent(date, k -> new ArrayList<>()).add(headline);
        }

        return newsByDate;
    }

    public  List<List<Candle>> fetchChart(List<String> symbols, String interval, String range) {
        List<List<Candle>> matrice = new ArrayList<>();
        for(String symbol : symbols) {
            matrice.add(fetchChart(symbol, interval, range));
        }
        return matrice;
    }

    // Fetch raw news JSON for a given stock symbol
    public JSONArray fetchNews(String symbol) {
        String url = "/v2/finance/news?symbols=" + symbol;

        String response = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        JSONObject json = new JSONObject(response);
        JSONArray items = json.getJSONObject("finance").getJSONArray("result")
                .getJSONObject(0)
                .getJSONArray("items");

        return items;
    }
    // Convert timestamp to LocalDate

    private LocalDate toDate(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds)
                .atZone(ZoneId.systemDefault())
                .toLocalDate();
    }
    // Map news by date

    private List<Candle> fetchChart(String symbol, String interval, String range) {
        List<Candle> candles = new ArrayList<>();
        try {
            String response = webClient.get()
                    .uri("/v8/finance/chart/{symbol}?interval={interval}&range={range}", symbol, interval, range)
                    .retrieve()
                    .bodyToMono(String.class).block();
            JSONObject json = new JSONObject(response);
            JSONObject result = json.getJSONObject("chart")
                    .getJSONArray("result")
                    .getJSONObject(0);
            JSONArray timestamps = result.getJSONArray("timestamp");
            JSONObject indicators = result.getJSONObject("indicators")
                    .getJSONArray("quote")
                    .getJSONObject(0);
            JSONArray opens = indicators.getJSONArray("open");
            JSONArray closes = indicators.getJSONArray("close");
            for (int i = 0; i < timestamps.length(); i++) {
                long ts = timestamps.getLong(i);
                Double open = opens.isNull(i) ? null : opens.getDouble(i);
                Double close = closes.isNull(i) ? null : closes.getDouble(i);
                candles.add(new Candle(ts, open, close));
            }
        } catch (WebClientResponseException e) {
            System.err.println("Error: " + e.getRawStatusCode() + " " + e.getResponseBodyAsString());
        }
        return candles;
    }
}

