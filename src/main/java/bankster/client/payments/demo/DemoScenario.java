package bankster.client.payments.demo;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import bankster.client.payments.Money;
import bankster.client.payments.PaymentsProperties;
import bankster.client.payments.cards.AcquirerProcessor;
import bankster.client.payments.cards.CardDetails;
import bankster.client.payments.cards.CardPaymentService;
import bankster.client.payments.cards.CardPaymentService.AuthorizeCommand;
import bankster.client.payments.cards.CardPaymentService.PaymentResult;
import bankster.client.payments.cards.Chargeback;
import bankster.client.payments.cards.ChargebackReason;
import bankster.client.payments.cards.ChargebackService;
import bankster.client.payments.cards.IssuerSimulator;
import bankster.client.payments.cards.Pan;
import bankster.client.payments.cards.SimulatedAcquirerProcessor;
import bankster.client.payments.cards.TokenVault;
import bankster.client.payments.core.Outbox;
import bankster.client.payments.events.MerchantWebhookDispatcher;
import bankster.client.payments.ledger.ChartOfAccounts;
import bankster.client.payments.ledger.Ledger;
import bankster.client.payments.orchestration.PaymentRouter;
import bankster.client.payments.rails.BankStatement;
import bankster.client.payments.rails.BankStatement.StatementEntry;
import bankster.client.payments.rails.CreditTransfer;
import bankster.client.payments.rails.Iban;
import bankster.client.payments.rails.Iso20022;
import bankster.client.payments.rails.PartyDetails;
import bankster.client.payments.rails.SepaPaymentService;
import bankster.client.payments.rails.SepaPaymentService.InitiateTransferCommand;
import bankster.client.payments.rails.SepaPaymentService.TransferResult;
import bankster.client.payments.rails.SepaRouter;
import bankster.client.payments.rails.SwiftService;
import bankster.client.payments.rails.TransferBookkeeper;
import bankster.client.payments.recon.ReconciliationBreak.BreakType;
import bankster.client.payments.recon.ReconciliationRun;
import bankster.client.payments.recon.ReconciliationService;
import bankster.client.payments.risk.AmlService;
import bankster.client.payments.risk.CustomerProfile;
import bankster.client.payments.risk.KycStatus;
import bankster.client.payments.settlement.ProcessorSettlementReport;
import bankster.client.payments.settlement.ProcessorSettlementReport.Line;
import bankster.client.payments.settlement.SettlementBatch;
import bankster.client.payments.settlement.SettlementService;

/**
 * Drives every path in the payments modules once, so that the console has real
 * data to show and the behaviour can be inspected rather than described.
 *
 * <p>The scenarios are chosen to be the interesting ones rather than the happy
 * ones. An approved payment demonstrates almost nothing; a payment that is
 * downgraded from instant to batch because the beneficiary's bank is unreachable, a
 * capture whose ledger write fails and is compensated, a chargeback defeated by
 * 3-D Secure liability shift, and a settlement held because reconciliation found a
 * break — those are the cases the design exists for.
 */
@Service
public class DemoScenario {

    private static final Logger log = LoggerFactory.getLogger(DemoScenario.class);

    public static final String MERCHANT_STORE = "acme-store";
    public static final String MERCHANT_TRAVEL = "globex-travel";
    public static final String CUSTOMER = "cust-liis";

    /** Debtor account the demo sends transfers from. */
    public static final String DEBTOR_IBAN = "EE717700771001735865";

    private final PaymentsProperties properties;
    private final Ledger ledger;
    private final TokenVault tokenVault;
    private final IssuerSimulator issuer;
    private final CardPaymentService cardPayments;
    private final ChargebackService chargebacks;
    private final PaymentRouter router;
    private final SettlementService settlements;
    private final ReconciliationService reconciliation;
    private final SepaPaymentService sepa;
    private final SepaRouter sepaRouter;
    private final SwiftService swift;
    private final TransferBookkeeper transferBookkeeper;
    private final AmlService aml;
    private final Iso20022 iso20022;
    private final Outbox outbox;
    private final MerchantWebhookDispatcher webhooks;

    private volatile DemoSummary lastSummary;

