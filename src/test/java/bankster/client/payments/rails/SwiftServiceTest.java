package bankster.client.payments.rails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import bankster.client.payments.Money;
import bankster.client.payments.TestClock;
import bankster.client.payments.core.AuditTrail;
import bankster.client.payments.core.Outbox;
import bankster.client.payments.ledger.ChartOfAccounts;
import bankster.client.payments.ledger.Ledger;

/**
 * Cross-border payments: FX spread, a correspondent chain, and charges deducted in
 * transit. The test that matters most is the one asserting what the beneficiary
 * actually receives.
 */
class SwiftServiceTest {

    private static final String CUSTOMER = "cust-1";

    private TestClock clock;
    private Ledger ledger;
    private FxRates fxRates;
    private CorrespondentNetwork network;
    private TransferBookkeeper bookkeeper;
    private SwiftService swift;

    @BeforeEach
    void setUp() {
        clock = TestClock.businessDayMorning();
        ledger = new Ledger(clock);
        fxRates = new FxRates();
        network = new CorrespondentNetwork();
        bookkeeper = new TransferBookkeeper(ledger);
        swift = new SwiftService(clock, network, fxRates, bookkeeper,
                new AuditTrail(clock), new Outbox(clock));

        ChartOfAccounts.bootstrap(ledger, "EUR", List.of());
        ChartOfAccounts.openCustomerAccount(ledger, CUSTOMER, "EUR");
        bookkeeper.recordCustomerDeposit(CUSTOMER, "funding", Money.of("EUR", "100000.00"));
    }

    private SwiftService.CrossBorderInstruction toUnitedStates(String amount) {
        return new SwiftService.CrossBorderInstruction(CUSTOMER,
                PartyDetails.of("Liis-Mari Männik", new Iban("EE717700771001735865")),
                PartyDetails.of("Jane Roe", new Iban("DE04500700100532013000")),
                "0123456789", "US",
                Money.of("EUR", amount), "USD", "Invoice US-4471",
                CreditTransfer.ChargeBearer.SHAR);
    }

    // --- Quoting ----------------------------------------------------------

    @Test
    void theQuoteSaysWhatTheBeneficiaryWillActuallyReceive() {
        SwiftService.CrossBorderQuote quote = swift.quote(toUnitedStates("5000.00"));

        assertTrue(quote.routable());
        // 5000 EUR at 1.085 mid, less a 50 bps spread, less 15 USD deducted in transit.
        assertEquals(Money.of("EUR", "5030.00"), quote.amountDebited(),
                "the principal plus our explicit fee");
        assertEquals(Money.of("USD", "5382.87"), quote.amountCredited());
        assertEquals(Money.of("USD", "15.00"), quote.deductedCharges());
        assertEquals(List.of("CHASUS33"), quote.correspondentChain());
    }

    @Test
    void theSpreadIsUsuallyLargerThanTheExplicitFees() {
        SwiftService.CrossBorderQuote quote = swift.quote(toUnitedStates("5000.00"));

        assertEquals(Money.of("EUR", "30.00"), quote.senderFee());
        assertEquals(Money.of("EUR", "25.00"), quote.fx().spreadCost());
        assertEquals(Money.of("EUR", "55.00"), quote.totalCostInSourceCurrency());
        assertTrue(quote.fx().clientRate().compareTo(quote.fx().midRate()) < 0,
                "the spread always moves the rate against the customer");
    }

    @Test
    void theExplanationNamesTheChainAndTheRate() {
        SwiftService.CrossBorderQuote quote = swift.quote(toUnitedStates("1000.00"));

        assertTrue(quote.explanation().contains("JPMorgan Chase"), quote.explanation());
        assertTrue(quote.explanation().contains("spread 50 bps"), quote.explanation());
        assertTrue(quote.explanation().contains("deducted in transit"), quote.explanation());
    }

    @Test
    void aCountryWithNoCorrespondentRouteIsNotQuoted() {
        SwiftService.CrossBorderInstruction unreachable = new SwiftService.CrossBorderInstruction(
                CUSTOMER,
                PartyDetails.of("Payer", new Iban("EE717700771001735865")),
                PartyDetails.of("Payee", new Iban("DE04500700100532013000")),
                "123", "BR", Money.of("EUR", "100.00"), "USD", "ref",
                CreditTransfer.ChargeBearer.SHAR);

        SwiftService.CrossBorderQuote quote = swift.quote(unreachable);

        assertFalse(quote.routable());
        assertTrue(quote.explanation().contains("no correspondent route"), quote.explanation());
    }

