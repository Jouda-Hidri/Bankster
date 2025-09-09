package bankster.client.web;


import bankster.client.service.StockService;

import java.util.List;

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
        YahooFinanceWebClient client = new YahooFinanceWebClient();
        List<Candle> candles = client.fetchOHLCV("AAPL", "1d", "6mo");
        model.addAttribute("candles", candles);
        service.predict(candles);
        long correctCount = candles.stream()
                .filter(Candle::getRandomForest)
                .count();
        long total = candles.stream()
                .filter(c -> c.getRandomForestPrediction() != null)
                .count();
        double accuracy = 100.0 * correctCount / total;
        model.addAttribute("accuracy", accuracy);
        return "stock";
    }

}