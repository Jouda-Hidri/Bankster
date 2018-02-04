package bankster.client.domain;

import java.util.List;

import org.springframework.data.repository.CrudRepository;

/**
 * The Transaction repository
 * 
 * @author joudahidri
 *
 */
public interface TransactionRepository extends CrudRepository<Transaction, Long> {

	/** Persist Transaction in the repository */
	@SuppressWarnings("unchecked")
	Transaction save(Transaction transaction);

	/** Retrieve all transactions from the repository */
	public List<Transaction> findAllByOrderByIdAsc();
}
