package bankster.client;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @author joudahidri
 *
 */
@SpringBootApplication(exclude = {
        org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration.class
})
public class ClientApplication {

	public static void main(String[] args) {
		SpringApplication.run(ClientApplication.class, args);
	}
}
