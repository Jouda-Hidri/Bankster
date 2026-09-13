package bankster.client.payments.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import bankster.client.payments.PaymentsProperties;

/**
 * Runs {@link DemoScenario} once at start-up so the console has something to show.
 *
 * <p>Failures are logged rather than propagated: a problem in the demo data must
 * not stop the application from starting, because the open banking half of
 * Bankster does not depend on it.
 */
@Component
public class DemoRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoRunner.class);

    private final PaymentsProperties properties;
    private final DemoScenario scenario;

    public DemoRunner(PaymentsProperties properties, DemoScenario scenario) {
        this.properties = properties;
        this.scenario = scenario;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isSeedDemoData()) {
            log.info("Demo data seeding is disabled (payments.seed-demo-data=false)");
            return;
        }
        try {
            scenario.run();
            log.info("Payments console seeded — open http://localhost:8099/payments");
        } catch (RuntimeException e) {
            log.error("The demo scenario failed; the console will be empty", e);
        }
    }
}
