package bankster.client.web;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import bankster.client.domain.Category;
import bankster.client.domain.Transaction;
import bankster.client.service.CalculatorService;

/**
 * This controller takes care of handling the view requests to display the
 * overview of transactions
 * 
 * @author joudahidri
 *
 */
@Controller
public class TransactionController {

	@Autowired
	CalculatorService calculatorService;

	/**
	 * This method gets all transactions
	 * {@link bankster.client.service.CalculatorService#getListTransactions()} and
	 * adds them to the model to be sent to the view
	 * 
	 * @param the model where the transactions list will be held
	 * @return the view name
	 */
	@RequestMapping(value = "/list", method = RequestMethod.GET)
	public String getTransactions(Model model) {
		List<Transaction> listTransactions = calculatorService.getListTransactions();
		model.addAttribute("listTransactions", listTransactions);
		return "transactions";
	}

	/**
	 * This method gets all transaction sum amounts by category
	 * {@link bankster.client.service.CalculatorService#getSumAmountByCategory()}
	 * and adds them to the model to be sent to the view
	 * 
	 * @param the model where the transaction sums will be held
	 * @return the view name
	 */
	@RequestMapping(value = "/sum", method = RequestMethod.GET)
	public String getEvaluation(Model model) {
		Map<Category, Double> sumAmountByCategory = calculatorService.getSumAmountByCategory();
		model.addAttribute("sumAmountByCategory", sumAmountByCategory);
		return "evaluation";
	}

}