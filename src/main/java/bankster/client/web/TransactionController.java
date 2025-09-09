package bankster.client.web;


import bankster.client.service.StockService;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * This controller takes care of handling the view requests to display the
 * overview of transactions
 *
 * @author joudahidri
 */
@Controller
public class TransactionController {

//	@Autowired
//	CalculatorService calculatorService;

    @Autowired
    private StockService service;

    @RequestMapping(value = "/list", method = RequestMethod.GET)
    public String getTransactions(Model model) {
//		List<Transaction> listTransactions = calculatorService.getListTransactions();
//		model.addAttribute("listTransactions", listTransactions);
        return "transactions";
    }

    @RequestMapping(value = "/sum", method = RequestMethod.GET)
    public String getEvaluation(Model model) {
//		Map<Category, Double> sumAmountByCategory = calculatorService.getSumAmountByCategory();
//		model.addAttribute("sumAmountByCategory", sumAmountByCategory);
        return "evaluation";
    }

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
//        double rsi = service.computeRSI(candles, 50);
//        double volatility = service.computeVolatility(candles, 50);
//        model.addAttribute("rsi", rsi);
//        model.addAttribute("volatility", volatility);
        return "stock";
    }

}