    public DemoScenario(PaymentsProperties properties, Ledger ledger, TokenVault tokenVault,
                        IssuerSimulator issuer, CardPaymentService cardPayments,
                        ChargebackService chargebacks, PaymentRouter router,
                        SettlementService settlements, ReconciliationService reconciliation,
                        SepaPaymentService sepa, SepaRouter sepaRouter, SwiftService swift,
                        TransferBookkeeper transferBookkeeper, AmlService aml,
                        Iso20022 iso20022, Outbox outbox, MerchantWebhookDispatcher webhooks) {
        this.properties = properties;
        this.ledger = ledger;
        this.tokenVault = tokenVault;
        this.issuer = issuer;
        this.cardPayments = cardPayments;
        this.chargebacks = chargebacks;
        this.router = router;
        this.settlements = settlements;
        this.reconciliation = reconciliation;
        this.sepa = sepa;
        this.sepaRouter = sepaRouter;
        this.swift = swift;
        this.transferBookkeeper = transferBookkeeper;
        this.aml = aml;
        this.iso20022 = iso20022;
        this.outbox = outbox;
        this.webhooks = webhooks;
    }

    /** Headline outcomes, for the console to show without re-deriving them. */
    public record DemoSummary(
            Instant ranAt,
            List<String> steps,
            String pain001,
            String camt053,
            String mt103,
            Optional<String> downgradedTransferExplanation,
            Optional<String> chargebackOutcome,
            Optional<String> reconciliationSummary) {

        public DemoSummary {
            steps = List.copyOf(steps);
        }
    }

    public Optional<DemoSummary> lastSummary() {
        return Optional.ofNullable(lastSummary);
    }

    /**
     * Runs the whole scenario. Safe to call more than once — the second run simply
     * adds more history, which is itself useful for seeing ageing and velocity
     * behave.
     */
    public DemoSummary run() {
        List<String> steps = new ArrayList<>();
        String currency = properties.getDemoCurrency();

        bootstrap(currency, steps);

        // --- Card payments ---------------------------------------------
        lowValueExemptPayment(currency, steps);
        PaymentResult challenged = challengedPayment(currency, steps);
        declinedPayment(currency, steps);
        failoverPayment(currency, steps);
        partialCaptureAndRefund(currency, steps);
        Optional<String> chargebackOutcome = disputeDefeatedByLiabilityShift(challenged, steps);
        cardTestingBurst(currency, steps);

        // --- Banking rails ---------------------------------------------
        TransferResult instant = instantTransfer(currency, steps);
        Optional<String> downgraded = downgradedTransfers(currency, steps);
        rejectedTransfers(currency, steps);
        String mt103 = crossBorderTransfer(steps);

        // --- Settlement and reconciliation -----------------------------
        Optional<String> reconSummary = settleAndReconcile(currency, steps);

        // --- Messaging -------------------------------------------------
        String pain001 = iso20022Messages(instant, steps);
        String camt053 = statementMessage(currency, steps);

        // --- Eventual consistency --------------------------------------
        eventDelivery(steps);

        DemoSummary summary = new DemoSummary(Instant.now(), steps, pain001, camt053, mt103,
                downgraded, chargebackOutcome, reconSummary);
        lastSummary = summary;
        log.info("Demo scenario completed with {} steps; ledger holds {} entries",
                steps.size(), ledger.size());
        return summary;
    }

    // --- Setup ------------------------------------------------------------

    private void bootstrap(String currency, List<String> steps) {
        ChartOfAccounts.bootstrap(ledger, currency, List.of(MERCHANT_STORE, MERCHANT_TRAVEL));

        // Issuer-side card accounts, keyed by the payment account reference the
        // vault derives. Tokenizing here is how the demo learns it without
        // handling the card number anywhere else.
        openIssuerAccount(approvedCard(), currency, "5000.00");
        openIssuerAccount(insufficientFundsCard(), currency, "10.00");
        openIssuerAccount(scaRequiredCard(), currency, "5000.00");
        openIssuerAccount(nordicCard(), currency, "8000.00");
        openIssuerAccount(commercialCard(), currency, "25000.00");

        // Customer onboarding, so transfers have a verified payer with a balance.
        if (aml.customer(CUSTOMER).isEmpty()) {
            aml.register(new CustomerProfile(CUSTOMER, "Liis-Mari Männik", "EE", "EE",
                    KycStatus.PENDING, false, Money.of(currency, "20000.00"), null));
            aml.verifyCustomer(CUSTOMER);
        }

        // Topped up rather than funded once, because the scenario is meant to be
        // re-runnable and each run spends a little over 150,000 on the transfers. A
        // one-off deposit would leave later runs rejecting for want of funds, which
        // would quietly change what the demo demonstrates.
        Money balance = transferBookkeeper.customerFunds(CUSTOMER, currency);
        Money workingBalance = Money.of(currency, "400000.00");
        if (balance.isLessThan(workingBalance)) {
            transferBookkeeper.recordCustomerDeposit(CUSTOMER, "top-up-" + shortId(),
                    workingBalance.minus(balance));
        }

        // Beneficiary names, so Confirmation of Payee has something to check.
        sepa.registerAccountName("DE04500700100532013000", "Klaus Weber");
        sepa.registerAccountName("SE2930000000000540398031", "Astrid Lindqvist");
        sepa.registerAccountName("NL86INGB0002445588", "Pieter de Vries");

        steps.add("Bootstrapped the chart of accounts, five card accounts at the issuer, "
                + "a KYC-verified customer topped up to "
                + transferBookkeeper.customerFunds(CUSTOMER, currency)
                + ", and three beneficiary names for Confirmation of Payee.");
    }

