package bankster.client;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import bankster.client.domain.Category;
import bankster.client.domain.Transaction;
import bankster.client.domain.TransactionRepository;

/**
 * @author joudahidri
 *
 */
@SpringBootApplication
public class ClientApplication {

	@Autowired
	TransactionRepository transactionRepository;

	public static void main(String[] args) {
		SpringApplication.run(ClientApplication.class, args);
	}

	/**
	 * This method inserts a list of transactions as test data
	 * 
	 * @return the list of transactions as Bean
	 */
	@Bean
	InitializingBean sendDatabase() {
		return () -> {
			transactionRepository.save(new Transaction(-2.95, Category.FOOD));
			transactionRepository.save(new Transaction(-63.42, Category.UTILITIES));
			transactionRepository.save(new Transaction(-10.00, Category.HOBBIES));
			transactionRepository.save(new Transaction(-19.90, Category.HOBBIES));
			transactionRepository.save(new Transaction(-6.06, Category.FOOD));
			transactionRepository.save(new Transaction(-9.99, Category.HOBBIES));
			transactionRepository.save(new Transaction(-200.00, Category.CASH));
			transactionRepository.save(new Transaction(-5.89, Category.FOOD));
			transactionRepository.save(new Transaction(-4.50, Category.SHOPPING));
			transactionRepository.save(new Transaction(-1.75, Category.FOOD));
			transactionRepository.save(new Transaction(-1.75, Category.FOOD));
			transactionRepository.save(new Transaction(-650.00, Category.UTILITIES));
			transactionRepository.save(new Transaction(-18.88, Category.FOOD));
			transactionRepository.save(new Transaction(-50.00, Category.CASH));
			transactionRepository.save(new Transaction(-3.95, Category.OTHER));
			transactionRepository.save(new Transaction(-5.89, Category.FOOD));
		};
	}
}
