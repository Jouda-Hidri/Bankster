package bankster.client.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import bankster.client.payments.cards.AcquirerProcessor;
import bankster.client.payments.core.EventHandler;
import bankster.client.payments.core.Outbox;
import bankster.client.payments.demo.DemoScenario;
import bankster.client.payments.ledger.Ledger;
import bankster.client.payments.orchestration.PaymentRouter;
import bankster.client.payments.rails.SepaRouter;
import bankster.client.payments.recon.ReconciliationService;

/**
 * Starts the application context and runs the whole demo scenario through it.
 *
 * <p>A single integration test, rather than many, because the unit tests already
 * cover the behaviour. What this one adds is that the Spring wiring is correct and
 * that the modules hold together when driven end to end — in particular that the
 * ledger still balances and its hash chain still verifies after every path in the
 * system has been exercised, which is the property most likely to be broken by a
 * change somewhere else.
 *
 * <p>Demo seeding is disabled so the scenario runs exactly once, under the test's
 * control.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "payments.seed-demo-data=false",
        "payments.acquirer-fraud-rate-basis-points=5"
})
class PaymentsWiringTest {

    @Autowired
    private DemoScenario scenario;

    @Autowired
    private Ledger ledger;

    @Autowired
    private PaymentRouter paymentRouter;

    @Autowired
    private SepaRouter sepaRouter;

    @Autowired
    private ReconciliationService reconciliation;

    @Autowired
    private Outbox outbox;

    @Autowired
    private List<AcquirerProcessor> processors;

    @Autowired
    private List<EventHandler> eventHandlers;

    @Test
    void bothAcquirersAreRegisteredWithTheRouter() {
        assertEquals(2, processors.size());
        assertEquals(2, paymentRouter.processors().size(),
                "adding an acquirer should be a matter of declaring a bean");
        assertTrue(paymentRouter.processors().stream()
                .anyMatch(processor -> processor.id().equals("northbound")));
        assertTrue(paymentRouter.processors().stream()
                .anyMatch(processor -> processor.id().equals("meridian")));
    }

    @Test
    void configuredLimitsReachTheRailRouter() {
        assertEquals(Money.of("EUR", "100000.00"), sepaRouter.instantTransactionLimit());
        assertEquals(Money.of("EUR", "250000.00"), sepaRouter.highValueThreshold());
    }

    @Test
    void everyEventHandlerIsSubscribedToTheOutbox() {
        assertFalse(eventHandlers.isEmpty());

        outbox.append("payment.captured", "subject-1", java.util.Map.of());
        outbox.drain();

        // Each handler keeps its own dedupe log, so each must have been subscribed.
        assertTrue(eventHandlers.stream()
                .filter(handler -> handler.handles("payment.captured"))
                .allMatch(handler -> !outbox.processedBy(handler.name()).isEmpty()));
    }

    @Test
    void theWholeScenarioRunsAndLeavesTheLedgerSound() {
        DemoScenario.DemoSummary summary = scenario.run();

        assertFalse(summary.steps().isEmpty());
        assertTrue(ledger.size() > 0);

        // The property most likely to be broken by a change elsewhere.
        assertTrue(ledger.trialBalance().balances(),
                "debits must equal credits in every currency after every path has run");
        assertTrue(ledger.verifyIntegrity().intact(), "the journal must not have been rewritten");
        assertTrue(reconciliation.checkLedger(java.time.Instant.now()).isEmpty());
    }

    @Test
    void theScenarioProducesTheMessagesAndOutcomesTheConsoleShows() {
        DemoScenario.DemoSummary summary = scenario.run();

        assertTrue(summary.pain001().contains("CstmrCdtTrfInitn"));
        assertTrue(summary.camt053().contains("BkToCstmrStmt"));
        assertTrue(summary.mt103().contains(":32A:"));
        assertTrue(summary.downgradedTransferExplanation().isPresent(),
                "the scenario is built around a transfer that cannot go instant");
        assertTrue(summary.chargebackOutcome().isPresent());
        assertTrue(summary.reconciliationSummary().isPresent());
    }

    @Test
    void runningTheScenarioTwiceKeepsTheBooksBalanced() {
        scenario.run();
        int afterFirst = ledger.size();
        scenario.run();

        assertTrue(ledger.size() > afterFirst, "the second run adds more history");
        assertTrue(ledger.trialBalance().balances());
        assertTrue(ledger.verifyIntegrity().intact());
    }
}