    private void openIssuerAccount(CardDetails card, String currency, String limit) {
        String par = tokenVault.tokenize(card, "bootstrap").par();
        if (issuer.account(par).isEmpty()) {
            issuer.openAccount(par, "EE", Money.of(currency, limit));
        }
    }

    // --- Card scenarios ---------------------------------------------------

    /**
     * A €18 purchase. Under the low-value threshold, so SCA is exempted and the
     * shopper sees no challenge — at the cost of keeping fraud liability.
     */
    private PaymentResult lowValueExemptPayment(String currency, List<String> steps) {
        PaymentResult result = cardPayments.authorize(AuthorizeCommand.ecommerce(
                MERCHANT_STORE, "order-" + shortId(), approvedCard(),
                Money.of(currency, "18.50"), "5411", idempotencyKey()));

        steps.add("Low-value payment of " + Money.of(currency, "18.50") + ": "
                + describe(result) + ". SCA exemption claimed ("
                + result.payment().authentication().exemption()
                + "), so liability stays with us rather than shifting to the issuer.");
        return result;
    }

    /**
     * A €940 purchase. Above the issuer's frictionless ceiling, so it is challenged;
     * passing the challenge shifts liability, which matters later when it is
     * disputed.
     */
    private PaymentResult challengedPayment(String currency, List<String> steps) {
        PaymentResult initial = cardPayments.authorize(new AuthorizeCommand(
                MERCHANT_TRAVEL, "order-" + shortId(), approvedCard(),
                Money.of(currency, "940.00"), "4722",
                false, false, false, false, false,
                "81.20.14.7", "EE", "EE", idempotencyKey(), false));

        if (!initial.requiresAuthentication()) {
            steps.add("High-value payment of " + Money.of(currency, "940.00")
                    + " did not require a challenge: " + describe(initial));
            return initial;
        }

        PaymentResult authenticated = cardPayments.completeAuthentication(
                initial.payment().paymentId(), true);
        cardPayments.captureAll(authenticated.payment().paymentId(), idempotencyKey());

        steps.add("High-value payment of " + Money.of(currency, "940.00")
                + " was challenged under 3-D Secure, the cardholder passed, and it was then "
                + "authorized and captured. ECI "
                + authenticated.payment().authentication().eci()
                + " with liability shift — the issuer now carries fraud risk on it.");
        return authenticated;
    }

    /** A card with no room on it. A soft decline: retrying later could work. */
    private void declinedPayment(String currency, List<String> steps) {
        PaymentResult result = cardPayments.authorize(AuthorizeCommand.ecommerce(
                MERCHANT_STORE, "order-" + shortId(), insufficientFundsCard(),
                Money.of(currency, "250.00"), "5411", idempotencyKey()));

        steps.add("Payment on a card with a €10 limit was declined: "
                + result.message() + ". Classified as a "
                + (result.payment().declineCode() != null
                && result.payment().declineCode().isSoftDecline() ? "soft" : "hard")
                + " decline, which is what decides whether a retry is legitimate.");
    }

    /**
     * The primary acquirer is unreachable, so the payment fails over to the
     * secondary. Only possible because the failure is technical rather than a
     * decline.
     */
    private void failoverPayment(String currency, List<String> steps) {
        Optional<SimulatedAcquirerProcessor> primary = simulated("northbound");
        if (primary.isEmpty()) {
            return;
        }
        primary.get().failNextAuthorizations(1);

        PaymentResult result = cardPayments.authorize(AuthorizeCommand.ecommerce(
                MERCHANT_STORE, "order-" + shortId(), nordicCard(),
                Money.of(currency, "64.00"), "5812", idempotencyKey()));

        steps.add("With the primary acquirer returning a transient fault, the payment "
                + "failed over: processors attempted were "
                + String.join(" then ", result.processorsAttempted()) + ". Result: "
                + describe(result) + ". A decline would not have been retried elsewhere — "
                + "only infrastructure failures are.");
    }

