package bankster.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.ws.rs.client.ClientBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import bankster.client.domain.Category;
import bankster.client.domain.Transaction;
import bankster.client.domain.TransactionRepository;

/**
 * @author joudahidri
 *
 */
@SpringBootApplication
public class ClientApplication {

	private static final Logger log = LoggerFactory.getLogger(ClientApplication.class);

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

	@Bean
	public RestTemplate restTemplate(RestTemplateBuilder builder) {
		return builder.build();
	}

	@Bean
	public CommandLineRunner run(RestTemplate restTemplate) throws Exception {
		return args -> {
			String urlGETList = "https://api.openbankproject.com/obp/v1.2.1/banks/postbank/accounts/f9315a52-330a-470c-8146-c51292c68f9d/public/transactions";
			String json = ClientBuilder.newClient().target(urlGETList).request()
					// .accept(MediaType.APPLICATION_JSON) FIXME
					.get(String.class);
			log.info("json : " + json);
			
			try {
				ObjectMapper mapper = new ObjectMapper();
				Map<String, List<Map<String, Object>>> map = mapper.readValue(json,
						new TypeReference<Map<String, List<Map<String, Object>>>>() {
						});
				log.info("map : " + map);

				List<Map<String, Object>> lisTransactions = map.get("transactions");

				for (Map<String, Object> transaction : lisTransactions) {
					String id = (String) transaction.get("id");
					@SuppressWarnings("unchecked")
					Map<String, Object> transactionsDetails = (Map<String, Object>) transaction.get("details");
					@SuppressWarnings("unchecked")
					Map<String, Object> transactionsValue = (Map<String, Object>) transactionsDetails.get("value");
					String transactionsAmount = (String) transactionsValue.get("amount");
					log.info("transaction_id : " + id + "; amount : " + transactionsAmount);
					//TODO persist Entity transaction in Database
				}

			} catch (JsonGenerationException e) {
				e.printStackTrace();
			} catch (JsonMappingException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		};
	}
}
