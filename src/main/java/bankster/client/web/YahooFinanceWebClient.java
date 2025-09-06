package bankster.client.web;

import java.util.ArrayList;
import java.util.List;

import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.json.JSONObject;
import org.json.JSONArray;

public class YahooFinanceWebClient {
    private final WebClient webClient;

    public YahooFinanceWebClient() {
        this.webClient = WebClient.builder()
                .baseUrl("https://query1.finance.yahoo.com")
                .defaultHeader("User-Agent", "Mozilla/5.0")
                .build();
    }

    public List<Candle> fetchOHLCV(String symbol, String interval, String range) {

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

