package bankster.client.web;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.reactive.function.client.WebClient;

public class FinbertWebClient {

    private final WebClient client = WebClient.create("http://localhost:8001");

    public Map<LocalDate, Double> getDailySentiment(Map<LocalDate, List<String>> newsByDate) {
        Map<LocalDate, Double> dailySentiment = new HashMap<>();

        for (Map.Entry<LocalDate, List<String>> entry : newsByDate.entrySet()) {
            LocalDate date = entry.getKey();
            List<String> headlines = entry.getValue();

            if (headlines.isEmpty()) {
                continue;
            }

            // Compute average sentiment for the day
            double sum = 0;
            for (String headline : headlines) {
                sum += getSentimentScore(headline);
            }
            double avgSentiment = sum / headlines.size();
            dailySentiment.put(date, avgSentiment);
        }

        return dailySentiment;
    }

    private int getSentimentScore(String headline) {
        SentimentRequest req = new SentimentRequest(headline);
        SentimentResponse resp = client.post()
                .uri("/sentiment")
                .bodyValue(req)
                .retrieve()
                .bodyToMono(SentimentResponse.class)
                .block();
        return resp.score; // -1, 0, or 1
    }

    record SentimentRequest(String text) {
    }

    record SentimentResponse(String text, String sentiment, int score, double[] probabilities) {
    }

}
