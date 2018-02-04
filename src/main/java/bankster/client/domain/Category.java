package bankster.client.domain;

/**
 * Transaction categories
 * 
 * @author joudahidri
 *
 */

public enum Category {

	/** Income Category : Salary and other income ... */
	INCOME(0),

	/**
	 * Utilities category : Rent, public transport subscription, phone card etc.,
	 */
	UTILITIES(1),

	/** Food category : Supermarket, bar, restaurants etc, */
	FOOD(2),

	/**
	 * Shopping category : Clothes, cleaning & cosmetic, furniture, electronics etc.
	 */
	SHOPPING(3),

	/**
	 * Hobbies category : Books, training etc., sport , etc.
	 */
	HOBBIES(4),

	/**
	 * Travel category : Plane tickets, hotel, expenses abroad etc.
	 */
	TRAVEL(5),

	/**
	 * Health category : Medicines, doctor visit, operation, etc.
	 */
	HEALTH(6),

	/**
	 * Insurance category : House insurance, car insurance, etc.
	 */
	INSURANCE(7),

	/** Cash category : Money withdrawal */
	CASH(8),

	/** All other expenses */
	OTHER(9);

	/**
	 * Category index
	 */
	private int index;

	/**
	 * Constructor to initialize the category index
	 * 
	 * @param index
	 */
	Category(int index) {
		this.index = index;
	}

	/**
	 * Getter method for the attribute Index
	 * 
	 * @return index
	 */
	public int getIndex() {
		return index;
	}
}