    /**
     * Authorize, capture part of it, refund part of that. Exercises the three
     * running totals and the states between them.
     */
    private PaymentResult partialCaptureAndRefund(String currency, List<String> steps) {
        PaymentResult authorized = cardPayments.authorize(new AuthorizeCommand(
                MERCHANT_STORE, "order-" + shortId(), commercialCard(),
                Money.of(currency, "600.00"), "5411",
                false, false, false, false, true,
                "81.20.14.7", "EE", "EE", idempotencyKey(), false));

        if (!authorized.approved()) {
            steps.add("Split-shipment scenario could not start: " + authorized.message());
            return authorized;
        }

        String paymentId = authorized.payment().paymentId();
        cardPayments.capture(paymentId, Money.of(currency, "220.00"), idempotencyKey());
        cardPayments.capture(paymentId, Money.of(currency, "180.00"), idempotencyKey());
        cardPayments.refund(paymentId, Money.of(currency, "80.00"),
                "item returned", idempotencyKey());

        // A replayed capture under the same key must not take the money twice.
        String reusedKey = idempotencyKey();
        cardPayments.capture(paymentId, Money.of(currency, "50.00"), reusedKey);
        PaymentResult replay = cardPayments.capture(paymentId, Money.of(currency, "50.00"), reusedKey);

        steps.add("Split shipment on a commercial card: authorized "
                + authorized.payment().authorizedAmount() + ", captured "
                + authorized.payment().capturedAmount() + " across "
                + authorized.payment().captures().size() + " captures, refunded "
                + authorized.payment().refundedAmount() + ", leaving "
                + authorized.payment().uncapturedAmount() + " uncaptured. Re-sending the last "
                + "capture under the same idempotency key was "
                + (replay.replayed() ? "replayed rather than charged again" : "NOT deduplicated")
                + ". The card is commercial, so interchange is uncapped and priced accordingly.");
        return authorized;
    }

    /**
     * A fraud dispute on a payment that was authenticated. Liability had already
     * shifted, so the issuer was not entitled to raise it and the representment
     * wins outright.
     */
    private Optional<String> disputeDefeatedByLiabilityShift(PaymentResult authenticated,
                                                             List<String> steps) {
        if (authenticated == null || !authenticated.payment().status().isCaptured()) {
            return Optional.empty();
        }
        Chargeback dispute = chargebacks.receive(
                authenticated.payment().paymentId(),
                ChargebackReason.FRAUD_CARD_ABSENT,
                authenticated.payment().capturedAmount());

        Chargeback defended = chargebacks.represent(dispute.caseId(), List.of(
                "3-D Secure authentication record, ECI "
                        + authenticated.payment().authentication().eci(),
                "DS transaction id " + authenticated.payment().authentication().dsTransactionId(),
                "Booking confirmation and customer IP address at checkout"));

        ChargebackService.ChargebackRatio ratio = chargebacks.ratioFor(MERCHANT_TRAVEL);
        String outcome = "Dispute " + defended.caseId() + " under "
                + defended.reason().code() + " (" + defended.reason().description() + ") was "
                + defended.status() + ": " + defended.outcomeReason()
                + " The merchant's dispute ratio is now " + ratio.formattedRatio()
                + (ratio.inMonitoringProgramme()
                ? ", which is above the scheme's 0.9% threshold and would place them in a "
                + "monitoring programme."
                : ", below the scheme's 0.9% monitoring threshold.");

        steps.add(outcome);
        return Optional.of(outcome);
    }

    /**
     * Many small payments on different cards from one address. Individually
     * unremarkable; together, card testing.
     */
    private void cardTestingBurst(String currency, List<String> steps) {
        String attackerIp = "203.0.113.77";
        int attempts = 8;
        int declined = 0;
        int challenged = 0;
        int approved = 0;
        int firstDeclineAt = -1;
        int topScore = 0;

        for (int i = 0; i < attempts; i++) {
            CardDetails probe = pan("40000" + "0"
                    + String.format(java.util.Locale.ROOT, "%09d", 500_000 + i));
            String par = tokenVault.tokenize(probe, "bootstrap").par();
            if (issuer.account(par).isEmpty()) {
                issuer.openAccount(par, "EE", Money.of(currency, "3000.00"));
            }
            PaymentResult result = cardPayments.authorize(new AuthorizeCommand(
                    MERCHANT_STORE, "probe-" + i, probe, Money.of(currency, "1.00"), "5411",
                    false, false, false, false, false,
                    attackerIp, "NG", "EE", idempotencyKey(), true));

            if (result.payment().risk() != null) {
                topScore = Math.max(topScore, result.payment().risk().score());
            }
            if (result.approved()) {
                approved++;
            } else if (result.requiresAuthentication()) {
                challenged++;
            } else {
                declined++;
                if (firstDeclineAt < 0) {
                    firstDeclineAt = i + 1;
                }
            }
        }

        steps.add("Simulated a card-testing burst: " + attempts + " one-euro attempts on "
                + attempts + " different cards from a single IP address, with a "
                + "billing-country mismatch. " + approved + " approved, " + challenged
                + " stepped up to a 3-D Secure challenge, " + declined + " refused outright"
                + (firstDeclineAt > 0 ? " — the first outright refusal was attempt "
                + firstDeclineAt : "") + ". Peak risk score " + topScore + ". "
                + "Each attempt on its own is unremarkable; the signal is velocity across "
                + "distinct cards from one address, which no per-transaction check can see. "
                + "Note that the challenged attempts are what let the score climb: an engine "
                + "that only recorded decided attempts would never accumulate evidence against "
                + "an attack whose attempts all trip its own challenge rule.");
    }

