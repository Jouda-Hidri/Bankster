package bankster.client.payments.web;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import bankster.client.payments.Money;
import bankster.client.payments.PaymentsProperties;
import bankster.client.payments.cards.ChargebackService;
import bankster.client.payments.cards.Payment;
import bankster.client.payments.core.AuditTrail;
import bankster.client.payments.core.Outbox;
import bankster.client.payments.demo.DemoScenario;
import bankster.client.payments.events.ComplianceEventRecorder;
import bankster.client.payments.events.MerchantWebhookDispatcher;
import bankster.client.payments.ledger.ChartOfAccounts;
import bankster.client.payments.ledger.Ledger;
import bankster.client.payments.orchestration.PaymentRepository;
import bankster.client.payments.orchestration.PaymentRouter;
import bankster.client.payments.orchestration.RoutingStrategy;
import bankster.client.payments.rails.SepaPaymentService;
import bankster.client.payments.rails.SepaRouter;
import bankster.client.payments.recon.ReconciliationService;
import bankster.client.payments.risk.AmlService;
import bankster.client.payments.risk.FraudEngine;
import bankster.client.payments.settlement.SettlementService;

/**
 * A read-mostly console over the payments modules.
 *
 * <p>Its purpose is to make the behaviour inspectable: which rail a transfer took
 * and why it was not the requested one, what a payment's risk score was made of,
 * how a settlement batch decomposes into gross, fees and net, which reconciliation
 * breaks are open and how old they are. Payments systems are judged on whether
 * these questions can be answered after the fact, so the console is a reasonable
 * proxy for whether the design underneath is sound.
 *
 * <p>The handful of write actions exist to make the interesting paths reachable
 * without restarting — taking the instant rail down, changing the routing strategy,
 * running a cut-off, draining the outbox.
 */
@Controller
@RequestMapping("/payments")
public class PaymentsController {

    private final Clock clock;
    private final PaymentsProperties properties;
    private final Ledger ledger;
    private final PaymentRepository payments;
    private final PaymentRouter router;
    private final ChargebackService chargebacks;
    private final SettlementService settlements;
    private final ReconciliationService reconciliation;
    private final SepaPaymentService sepa;
    private final SepaRouter sepaRouter;
    private final AmlService aml;
    private final FraudEngine fraudEngine;
    private final AuditTrail auditTrail;
    private final Outbox outbox;
    private final MerchantWebhookDispatcher webhooks;
    private final ComplianceEventRecorder complianceLog;
    private final DemoScenario demo;

    public PaymentsController(Clock clock, PaymentsProperties properties, Ledger ledger,
                              PaymentRepository payments, PaymentRouter router,
                              ChargebackService chargebacks, SettlementService settlements,
                              ReconciliationService reconciliation, SepaPaymentService sepa,
                              SepaRouter sepaRouter, AmlService aml, FraudEngine fraudEngine,
                              AuditTrail auditTrail, Outbox outbox,
                              MerchantWebhookDispatcher webhooks,
                              ComplianceEventRecorder complianceLog, DemoScenario demo) {
        this.clock = clock;
        this.properties = properties;
        this.ledger = ledger;
        this.payments = payments;
        this.router = router;
        this.chargebacks = chargebacks;
        this.settlements = settlements;
        this.reconciliation = reconciliation;
        this.sepa = sepa;
        this.sepaRouter = sepaRouter;
        this.aml = aml;
        this.fraudEngine = fraudEngine;
        this.auditTrail = auditTrail;
        this.outbox = outbox;
        this.webhooks = webhooks;
        this.complianceLog = complianceLog;
        this.demo = demo;
    }

    @GetMapping
    public String overview(Model model) {
        String currency = properties.getDemoCurrency();
        Ledger.TrialBalance trialBalance = ledger.trialBalance();

        model.addAttribute("paymentCount", payments.size());
        model.addAttribute("transferCount", sepa.all().size());
        model.addAttribute("journalEntries", ledger.size());
        model.addAttribute("trialBalances", trialBalance.balances());
        model.addAttribute("integrity", ledger.verifyIntegrity());
        model.addAttribute("disputeCount", chargebacks.all().size());
        model.addAttribute("batchCount", settlements.all().size());
        model.addAttribute("openBreaks", reconciliation.openBreaks().size());
        model.addAttribute("sarCount", aml.reports().size());
        model.addAttribute("trackedAttempts", fraudEngine.trackedAttempts());
        model.addAttribute("pendingEvents", outbox.pending().size());
        model.addAttribute("deadLetters", outbox.deadLetters().size());

        model.addAttribute("processors", router.processors());
        model.addAttribute("strategy", router.strategy());
        model.addAttribute("strategies", RoutingStrategy.values());
        model.addAttribute("instantLimit", sepaRouter.instantTransactionLimit());
        model.addAttribute("instantAvailable", sepaRouter.isInstantRailAvailable());
        model.addAttribute("highValueThreshold", sepaRouter.highValueThreshold());

        model.addAttribute("unsettledCaptures", reconciliation.unsettledCaptureValue(currency));
        model.addAttribute("refundedValue", reconciliation.refundedValue(currency));
        model.addAttribute("customerFunds", balanceOrZero(
                ChartOfAccounts.customerFunds("cust-liis", currency), currency));
        model.addAttribute("inTransit", balanceOrZero(
                ChartOfAccounts.in(ChartOfAccounts.PAYMENTS_IN_TRANSIT, currency), currency));
        model.addAttribute("suspense", balanceOrZero(
                ChartOfAccounts.in(ChartOfAccounts.SUSPENSE, currency), currency));

        model.addAttribute("demoSummary", demo.lastSummary().orElse(null));
        return "payments/overview";
    }

