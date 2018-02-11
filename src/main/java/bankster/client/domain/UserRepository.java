package bankster.client.domain;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * User Repository
 *
 * @author joudahidri
 */
public interface UserRepository extends JpaRepository<User, Long> {

	User findByUsername(String username);
}