    @Test
    void anUnsupportedTargetCurrencyIsNotQuoted() {
        SwiftService.CrossBorderInstruction exotic = new SwiftService.CrossBorderInstruction(
                CUSTOMER,
                PartyDetails.of("Payer", new Iban("EE717700771001735865")),
                PartyDetails.of("Payee", new Iban("DE04500700100532013000")),
                "123", "US", Money.of("EUR", "100.00"), "ZWL", "ref",
                CreditTransfer.ChargeBearer.SHAR);

        assertFalse(swift.quote(exotic).routable());
    }

    @Test
    void aMultiHopRouteAccumulatesTransitTime() {
        // Reaching Japan in yen goes through New York first; the dollar is the vehicle
        // currency for a great deal of trade involving neither party.
        SwiftService.CrossBorderInstruction toJapan = new SwiftService.CrossBorderInstruction(
                CUSTOMER,
                PartyDetails.of("Payer", new Iban("EE717700771001735865")),
                PartyDetails.of("Payee", new Iban("DE04500700100532013000")),
                "123", "JP", Money.of("EUR", "1000.00"), "JPY", "ref",
                CreditTransfer.ChargeBearer.SHAR);

        SwiftService.CrossBorderQuote quote = swift.quote(toJapan);

        assertTrue(quote.routable());
        assertEquals(List.of("CHASUS33", "MHCBJPJT"), quote.correspondentChain());
        assertEquals(60, quote.expectedTransit().toHours(), "24h plus 36h — days, not seconds");
    }

    @Test
    void payingAllChargesOurselvesMeansNothingIsTakenOutInTransit() {
        SwiftService.CrossBorderInstruction senderPaysAll = new SwiftService.CrossBorderInstruction(
                CUSTOMER,
                PartyDetails.of("Payer", new Iban("EE717700771001735865")),
                PartyDetails.of("Payee", new Iban("DE04500700100532013000")),
                "123", "US", Money.of("EUR", "1000.00"), "USD", "ref",
                CreditTransfer.ChargeBearer.DEBT);

        SwiftService.CrossBorderQuote quote = swift.quote(senderPaysAll);

        assertEquals(Money.zero("USD"), quote.deductedCharges());
    }

    // --- Sending ----------------------------------------------------------

    @Test
    void sendingDebitsThePayerAndHoldsTheMoneyInTransit() {
        Money fundsBefore = bookkeeper.customerFunds(CUSTOMER, "EUR");

        SwiftService.CrossBorderResult result = swift.send(toUnitedStates("5000.00"));

        assertTrue(result.accepted(), result.message());
        // Booked like any other outbound payment: the payer's balance goes down now,
        // and the money sits in transit until the far end confirms.
        assertEquals(fundsBefore.minus(Money.of("EUR", "5030.00")),
                bookkeeper.customerFunds(CUSTOMER, "EUR"));
        assertEquals(Money.of("EUR", "5000.00"), bookkeeper.paymentsInTransit("EUR"));
        assertTrue(ledger.trialBalance().balances());
    }

    @Test
    void confirmationClearsTheInTransitLiability() {
        swift.send(toUnitedStates("5000.00"));
        String reference = swift.send(toUnitedStates("1000.00")).reference();

        swift.confirmArrival(reference, Money.of("EUR", "1000.00"));

        assertEquals(Money.of("EUR", "5000.00"), bookkeeper.paymentsInTransit("EUR"),
                "only the confirmed one has cleared");
        assertTrue(ledger.trialBalance().balances());
    }

    @Test
    void anUnfundedNostroStopsThePaymentRegardlessOfTheInstruction() {
        // Liquidity management is a real operational constraint in correspondent
        // banking: the payment cannot go if the account is not pre-funded, however
        // good the instruction and however rich the payer.
        bookkeeper.recordCustomerDeposit(CUSTOMER, "top-up", Money.of("EUR", "5000000.00"));
        CorrespondentNetwork.Correspondent chase = network.find("CHASUS33").orElseThrow();
        chase.debitNostro(chase.nostroBalance());
        int entriesBefore = ledger.size();

        SwiftService.CrossBorderResult result = swift.send(toUnitedStates("1000.00"));

        assertFalse(result.accepted());
        assertTrue(result.message().contains("nostro account"), result.message());
        assertEquals(entriesBefore, ledger.size(), "a refused payment books nothing");
    }