    // --- Transfer scenarios -----------------------------------------------

    /** Within the instant limit, to a reachable bank. Settles in seconds. */
    private TransferResult instantTransfer(String currency, List<String> steps) {
        TransferResult result = sepa.initiate(InitiateTransferCommand.instant(
                CUSTOMER, "Liis-Mari Männik", DEBTOR_IBAN,
                "Klaus Weber", "DE04500700100532013000",
                Money.of(currency, "2450.00"), "Invoice 2026-0915", idempotencyKey()));

        steps.add("Instant transfer of " + Money.of(currency, "2450.00") + " to a German bank: "
                + result.message() + " Status " + result.transfer().status().isoCode()
                + " (" + result.transfer().status().description() + "). "
                + "Instant transfers are irrevocable once accepted, which is why "
                + "Confirmation of Payee runs before they are sent, not after.");
        return result;
    }

    /**
     * The case this application was extended for: an instant transfer that cannot
     * go instant, routed deliberately rather than rejected or silently downgraded.
     */
    private Optional<String> downgradedTransfers(String currency, List<String> steps) {
        // Over the institution's per-transaction instant limit. Still urgent, so it
        // goes to RTGS rather than waiting for the batch rail.
        TransferResult overLimit = sepa.initiate(InitiateTransferCommand.instant(
                CUSTOMER, "Liis-Mari Männik", DEBTOR_IBAN,
                "Klaus Weber", "DE04500700100532013000",
                Money.of(currency, "150000.00"), "Property deposit", idempotencyKey()));

        // Beneficiary's bank is not reachable on the instant scheme, so the amount
        // is irrelevant — it has to go on the batch rail.
        TransferResult unreachable = sepa.initiate(InitiateTransferCommand.instant(
                CUSTOMER, "Liis-Mari Männik", DEBTOR_IBAN,
                "Astrid Lindqvist", "SE2930000000000540398031",
                Money.of(currency, "320.00"), "Conference fee", idempotencyKey()));

        // The rail itself is down. Same request, different reason, same discipline.
        sepaRouter.setInstantRailAvailable(false);
        TransferResult railDown = sepa.initiate(InitiateTransferCommand.instant(
                CUSTOMER, "Liis-Mari Männik", DEBTOR_IBAN,
                "Pieter de Vries", "NL86INGB0002445588",
                Money.of(currency, "75.00"), "Shared dinner", idempotencyKey()));
        sepaRouter.setInstantRailAvailable(true);

        String explanation = "Three instant requests that could not go instant, each downgraded "
                + "with a recorded reason rather than rejected or silently changed:\n"
                + "  • " + Money.of(currency, "150000.00") + " → " + outcomeOf(overLimit) + "\n"
                + "  • " + Money.of(currency, "320.00") + " → " + outcomeOf(unreachable) + "\n"
                + "  • " + Money.of(currency, "75.00") + " → " + outcomeOf(railDown);

        steps.add(explanation);
        if (unreachable.transfer().routing() != null) {
            steps.add("The batch-rail transfers sit at ACSP with a value date of "
                    + LocalDate.ofInstant(unreachable.transfer().routing().expectedSettlement(),
                    ZoneOffset.UTC)
                    + " — the execution date " + sepaRouter.executionDate()
                    + " plus one, computed from the 15:00 CET cut-off and the TARGET calendar. "
                    + "They are not settled, and the console does not claim they are.");
        }
        return Optional.of(explanation);
    }

