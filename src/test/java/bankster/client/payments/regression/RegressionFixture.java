package bankster.client.payments.regression;

import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import bankster.client.payments.Money;
import bankster.client.payments.TestClock;
import bankster.client.payments.cards.AcquirerProcessor;
import bankster.client.payments.cards.BinTable;
import bankster.client.payments.cards.CardDetails;
import bankster.client.payments.cards.CardPaymentService;
import bankster.client.payments.cards.CardScheme;
import bankster.client.payments.cards.ChargebackService;
import bankster.client.payments.cards.FeeSchedule;
import bankster.client.payments.cards.IssuerSimulator;
import bankster.client.payments.cards.Pan;
import bankster.client.payments.cards.SimulatedAcquirerProcessor;
import bankster.client.payments.cards.ThreeDSecureService;
import bankster.client.payments.cards.TokenVault;
import bankster.client.payments.core.AuditTrail;
import bankster.client.payments.core.IdempotencyStore;
import bankster.client.payments.core.Outbox;
import bankster.client.payments.ledger.ChartOfAccounts;
import bankster.client.payments.ledger.Ledger;
import bankster.client.payments.orchestration.PaymentBookkeeper;
import bankster.client.payments.orchestration.PaymentRepository;
import bankster.client.payments.orchestration.PaymentRouter;
import bankster.client.payments.rails.CorrespondentNetwork;
import bankster.client.payments.rails.FxRates;
import bankster.client.payments.rails.IbanBicDirectory;
import bankster.client.payments.rails.ReachabilityDirectory;
import bankster.client.payments.rails.SepaPaymentService;
import bankster.client.payments.rails.SepaRouter;
import bankster.client.payments.rails.SwiftService;
import bankster.client.payments.rails.TransferBookkeeper;
import bankster.client.payments.recon.ReconciliationService;
import bankster.client.payments.risk.AmlService;
import bankster.client.payments.risk.CustomerProfile;
import bankster.client.payments.risk.FraudEngine;
import bankster.client.payments.risk.KycStatus;
import bankster.client.payments.risk.SanctionsList;

/**
 * The whole object graph, assembled without Spring.
 *
 * <p>Deliberately one fixture covering cards, rails, settlement and reconciliation
 * together rather than a fixture per package: several of the defects these tests pin
 * down only appear when two modules interact — a dispute outcome showing up in the
 * ledger, a cross-border payment failing to reach the books, a split capture
 * confusing reconciliation.
 */
final class RegressionFixture {

    static final String MERCHANT = "merchant-1";
    static final String CUSTOMER = "cust-1";
    static final String CURRENCY = "EUR";

    /** Approves, and the issuer participates in 3-D Secure. */
    static final String GOOD_BIN = "400000";

    static final String DEBTOR_IBAN = "EE717700771001735865";
    /** Deutsche Bank — reachable on SEPA Instant. */
    static final String REACHABLE_IBAN = "DE04500700100532013000";
    /** ING — reachable, and used as the beneficiary for the screening-order test. */
    static final String ING_IBAN = "NL86INGB0002445588";

    final TestClock clock = TestClock.businessDayMorning();
    final Ledger ledger = new Ledger(clock);
    final TokenVault vault = new TokenVault();
    final BinTable binTable = new BinTable();
    final FraudEngine fraudEngine = new FraudEngine(clock);
    final ThreeDSecureService threeDSecure = new ThreeDSecureService(5);
    final IssuerSimulator issuer = new IssuerSimulator(clock);
    final PaymentRepository payments = new PaymentRepository();
    final PaymentBookkeeper bookkeeper = new PaymentBookkeeper(ledger);
    final AuditTrail auditTrail = new AuditTrail(clock);
    final Outbox outbox = new Outbox(clock);
    final IdempotencyStore idempotency = new IdempotencyStore(clock);

    final SimulatedAcquirerProcessor acquirer;
    final PaymentRouter router;
    final CardPaymentService cardPayments;
    final ChargebackService chargebacks;
    final bankster.client.payments.settlement.SettlementService settlements;
    final ReconciliationService reconciliation;

    final SanctionsList sanctions = new SanctionsList();
    final AmlService aml = new AmlService(clock, sanctions);
    final ReachabilityDirectory reachability = new ReachabilityDirectory();
    final IbanBicDirectory bicDirectory = new IbanBicDirectory();
    final SepaRouter sepaRouter;
    final TransferBookkeeper transferBookkeeper = new TransferBookkeeper(ledger);
    final SepaPaymentService sepa;
    final FxRates fxRates = new FxRates();
    final CorrespondentNetwork network = new CorrespondentNetwork();
    final SwiftService swift;

