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
        List<List<Candle>> stocks = client.fetch(
                List.of("AAPL", "MSFT", "GOOG", "AMZN"),
                "1d",
                "6mo");
        service.lstmForecast(stocks.get(0), 50);
        return "stock";
    }

}