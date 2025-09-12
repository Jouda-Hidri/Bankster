package bankster.client.web;


import bankster.client.service.StockService;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * This controller takes care of handling the view requests to display the
 * overview of transactions
 *
 * @author joudahidri
 */
@Controller
public class TransactionController {

    @Autowired
    private StockService service;

    @GetMapping("/stock")
    public String getStock(Model model) {
        YahooFinanceWebClient yahooClient = new YahooFinanceWebClient();
        List<List<Candle>> stocks = yahooClient.fetchChart(
                List.of("AAPL", "MSFT", "GOOG", "AMZN"),
                "1d",
                "6mo");
        List<Candle> candles = stocks.get(0);
        FinnHubWebClient finnHubWebClient = new FinnHubWebClient();
        Map<LocalDate, List<String>> news = finnHubWebClient.fetchNews(
                "AAPL",
                candles.get(0).getDate(),
                candles.get(candles.size() - 1).getDate());
//        Map<LocalDate, List<String>> news = yahooClient.fetchNewsByDate("AAPL");
        FinbertWebClient finbertClient = new FinbertWebClient();
        Map<LocalDate, Double> sentiment = finbertClient.getDailySentiment(news);
        service.lstmForecast(candles, 50, sentiment);
        long correctCount = candles.stream()
                .filter(Candle::isLstm)
                .count();
        long total = candles.stream()
                .filter(c -> c.getLstmForecast() != null)
                .count();
        double accuracy = 100.0 * correctCount / total;
        double totalPnl = stocks.get(0).stream().mapToDouble(Candle::getPnl).sum();
        model.addAttribute("accuracy", accuracy);
        model.addAttribute("candles", candles);
        model.addAttribute("pnl", totalPnl);
        return "stock";
    }

}