    /** Two transfers that must not be sent at all, for different reasons. */
    private void rejectedTransfers(String currency, List<String> steps) {
        // A typo in the IBAN. Caught by the check digits, locally, for nothing.
        TransferResult typo = sepa.initiate(InitiateTransferCommand.instant(
                CUSTOMER, "Liis-Mari Männik", DEBTOR_IBAN,
                "Klaus Weber", "DE04500700100532013009",
                Money.of(currency, "100.00"), "Typo test", idempotencyKey()));

        // A beneficiary on a sanctions list. Blocked, and a report is filed.
        TransferResult sanctioned = sepa.initiate(InitiateTransferCommand.instant(
                CUSTOMER, "Liis-Mari Männik", DEBTOR_IBAN,
                "Ivan Petrov", "NL86INGB0002445588",
                Money.of(currency, "5000.00"), "Consultancy", idempotencyKey()));

        steps.add("Rejected before anything irreversible happened: the mistyped IBAN failed "
                + "the mod-97 check (" + typo.transfer().rejectionCode() + " — "
                + typo.transfer().rejectionReason() + "), and the payment to a listed "
                + "beneficiary was blocked (" + sanctioned.transfer().rejectionCode()
                + " — " + sanctioned.transfer().rejectionReason() + "). "
                + aml.reports().size() + " suspicious activity report(s) now filed.");
    }

    /** A payment that leaves SEPA: FX, a correspondent chain, and fees in transit. */
    private String crossBorderTransfer(List<String> steps) {
        SwiftService.CrossBorderInstruction instruction = new SwiftService.CrossBorderInstruction(
                CUSTOMER,
                PartyDetails.of("Liis-Mari Männik", new Iban(DEBTOR_IBAN)),
                PartyDetails.of("Jane Roe", new Iban("DE04500700100532013000")),
                "0123456789", "US",
                Money.of("EUR", "5000.00"), "USD",
                "Invoice US-4471", CreditTransfer.ChargeBearer.SHAR);

        SwiftService.CrossBorderQuote quote = swift.quote(instruction);
        SwiftService.CrossBorderResult result = swift.send(instruction);

        steps.add("Cross-border payment of " + Money.of("EUR", "5000.00") + " to the United "
                + "States: " + result.message() + " " + quote.explanation()
                + ". The sender pays " + quote.amountDebited() + " and the beneficiary receives "
                + quote.amountCredited() + "; the difference is "
                + quote.senderFee() + " in explicit fees, " + quote.fx().spreadCost()
                + " in FX spread, and " + quote.deductedCharges()
                + " taken out of the principal by correspondents in transit. "
                + "The spread is the largest component and the one a customer is least "
                + "likely to notice.");

        return result.mt103() == null ? "" : result.mt103();
    }

    // --- Settlement and reconciliation -----------------------------------

    private Optional<String> settleAndReconcile(String currency, List<String> steps) {
        List<SettlementBatch> batches = settlements.runCutOff();
        if (batches.isEmpty()) {
            steps.add("Settlement cut-off found nothing to sweep.");
            return Optional.empty();
        }

        steps.add("Settlement cut-off closed " + batches.size() + " batch(es). For "
                + batches.get(0).merchantId() + ": gross " + batches.get(0).grossSales()
                + ", refunds " + batches.get(0).refunds()
                + ", disputes " + batches.get(0).chargebacks()
                + ", acquirer funding " + batches.get(0).acquirerFunding()
                + ", merchant payout " + batches.get(0).netPayout()
                + ", margin " + batches.get(0).margin()
                + ". Funding and payout differ by the margin, and both are tracked.");

        // A clean three-way reconciliation on the first batch, then payout.
        SettlementBatch clean = batches.get(0);
        ProcessorSettlementReport report = settlements.reportFor(clean.batchId());
        BankStatement statement = statementFunding(clean, report, currency);

        ReconciliationRun cleanRun = reconciliation.reconcile(clean.batchId(), report, statement);
        settlements.markFunded(clean.batchId(), report.netAmount());
        String payoutNote;
        try {
            SettlementBatch paid = settlements.payOut(clean.batchId());
            payoutNote = "paid out " + paid.netPayout() + " to " + paid.merchantId();
        } catch (IllegalStateException e) {
            payoutNote = "payout withheld: " + e.getMessage();
        }

        steps.add("Three-way reconciliation of batch " + clean.batchId() + ": "
                + cleanRun.summary() + ". " + payoutNote + ".");

        // A deliberate break on a second batch, to show detection and the hold.
        String breakSummary = null;
        if (batches.size() > 1) {
            SettlementBatch perturbed = batches.get(1);
            ProcessorSettlementReport original = settlements.reportFor(perturbed.batchId());
            ProcessorSettlementReport corrupted = withShortPaidLine(original, currency);

            ReconciliationRun brokenRun = reconciliation.reconcile(
                    perturbed.batchId(), corrupted, null);
            breakSummary = "Reconciled batch " + perturbed.batchId()
                    + " against a report where the processor short-paid one line: "
                    + brokenRun.summary() + ". The batch is now "
                    + settlements.require(perturbed.batchId()).status()
                    + " — a discrepancy that cannot be explained must not be paid out.";
            steps.add(breakSummary);

            // Write the unexplained difference to suspense so the books balance
            // while it is investigated.
            reconciliation.openBreaks().stream()
                    .filter(item -> item.type() == BreakType.AMOUNT_MISMATCH)
                    .findFirst()
                    .ifPresent(item -> {
                        reconciliation.writeOffToSuspense(item.breakKey(),
                                "pending processor query; parked rather than absorbed");
                        steps.add("Wrote the unexplained " + item.difference()
                                + " to the suspense account. The ledger balances again, and the "
                                + "suspense balance is itself a reported figure somebody has to "
                                + "explain — which is what stops write-off becoming a habit.");
                    });
        }

        Ledger.TrialBalance trialBalance = ledger.trialBalance();
        steps.add("Ledger check: " + ledger.size() + " journal entries, trial balance "
                + (trialBalance.balances() ? "balances" : "DOES NOT BALANCE")
                + ", hash chain " + (ledger.verifyIntegrity().intact() ? "intact" : "BROKEN")
                + ".");

        return Optional.ofNullable(breakSummary == null ? cleanRun.summary() : breakSummary);
    }