    @Test
    void aPayerWithoutTheFundsIsRefusedBeforeAnythingIsBooked() {
        // The customer was funded with 100,000; this needs 500,000 plus fees.
        int entriesBefore = ledger.size();

        SwiftService.CrossBorderResult result = swift.send(toUnitedStates("500000.00"));

        assertFalse(result.accepted());
        assertTrue(result.message().contains("is short of"), result.message());
        assertEquals(entriesBefore, ledger.size(),
                "the ledger must never show a customer balance that went negative");
    }

    @Test
    void anUnroutablePaymentIsRejectedWithItsReason() {
        SwiftService.CrossBorderInstruction unreachable = new SwiftService.CrossBorderInstruction(
                CUSTOMER,
                PartyDetails.of("Payer", new Iban("EE717700771001735865")),
                PartyDetails.of("Payee", new Iban("DE04500700100532013000")),
                "123", "BR", Money.of("EUR", "100.00"), "USD", "ref",
                CreditTransfer.ChargeBearer.SHAR);

        SwiftService.CrossBorderResult result = swift.send(unreachable);

        assertFalse(result.accepted());
        assertTrue(result.mt103() == null);
    }

    // --- MT103 formatting -------------------------------------------------

    @Test
    void theMt103UsesTheTagsAndConventionsTheFormatRequires() {
        String mt103 = swift.send(toUnitedStates("5000.00")).mt103();

        assertTrue(mt103.startsWith("{1:F01"));
        assertTrue(mt103.contains("{2:I103"));
        assertTrue(mt103.contains(":23B:CRED"));
        // Value date, currency, settled amount — with a comma decimal separator and
        // no thousands separator, which is a frequent integration bug.
        assertTrue(mt103.contains(":32A:"), mt103);
        assertTrue(mt103.contains("USD5382,87"), mt103);
        // The original instructed amount before conversion, and the rate applied.
        assertTrue(mt103.contains(":33B:EUR5000,00"), mt103);
        assertTrue(mt103.contains(":36:1,079575"), mt103);
        assertTrue(mt103.contains(":50K:/EE717700771001735865"));
        assertTrue(mt103.contains(":57A:CHASUS33"));
        assertTrue(mt103.contains(":59:/0123456789"));
        assertTrue(mt103.contains(":70:Invoice US-4471"));
        assertTrue(mt103.contains(":71A:SHA"), "shared charges");
        assertTrue(mt103.endsWith("-}"));
    }

    @Test
    void aSameCurrencyPaymentOmitsTheConversionTags() {
        SwiftService.CrossBorderInstruction euroToGermany = new SwiftService.CrossBorderInstruction(
                CUSTOMER,
                PartyDetails.of("Payer", new Iban("EE717700771001735865")),
                PartyDetails.of("Payee", new Iban("DE04500700100532013000")),
                "123", "DE", Money.of("EUR", "1000.00"), "EUR", "ref",
                CreditTransfer.ChargeBearer.SHAR);

        String mt103 = swift.send(euroToGermany).mt103();

        assertFalse(mt103.contains(":33B:"), "no conversion, so no original amount to state");
        assertFalse(mt103.contains(":36:"));
    }

    @Test
    void aMultiHopPaymentNamesTheIntermediary() {
        SwiftService.CrossBorderInstruction toJapan = new SwiftService.CrossBorderInstruction(
                CUSTOMER,
                PartyDetails.of("Payer", new Iban("EE717700771001735865")),
                PartyDetails.of("Payee", new Iban("DE04500700100532013000")),
                "123", "JP", Money.of("EUR", "1000.00"), "JPY", "ref",
                CreditTransfer.ChargeBearer.SHAR);

        String mt103 = swift.send(toJapan).mt103();

        assertTrue(mt103.contains(":56A:CHASUS33"), "the intermediary");
        assertTrue(mt103.contains(":57A:MHCBJPJT"), "the account-with institution");
    }

