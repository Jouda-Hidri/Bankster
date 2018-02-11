package bankster.client.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.core.userdetails.UserDetailsService;

@Configuration
@EnableWebSecurity
@Order(-10)
public class WebSecurityConfigurer extends WebSecurityConfigurerAdapter {

	@Autowired
	UserDetailsService userDetailsService;

	/**
	 * Configure HTTP Security to accept only authenticated users
	 */

	@Override
	protected void configure(HttpSecurity http) throws Exception {
		http.csrf().disable() // disable CSRF protection
				.authorizeRequests().anyRequest().authenticated() // accept authenticated requests
				.and().formLogin().loginPage("/login").permitAll(); // accept login requests
	}

	/**
	 * Configure the Authentication Manager to read user details from the Repository
	 */
	@Override
	public void configure(AuthenticationManagerBuilder auth) throws Exception {
		auth.userDetailsService(userDetailsService);
	}
}
