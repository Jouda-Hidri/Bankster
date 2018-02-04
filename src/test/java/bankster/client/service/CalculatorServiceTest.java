package bankster.client.service;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import bankster.client.domain.Category;
import bankster.client.domain.Transaction;
import bankster.client.domain.TransactionRepository;

public class CalculatorServiceTest {

	@InjectMocks
	CalculatorService calculatorService;

	@Mock
	TransactionRepository transactionRepository;

	@Before
	public void setUp() {
		MockitoAnnotations.initMocks(this);
	}

	/**
	 * Test that the service method {@link bankster.client.service.CalculatorService#getSumAmountByCategory()}
	 * returns the correct total amount per category for a given list of
	 * transactions where all amounts are negative
	 */
	@Test
	public void testGetSumAmountByCategoryWhenNegativeAmounts() {
		List<Transaction> listTransactions = new ArrayList<Transaction>();
		listTransactions.add(new Transaction(-3, Category.FOOD));
		listTransactions.add(new Transaction(-60, Category.UTILITIES));
		listTransactions.add(new Transaction(-10.00, Category.HOBBIES));
		listTransactions.add(new Transaction(-20, Category.HOBBIES));
		listTransactions.add(new Transaction(-6, Category.FOOD));
		listTransactions.add(new Transaction(-10, Category.HOBBIES));

		when(transactionRepository.findAllByOrderByIdAsc()).thenReturn(listTransactions);

		Map<Category, Double> expectation = new HashMap<Category, Double>();
		expectation.put(Category.UTILITIES, -60.0);
		expectation.put(Category.FOOD, -9.0);
		expectation.put(Category.HOBBIES, -40.0);

		Map<Category, Double> result = calculatorService.getSumAmountByCategory();

		assertThat(result, is(expectation));

	}

	/**
	 * Test that the service method {@link bankster.client.service.CalculatorService#getSumAmountByCategory()}
	 * returns the correct total amount per category for a given list of
	 * transactions with positive and negative amounts
	 */
	@Test
	public void testGetSumAmountByCategoryWhenPositivAndNegativeAmounts() {
		List<Transaction> listTransactions = new ArrayList<Transaction>();
		listTransactions.add(new Transaction(-3, Category.FOOD));
		listTransactions.add(new Transaction(-60, Category.UTILITIES));
		listTransactions.add(new Transaction(10.00, Category.HOBBIES));
		listTransactions.add(new Transaction(-20, Category.HOBBIES));
		listTransactions.add(new Transaction(6, Category.FOOD));
		listTransactions.add(new Transaction(-10, Category.HOBBIES));

		when(transactionRepository.findAllByOrderByIdAsc()).thenReturn(listTransactions);

		Map<Category, Double> expectation = new HashMap<Category, Double>();
		expectation.put(Category.UTILITIES, -60.0);
		expectation.put(Category.FOOD, 3.0);
		expectation.put(Category.HOBBIES, -20.0);

		Map<Category, Double> result = calculatorService.getSumAmountByCategory();

		assertThat(result, is(expectation));

	}
	
	/**
	 * Test that the service method {@link bankster.client.service.CalculatorService#getSumAmountByCategory()}
	 * returns an empty map of amount by category when the list of transactions is
	 * empty
	 */
	@Test
	public void testGetSumAmountByCategoryWhenEmptyListTransactions() {
		List<Transaction> listTransactions = new ArrayList<Transaction>();
		
		when(transactionRepository.findAllByOrderByIdAsc()).thenReturn(listTransactions);
		
		Map<Category, Double> expectation = new HashMap<Category, Double>();
		
		Map<Category, Double> result = calculatorService.getSumAmountByCategory();
		
		assertThat(result, is(expectation));
		
	}
	
	/**
	 * Test that the service method {@link bankster.client.service.CalculatorService#getSumAmountByCategory()}
	 * returns an empty map of amount by category when the list of transactions is
	 * null
	 */
	@Test
	public void testGetSumAmountByCategoryWhenNullListTransactions() {
		when(transactionRepository.findAllByOrderByIdAsc()).thenReturn(null);
		
		Map<Category, Double> expectation = new HashMap<Category, Double>();
		
		Map<Category, Double> result = calculatorService.getSumAmountByCategory();
		
		assertThat(result, is(expectation));
		
	}

}
