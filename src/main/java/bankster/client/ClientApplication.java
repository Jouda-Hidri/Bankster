package bankster.client;

import java.io.IOException;
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
import bankster.client.domain.User;
import bankster.client.domain.UserRepository;

/**
 * @author joudahidri
 *
 */
@SpringBootApplication
public class ClientApplication extends org.springframework.boot.web.support.SpringBootServletInitializer{

	private static final Logger log = LoggerFactory.getLogger(ClientApplication.class);

	@Autowired
	TransactionRepository transactionRepository;

	@Autowired
	UserRepository userRepository;

	public static void main(String[] args) {
		SpringApplication.run(ClientApplication.class, args);
	}

	/**
	 * This method inserts a test user
	 */
	@Bean
	InitializingBean sendDatabase() {
		return () -> {
			userRepository.save(new User("user", "password"));
		};
	}

	@Bean
	public RestTemplate restTemplate(RestTemplateBuilder builder) {
		return builder.build();
	}

	/**
	 * This command runner extracts transaction data from the Rest API and saves
	 * them in the repository
	 */

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
					Transaction save = transactionRepository
							.save(new Transaction(Double.parseDouble(transactionsAmount), Category.OTHER));
					log.info("Transaction : " + save.getAmount() + " EUR is saved successfully");

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
