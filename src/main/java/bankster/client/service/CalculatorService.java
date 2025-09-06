//package bankster.client.service;
//
//import org.springframework.stereotype.Service;
//
///**
// * The Service takes care of all the calculations
// *
// * @author joudahidri
// */
//@Service
//public class CalculatorService {
////
////	@Autowired
////	TransactionRepository transactionRepository;
//
//	/**
//	 * This method retrieves all the transactions from the repository
//	 *
//	 * @return transactions list
//	 */
////	public List<Transaction> getListTransactions() {
////		List<Transaction> listTransactions = List.of(); //transactionRepository.findAllByOrderByIdAsc();
////		return listTransactions;
////	}
//
//	/**
//	 * This method retrieves all the transactions from the repository, groups them
//	 * by category and calculates the total amount per category
//	 *
//	 * @return categories with their total amounts
//	 */
////	public Map<Category, Double> getSumAmountByCategory() {
////		List<Transaction> listTransactions = List.of(); //transactionRepository.findAllByOrderByIdAsc();
////		if(listTransactions == null || listTransactions.isEmpty()) {
////			return new HashMap<Category, Double>();
////		}
////		Map<Category, Double> result = listTransactions.stream()
////				.collect(Collectors.groupingBy(t -> t.getCategory(), Collectors.summingDouble(Transaction::getAmount)));
////		return result;
////	}
//
//}