    @Test
    void chargeBearerMapsToTheRightMt103Code() {
        assertTrue(swift.send(toUnitedStates("100.00")).mt103().contains(":71A:SHA"));

        SwiftService.CrossBorderInstruction beneficiaryPays =
                new SwiftService.CrossBorderInstruction(CUSTOMER,
                        PartyDetails.of("Payer", new Iban("EE717700771001735865")),
                        PartyDetails.of("Payee", new Iban("DE04500700100532013000")),
                        "123", "US", Money.of("EUR", "100.00"), "USD", "ref",
                        CreditTransfer.ChargeBearer.CRED);
        assertTrue(swift.send(beneficiaryPays).mt103().contains(":71A:BEN"));
    }

    // --- FX ---------------------------------------------------------------

    @Test
    void ratesCrossThroughTheEuro() {
        BigDecimal eurToUsd = fxRates.midRate("EUR", "USD");
        BigDecimal usdToEur = fxRates.midRate("USD", "EUR");

        assertEquals(new BigDecimal("1.08500000"), eurToUsd);
        // Round-tripping a rate should land back near one.
        assertTrue(eurToUsd.multiply(usdToEur).subtract(BigDecimal.ONE).abs()
                .compareTo(new BigDecimal("0.0001")) < 0);
    }

    @Test
    void conversionRoundsDownSoRoundingCannotCreateMoney() {
        // 10.00 EUR at 1.085 is 10.85 USD exactly; 10.01 would be 10.86085, which must
        // round down rather than to nearest.
        FxRates.FxQuote quote = fxRates.quote(Money.of("EUR", "10.01"), "USD");

        assertTrue(quote.converted().isLessThan(Money.of("USD", "10.86")),
                "rounding must never pay out more than the conversion produced");
    }

    @Test
    void aSameCurrencyQuoteIsAnIdentityWithNoSpread() {
        FxRates.FxQuote quote = fxRates.quote(Money.of("EUR", "100.00"), "EUR");

        assertFalse(quote.isConversion());
        assertEquals(Money.of("EUR", "100.00"), quote.converted());
        assertEquals(Money.zero("EUR"), quote.spreadCost());
        assertEquals(0, quote.spreadBasisPoints());
    }

    @Test
    void currenciesWithoutMinorUnitsConvertCorrectly() {
        FxRates.FxQuote quote = fxRates.quote(Money.of("EUR", "100.00"), "JPY");

        // 100 EUR at 162.40 less 50 bps is 16,158 yen, in whole yen.
        assertEquals("JPY", quote.converted().currency());
        assertEquals(16_158, quote.converted().minorUnits());
    }

    @Test
    void theSpreadIsConfigurable() {
        fxRates.setSpreadBasisPoints(0);

        FxRates.FxQuote noSpread = fxRates.quote(Money.of("EUR", "1000.00"), "USD");

        assertEquals(noSpread.midRate(), noSpread.clientRate());
        assertEquals(Money.zero("EUR"), noSpread.spreadCost());
    }

    // --- Correspondent network -------------------------------------------

    @Test
    void theNostroIsDebitedWhenAPaymentGoesOut() {
        CorrespondentNetwork.Correspondent chase =
                network.find("CHASUS33").orElseThrow();
        Money before = chase.nostroBalance();

        swift.send(toUnitedStates("1000.00"));

        assertTrue(chase.nostroBalance().isLessThan(before),
                "money has to be pre-funded in every nostro, sitting idle");
    }

    @Test
    void onlyFeeDeductingCorrespondentsTakeFromThePrincipal() {
        assertTrue(network.find("CHASUS33").orElseThrow().deductsFees());
        assertFalse(network.find("DEUTDEFF").orElseThrow().deductsFees());
        assertEquals(Money.of("USD", "15.00"),
                network.deductedFeesFor(network.chainTo("US", "USD"), "USD"));
    }

    @Test
    void theFinalHopMustBeAbleToPayInTheBeneficiarysCurrency() {
        assertTrue(network.chainTo("US", "USD").size() == 1);
        assertTrue(network.chainTo("US", "GBP").isEmpty(),
                "Chase is in the route for dollars, not sterling");
    }
}