    RegressionFixture() {
        acquirer = new SimulatedAcquirerProcessor("primary", "Primary Acquirer",
                Set.of(CardScheme.VISA, CardScheme.MASTERCARD, CardScheme.AMEX),
                Set.of("EUR", "USD"), issuer, FeeSchedule.standardEeaDebit(CURRENCY),
                Duration.ofDays(1), 0.95);
        router = new PaymentRouter(List.<AcquirerProcessor>of(acquirer));

        cardPayments = new CardPaymentService(clock, vault, binTable, fraudEngine, threeDSecure,
                router, payments, bookkeeper, issuer, outbox, auditTrail, idempotency);
        chargebacks = new ChargebackService(clock, payments, bookkeeper, auditTrail, outbox);
        settlements = new bankster.client.payments.settlement.SettlementService(clock, payments,
                router, binTable, bookkeeper, chargebacks, auditTrail, outbox);
        reconciliation = new ReconciliationService(clock, payments, chargebacks, settlements,
                ledger, auditTrail, outbox);

        sepaRouter = new SepaRouter(clock, reachability, bicDirectory);
        sepa = new SepaPaymentService(clock, sepaRouter, bicDirectory, aml, transferBookkeeper,
                auditTrail, outbox, idempotency);
        swift = new SwiftService(clock, network, fxRates, transferBookkeeper, auditTrail, outbox);

        ChartOfAccounts.bootstrap(ledger, CURRENCY, List.of(MERCHANT));
    }

    // --- Customers and funding --------------------------------------------

    /** Onboards and verifies the payer, then funds the balance. */
    void onboardAndFund(String amount) {
        aml.register(new CustomerProfile(CUSTOMER, "Liis-Mari Männik", "EE", "EE",
                KycStatus.PENDING, false, Money.of(CURRENCY, "1000000.00"), null));
        aml.verifyCustomer(CUSTOMER);
        transferBookkeeper.recordCustomerDeposit(CUSTOMER, "funding", Money.of(CURRENCY, amount));
    }

    // --- Cards -------------------------------------------------------------

    /** A distinct Luhn-valid card on a BIN, with an issuer account behind it. */
    CardDetails card(int index, String creditLimit) {
        String prefix = GOOD_BIN + String.format(java.util.Locale.ROOT, "%09d", index);
        CardDetails details = new CardDetails(new Pan(prefix + Pan.luhnCheckDigit(prefix)),
                YearMonth.from(clock.instant().atZone(ZoneOffset.UTC)).plusYears(3),
                "TEST HOLDER", "123");
        String par = vault.tokenize(details, "fixture").par();
        if (issuer.account(par).isEmpty()) {
            issuer.openAccount(par, "EE", Money.of(CURRENCY, creditLimit));
        }
        return details;
    }

    /**
     * An e-commerce authorization from a named address.
     *
     * <p>The address is a parameter because several of these tests make a run of
     * payments, and a shared address legitimately trips the card-spread rule —
     * correct behaviour, but not what most of them are about.
     */
    CardPaymentService.AuthorizeCommand auth(CardDetails card, String amount, String ip,
                                             boolean captureImmediately) {
        return authFrom(card, amount, ip, "EE", "EE", captureImmediately);
    }

    /**
     * As {@link #auth} but with the shopper's apparent country and billing country
     * given explicitly.
     *
     * <p>Needed to produce an attempt that is <em>challenged</em> rather than approved:
     * geography mismatches put the score in the middle band, which is where the step-up
     * happens. Without them a low-value e-commerce payment scores 10 and sails through,
     * and a test meaning to exercise the challenge path quietly exercises the approval
     * path instead.
     */
    CardPaymentService.AuthorizeCommand authFrom(CardDetails card, String amount, String ip,
                                                 String ipCountry, String billingCountry,
                                                 boolean captureImmediately) {
        return new CardPaymentService.AuthorizeCommand(MERCHANT, "order-" + amount + "-" + ip,
                card, eur(amount), "5411", false, false, false, false, false,
                ip, ipCountry, billingCountry, key(), captureImmediately);
    }

    /** Authorizes, passes the 3-D Secure challenge, and captures in full. */
    CardPaymentService.PaymentResult authenticatedCapture(int cardIndex, String amount) {
        CardPaymentService.PaymentResult initial = cardPayments.authorize(
                auth(card(cardIndex, "5000.00"), amount, "198.51.100." + cardIndex, false));
        if (!initial.requiresAuthentication()) {
            throw new IllegalStateException("expected a 3-D Secure challenge at " + amount);
        }
        CardPaymentService.PaymentResult authorized =
                cardPayments.completeAuthentication(initial.payment().paymentId(), true);
        cardPayments.captureAll(authorized.payment().paymentId(), key());
        return authorized;
    }

    // --- Helpers -----------------------------------------------------------

    Money eur(String amount) {
        return Money.of(CURRENCY, amount);
    }

    Money merchantBalance() {
        return bookkeeper.merchantBalance(MERCHANT, CURRENCY);
    }

    Money balanceOf(String baseAccount) {
        return ledger.balanceOf(ChartOfAccounts.in(baseAccount, CURRENCY));
    }

    Money customerFunds() {
        return transferBookkeeper.customerFunds(CUSTOMER, CURRENCY);
    }

    String key() {
        return "key-" + java.util.UUID.randomUUID();
    }
}
