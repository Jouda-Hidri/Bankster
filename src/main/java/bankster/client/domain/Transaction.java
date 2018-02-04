package bankster.client.domain;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;

/**
 * The Entity Transaction
 * 
 * @author joudahidri
 *
 */
@Entity
public class Transaction {

	@Id
	@GeneratedValue(strategy = GenerationType.AUTO)
	@Column(name = "transaction_id")
	private long id;
	/**
	 * Transaction category
	 */
	Category category;

	/** Transaction amount */
	double amount;

	public Category getCategory() {
		return category;
	}

	public void setCategory(Category category) {
		this.category = category;
	}

	public double getAmount() {
		return amount;
	}

	public void setAmount(double amount) {
		this.amount = amount;
	}

	public long getId() {
		return id;
	}

	Transaction() {
		this.setAmount(0);
		this.setCategory(Category.OTHER);
	}

	public Transaction(double amount, Category category) {
		this.setAmount(amount);
		this.setCategory(category);
	}

}
