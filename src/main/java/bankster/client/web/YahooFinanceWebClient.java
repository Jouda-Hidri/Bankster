package bankster.client.web;

import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.client.reactive.ReactorClientHttpConnector;
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

    public  List<List<Candle>> fetch(List<String> symbols, String interval, String range) {
        List<List<Candle>> matrice = new ArrayList<>();
        for(String symbol : symbols) {
            matrice.add(fetch(symbol, interval, range));
        }
        return matrice;
    }

    private List<Candle> fetch(String symbol, String interval, String range) {
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