    /** A statement showing the acquirer's funding credit arriving. */
    private BankStatement statementFunding(SettlementBatch batch, ProcessorSettlementReport report,
                                           String currency) {
        Money opening = Money.of(currency, "250000.00");
        StatementEntry funding = new StatementEntry(
                "NTRY-" + batch.batchId(),
                report.netAmount(), true,
                batch.expectedFundingDate(), batch.expectedFundingDate(),
                batch.processorId(), null,
                "Card settlement " + batch.batchId(),
                batch.batchId(), "ESCT");

        return new BankStatement(
                "STMT-" + batch.batchId(),
                DEBTOR_IBAN,
                batch.expectedFundingDate(), batch.expectedFundingDate(),
                opening, opening.plus(report.netAmount()),
                List.of(funding));
    }

    /**
     * Copies a report with one sale line short-paid, to produce a genuine
     * amount mismatch for reconciliation to find.
     */
    private ProcessorSettlementReport withShortPaidLine(ProcessorSettlementReport report,
                                                        String currency) {
        List<Line> lines = new ArrayList<>(report.lines());
        if (lines.isEmpty()) {
            return report;
        }
        Line original = lines.get(0);
        Money shortfall = Money.of(currency, "5.00");
        lines.set(0, new Line(original.itemId(), original.processorReference(), original.paymentId(),
                original.type(), original.gross().minus(shortfall), original.fee(),
                original.transactionDate()));

        Money net = Money.zero(currency);
        for (Line line : lines) {
            net = net.plus(line.signedNet());
        }
        return new ProcessorSettlementReport(report.reportId(), report.processorId(),
                report.merchantId(), report.settlementDate(), report.currency(),
                report.grossAmount().minus(shortfall), report.feeAmount(), net, lines);
    }

    // --- Messaging --------------------------------------------------------

    private String iso20022Messages(TransferResult instant, List<String> steps) {
        if (instant == null || instant.transfer() == null) {
            return "";
        }
        String pain001 = iso20022.pain001("MSG-" + shortId(), "Bankster Payments",
                List.of(instant.transfer()));
        String pacs008 = iso20022.pacs008("PACS-" + shortId(), instant.transfer(), "TIPS");

        steps.add("Generated the ISO 20022 messages for the instant transfer: a pain.001 "
                + "initiation carrying LclInstrm/Cd of INST — the element that actually "
                + "requests the instant rail — and a pacs.008 interbank message settling "
                + "through TIPS. Both are "
                + (pain001.length() + pacs008.length()) + " characters of structured data "
                + "where an MT103 would have one 140-character free-text field.");
        return pain001;
    }