    // --- Ledger -----------------------------------------------------------

    @GetMapping("/ledger")
    public String ledgerView(Model model) {
        Ledger.TrialBalance trialBalance = ledger.trialBalance();
        model.addAttribute("accounts", ledger.accounts());
        model.addAttribute("balances", ledger.accounts().stream()
                .collect(java.util.stream.Collectors.toMap(
                        account -> account.id(),
                        account -> ledger.balanceOf(account.id()),
                        (a, b) -> a,
                        java.util.LinkedHashMap::new)));
        model.addAttribute("trialBalance", trialBalance);
        model.addAttribute("entries", ledger.entries().reversed());
        model.addAttribute("integrity", ledger.verifyIntegrity());
        return "payments/ledger";
    }

    // --- Card payments ----------------------------------------------------

    @GetMapping("/cards")
    public String cards(Model model) {
        model.addAttribute("payments", payments.all());
        model.addAttribute("disputes", chargebacks.all());
        model.addAttribute("ratios", List.of(
                chargebacks.ratioFor(DemoScenario.MERCHANT_STORE),
                chargebacks.ratioFor(DemoScenario.MERCHANT_TRAVEL)));
        return "payments/cards";
    }

    @GetMapping("/cards/{paymentId}")
    public String cardDetail(@PathVariable String paymentId, Model model) {
        Payment payment = payments.require(paymentId);
        model.addAttribute("payment", payment);
        model.addAttribute("audit", auditTrail.forSubject(paymentId));
        model.addAttribute("journal", ledger.entriesWithReference(paymentId));
        model.addAttribute("disputes", chargebacks.all().stream()
                .filter(dispute -> dispute.paymentId().equals(paymentId))
                .toList());
        return "payments/card-detail";
    }

    // --- Banking rails ----------------------------------------------------

    @GetMapping("/rails")
    public String rails(Model model) {
        model.addAttribute("transfers", sepa.all());
        model.addAttribute("instantLimit", sepaRouter.instantTransactionLimit());
        model.addAttribute("instantAvailable", sepaRouter.isInstantRailAvailable());
        model.addAttribute("unsettled", sepa.unsettled());
        model.addAttribute("demoSummary", demo.lastSummary().orElse(null));
        return "payments/rails";
    }

    // --- Settlement and reconciliation -----------------------------------

    @GetMapping("/settlement")
    public String settlement(Model model) {
        String currency = properties.getDemoCurrency();
        model.addAttribute("batches", settlements.all());
        model.addAttribute("runs", reconciliation.runs().reversed());
        model.addAttribute("agedBreaks", reconciliation.agedBreaks());
        model.addAttribute("blockingBreaks", reconciliation.blockingBreaks());
        model.addAttribute("now", clock.instant());
        model.addAttribute("unsettledCaptures", reconciliation.unsettledCaptureValue(currency));
        model.addAttribute("openDisputes", reconciliation.openDisputes());
        return "payments/settlement";
    }

    // --- Compliance and events -------------------------------------------

    @GetMapping("/compliance")
    public String compliance(Model model) {
        model.addAttribute("reports", aml.reports());
        model.addAttribute("monitored", aml.monitoredTransactions());
        model.addAttribute("complianceRecords", complianceLog.records().reversed());
        model.addAttribute("auditEvents", auditTrail.events().reversed());
        model.addAttribute("auditIntact", auditTrail.verifyIntegrity());
        return "payments/compliance";
    }

    @GetMapping("/events")
    public String events(Model model) {
        model.addAttribute("records", outbox.records().reversed());
        model.addAttribute("deadLetters", outbox.deadLetters());
        model.addAttribute("deliveries", webhooks.deliveries().reversed());
        model.addAttribute("failingTypes", webhooks.failingTypes());
        model.addAttribute("maxAttempts", Outbox.MAX_ATTEMPTS);
        return "payments/events";
    }

    @GetMapping("/messages")
    public String messages(Model model) {
        model.addAttribute("demoSummary", demo.lastSummary().orElse(null));
        return "payments/messages";
    }

    // --- Actions ----------------------------------------------------------

    @PostMapping("/demo/run")
    public String runDemo() {
        demo.run();
        return "redirect:/payments";
    }

    @PostMapping("/settlement/cut-off")
    public String runCutOff() {
        settlements.runCutOff();
        return "redirect:/payments/settlement";
    }

    @PostMapping("/events/drain")
    public String drainOutbox() {
        outbox.drain();
        return "redirect:/payments/events";
    }

    @PostMapping("/events/replay/{eventId}")
    public String replayDeadLetter(@PathVariable String eventId) {
        webhooks.recover();
        outbox.replayDeadLetter(eventId);
        outbox.drain();
        return "redirect:/payments/events";
    }

    @PostMapping("/rails/instant")
    public String toggleInstantRail(@RequestParam boolean available) {
        sepaRouter.setInstantRailAvailable(available);
        return "redirect:/payments/rails";
    }

    @PostMapping("/routing/strategy")
    public String setStrategy(@RequestParam RoutingStrategy strategy) {
        router.setStrategy(strategy);
        return "redirect:/payments";
    }

    @PostMapping("/transfers/settle-due")
    public String settleDue() {
        sepa.settleDueTransfers();
        return "redirect:/payments/rails";
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public String handleFailure(RuntimeException exception, Model model) {
        model.addAttribute("message", exception.getMessage());
        model.addAttribute("details", List.<String>of());
        return "psd2/error";
    }

    private Money balanceOrZero(String accountId, String currency) {
        return ledger.account(accountId).isPresent()
                ? ledger.balanceOf(accountId)
                : Money.zero(currency);
    }
}
