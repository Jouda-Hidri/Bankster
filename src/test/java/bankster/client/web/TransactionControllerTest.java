package bankster.client.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import bankster.client.web.TransactionController;

@RunWith(SpringRunner.class)
@SpringBootTest
public class TransactionControllerTest {

	private MockMvc mockMvc;

	@Autowired
	private TransactionController controller;

	@Before
	public void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
	}

	/**
	 * Test that the Get request for the controller method
	 * {@link bankster.client.web.TransactionController#getTransactions()}
	 * returns the correct status and the correct view
	 */
	@Test
	public void testGetTransactions() throws Exception {
		this.mockMvc.perform(get("/list")).andExpect(status().isOk()).andExpect(view().name("transactions"))
				.andDo(print());
	}

	/**
	 * Test that the Get request for the controller method
	 * {@link bankster.client.web.TransactionController#getEvaluation()}
	 * returns the correct status and the correct view
	 */
	@Test
	public void testGetSumAmounts() throws Exception {
		this.mockMvc.perform(get("/sum")).andExpect(status().isOk()).andExpect(view().name("evaluation"))
				.andDo(print());
	}
}
