package bankster.client.payments.cards;

import java.time.Duration;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import bankster.client.payments.Money;
import bankster.client.payments.TestClock;
import bankster.client.payments.core.AuditTrail;
import bankster.client.payments.core.IdempotencyStore;
import bankster.client.payments.core.Outbox;
import bankster.client.payments.ledger.ChartOfAccounts;
import bankster.client.payments.ledger.Ledger;
import bankster.client.payments.orchestration.PaymentBookkeeper;
import bankster.client.payments.orchestration.PaymentRepository;
import bankster.client.payments.orchestration.PaymentRouter;
import bankster.client.payments.risk.FraudEngine;

/**
 * Assembles the card-payment object graph without Spring, so the tests stay fast
 * and every collaborator is reachable for assertions.
 *
 * <p>Two processors are wired, because one cannot demonstrate routing or failover.
 */
final class CardFixture {

    static final String MERCHANT = "merchant-1";
    static final String CURRENCY = "EUR";

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

    final SimulatedAcquirerProcessor primary;
    final SimulatedAcquirerProcessor secondary;
    final PaymentRouter router;
    final CardPaymentService service;
    final ChargebackService chargebacks;

    CardFixture() {
        primary = new SimulatedAcquirerProcessor("primary", "Primary Acquirer",
                Set.of(CardScheme.VISA, CardScheme.MASTERCARD, CardScheme.AMEX),
                Set.of("EUR", "USD"), issuer, FeeSchedule.standardEeaDebit(CURRENCY),
                Duration.ofDays(1), 0.95);
        secondary = new SimulatedAcquirerProcessor("secondary", "Secondary Acquirer",
                Set.of(CardScheme.VISA, CardScheme.MASTERCARD),
                Set.of("EUR"), issuer, FeeSchedule.standardEeaCredit(CURRENCY),
                Duration.ofDays(2), 0.90);

        router = new PaymentRouter(List.of(primary, secondary));
        service = new CardPaymentService(clock, vault, binTable, fraudEngine, threeDSecure,
                router, payments, bookkeeper, issuer, outbox, auditTrail, idempotency);
        chargebacks = new ChargebackService(clock, payments, bookkeeper, auditTrail, outbox);

        ChartOfAccounts.bootstrap(ledger, CURRENCY, List.of(MERCHANT));
    }

    /** A Luhn-valid test card on the given BIN, with an issuer account behind it. */
    CardDetails cardOn(String bin, String creditLimit) {
        CardDetails details = cardOn(bin);
        String par = vault.tokenize(details, "fixture").par();
        if (issuer.account(par).isEmpty()) {
            issuer.openAccount(par, "EE", Money.of(CURRENCY, creditLimit));
        }
        return details;
    }

    /** A card with no issuer account — used to exercise the unknown-card decline. */
    CardDetails cardOn(String bin) {
        String prefix = bin + "000000000".substring(0, 15 - bin.length());
        return new CardDetails(new Pan(prefix + Pan.luhnCheckDigit(prefix)),
                YearMonth.from(clock.instant().atZone(clock.getZone())).plusYears(3),
                "TEST HOLDER", "123");
    }

    /** Distinct cards on the same BIN, so velocity scenarios have separate accounts. */
    CardDetails distinctCardOn(String bin, int index, String creditLimit) {
        String suffix = String.format(java.util.Locale.ROOT, "%0" + (15 - bin.length()) + "d", index);
        String prefix = bin + suffix;
        CardDetails details = new CardDetails(new Pan(prefix + Pan.luhnCheckDigit(prefix)),
                YearMonth.from(clock.instant().atZone(clock.getZone())).plusYears(3),
                "TEST HOLDER", "123");
        String par = vault.tokenize(details, "fixture").par();
        if (issuer.account(par).isEmpty()) {
            issuer.openAccount(par, "EE", Money.of(CURRENCY, creditLimit));
        }
        return details;
    }

    Money eur(String amount) {
        return Money.of(CURRENCY, amount);
    }

    /** An e-commerce authorization that is not auto-captured. */
    CardPaymentService.AuthorizeCommand authOnly(CardDetails card, String amount) {
        return authOnlyFrom(card, amount, "127.0.0.1");
    }

    /** An e-commerce authorization captured in the same call. */
    CardPaymentService.AuthorizeCommand authAndCapture(CardDetails card, String amount) {
        return authAndCaptureFrom(card, amount, "127.0.0.1");
    }

    /**
     * As {@link #authOnly} but from a named address.
     *
     * <p>Tests that make several payments in quick succession need distinct
     * addresses, or the fraud engine's card-spread rule legitimately steps them up
     * to a challenge — which is correct behaviour, and not what those tests are
     * about.
     */
    CardPaymentService.AuthorizeCommand authOnlyFrom(CardDetails card, String amount, String ip) {
        return new CardPaymentService.AuthorizeCommand(MERCHANT, "order-" + amount, card,
                eur(amount), "5411", false, false, false, false, false,
                ip, "EE", "EE", key(), false);
    }

    CardPaymentService.AuthorizeCommand authAndCaptureFrom(CardDetails card, String amount, String ip) {
        return new CardPaymentService.AuthorizeCommand(MERCHANT, "order-" + amount, card,
                eur(amount), "5411", false, false, false, false, false,
                ip, "EE", "EE", key(), true);
    }

    /**
     * Captures enough prior volume that the merchant's payable balance can absorb a
     * claw-back and its fee. 400.00 stays under the issuer's frictionless ceiling, so
     * it needs no challenge.
     */
    Money warmUpMerchantBalance() {
        service.authorize(authAndCaptureFrom(
                distinctCardOn("400000", 900_000, "5000.00"), "400.00", "10.0.0.254"));
        return merchantBalance();
    }

    String key() {
        return "key-" + java.util.UUID.randomUUID();
    }

    Money merchantBalance() {
        return bookkeeper.merchantBalance(MERCHANT, CURRENCY);
    }

    Money balanceOf(String baseAccount) {
        return ledger.balanceOf(ChartOfAccounts.in(baseAccount, CURRENCY));
    }
}