    private String statementMessage(String currency, List<String> steps) {
        LocalDate today = LocalDate.now();
        Money opening = Money.of(currency, "12000.00");
        List<StatementEntry> entries = List.of(
                new StatementEntry("E-1", Money.of(currency, "2450.00"), false, today, today,
                        "Klaus Weber", "DE04500700100532013000", "Invoice 2026-0915",
                        "E2E-DEMO-0001", "ESCT"),
                new StatementEntry("E-2", Money.of(currency, "980.00"), true, today, today,
                        "Northbound Payments", null, "Card settlement", "E2E-DEMO-0002", "RCDT"));

        Money closing = opening.minus(Money.of(currency, "2450.00")).plus(Money.of(currency, "980.00"));
        BankStatement statement = new BankStatement("STMT-DEMO", DEBTOR_IBAN,
                today, today, opening, closing, entries);

        String camt053 = iso20022.camt053(statement);
        BankStatement reparsed = iso20022.parseCamt053(camt053);

        steps.add("Generated a camt.053 statement and parsed it back: "
                + reparsed.entries().size() + " entries, self-consistency check "
                + (reparsed.isSelfConsistent() ? "passed" : "FAILED")
                + " (opening plus entries equals closing). That check runs before "
                + "reconciliation, because matching against a truncated file manufactures "
                + "breaks that do not exist.");
        return camt053;
    }

    // --- Eventual consistency --------------------------------------------

    /**
     * Drains the outbox, then shows the failure path: a merchant endpoint that
     * rejects one event type until it is dead-lettered, and the replay once it
     * recovers.
     */
    private void eventDelivery(List<String> steps) {
        int dispatched = outbox.drain();

        webhooks.failDeliveriesOfType("payment.refunded");
        outbox.append("payment.refunded", "demo-dead-letter",
                java.util.Map.of("note", "endpoint is down"));
        for (int attempt = 0; attempt < Outbox.MAX_ATTEMPTS; attempt++) {
            outbox.drain();
        }
        int deadLetters = outbox.deadLetters().size();

        webhooks.recover();
        outbox.deadLetters().forEach(record -> outbox.replayDeadLetter(record.event().eventId()));
        outbox.drain();

        steps.add("Outbox: " + dispatched + " events delivered on the first pass, "
                + webhooks.deliveries().size() + " webhooks sent in total. A failing merchant "
                + "endpoint caused " + deadLetters + " event(s) to be dead-lettered after "
                + Outbox.MAX_ATTEMPTS + " attempts rather than blocking the queue; once the "
                + "endpoint recovered they were replayed, and the per-handler dedupe log meant "
                + "nothing was applied twice. "
                + outbox.deadLetters().size() + " dead letter(s) remain.");
    }

    // --- Test cards -------------------------------------------------------

    private CardDetails approvedCard() {
        return pan("400000000000000");
    }

    private CardDetails insufficientFundsCard() {
        return pan("400002000000000");
    }

    private CardDetails scaRequiredCard() {
        return pan("400006000000000");
    }

    private CardDetails nordicCard() {
        return pan("411111000000000");
    }

    private CardDetails commercialCard() {
        return pan("492910000000000");
    }

    /** Builds a valid test card from a 15-digit prefix by computing the check digit. */
    private CardDetails pan(String fifteenDigits) {
        Pan pan = new Pan(fifteenDigits + Pan.luhnCheckDigit(fifteenDigits));
        return new CardDetails(pan, YearMonth.now().plusYears(2), "LIIS-MARI MANNIK", "123");
    }

    // --- Helpers ----------------------------------------------------------

    private Optional<SimulatedAcquirerProcessor> simulated(String processorId) {
        for (AcquirerProcessor processor : router.processors()) {
            if (processor.id().equals(processorId)
                    && processor instanceof SimulatedAcquirerProcessor simulated) {
                return Optional.of(simulated);
            }
        }
        return Optional.empty();
    }

    private String describe(PaymentResult result) {
        if (result.approved()) {
            return "approved (" + result.message() + ")";
        }
        if (result.requiresAuthentication()) {
            return "awaiting a 3-D Secure challenge";
        }
        return "declined — " + result.message();
    }

    private String firstReason(TransferResult result) {
        if (result.transfer().routing() == null
                || result.transfer().routing().rejections().isEmpty()) {
            return result.message();
        }
        return result.transfer().routing().rejections().get(0).reason();
    }

    /**
     * How a transfer ended, whichever way it ended.
     *
     * <p>A transfer rejected before routing has no rail, so reading one would fail.
     * That is not a hypothetical: a payment can be refused for compliance or for want
     * of funds before it is ever routed, and the narrative has to say so rather than
     * break.
     */
    private String outcomeOf(TransferResult result) {
        if (result.transfer().rail() == null) {
            return "not sent (" + result.transfer().status().isoCode() + "): " + result.message();
        }
        return result.transfer().rail().displayName() + ": " + firstReason(result);
    }

    private String idempotencyKey() {
        return "demo-" + UUID.randomUUID();
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
