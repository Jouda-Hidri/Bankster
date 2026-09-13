package bankster.client.payments.rails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import bankster.client.payments.Money;
import bankster.client.payments.TestClock;
import bankster.client.payments.rails.BankStatement.StatementEntry;

/** Message generation and parsing, including the statement round trip recon needs. */
class Iso20022Test {

    private TestClock clock;
    private Iso20022 iso20022;

    @BeforeEach
    void setUp() {
        clock = TestClock.businessDayMorning();
        iso20022 = new Iso20022(clock);
    }

    private CreditTransfer transfer(String amount, PaymentRail rail) {
        CreditTransfer transfer = new CreditTransfer(
                "ct_1", "E2E-REF-0001",
                PartyDetails.of("Liis-Mari Männik", new Iban("EE717700771001735865"),
                        new Bic("LHVBEE22")),
                PartyDetails.of("Klaus Weber", new Iban("DE04500700100532013000"),
                        new Bic("DEUTDEFF")),
                Money.of("EUR", amount), Money.of("EUR", "0.50"),
                "Invoice 2026-0915", CreditTransfer.ChargeBearer.SLEV,
                LocalDate.of(2026, 9, 15), clock.instant());
        transfer.setRouting(new RailDecision(rail, rail, false, List.of(),
                clock.instant(), Money.of("EUR", "0.50"), "test"));
        return transfer;
    }

    // --- pain.001 ---------------------------------------------------------

    @Test
    void painOneCarriesTheMandatoryGroupHeaderAndControlSum() {
        String xml = iso20022.pain001("MSG-1", "Bankster Payments",
                List.of(transfer("100.00", PaymentRail.SEPA_SCT),
                        transfer("50.00", PaymentRail.SEPA_SCT)));

        assertTrue(xml.contains("urn:iso:std:iso:20022:tech:xsd:pain.001.001.09"));
        assertTrue(xml.contains("<MsgId>MSG-1</MsgId>"));
        assertTrue(xml.contains("<NbOfTxs>2</NbOfTxs>"));
        // The receiving bank checks this, so a truncated file is rejected whole.
        assertTrue(xml.contains("<CtrlSum>150.00</CtrlSum>"));
        assertTrue(xml.contains("<Nm>Bankster Payments</Nm>"));
    }

    @Test
    void theInstantRailIsRequestedWithLocalInstrumentInst() {
        String instant = iso20022.pain001("MSG-1", "Bankster",
                List.of(transfer("100.00", PaymentRail.SEPA_INST)));
        String standard = iso20022.pain001("MSG-2", "Bankster",
                List.of(transfer("100.00", PaymentRail.SEPA_SCT)));

        assertTrue(instant.contains("<LclInstrm>"), "INST is how the instant rail is asked for");
        assertTrue(instant.contains("<Cd>INST</Cd>"));
        assertFalse(standard.contains("<LclInstrm>"),
                "the same message without it is a standard transfer");
        assertTrue(standard.contains("<Cd>SEPA</Cd>"));
    }

    @Test
    void painOneCarriesThePartiesAndTheEndToEndReference() {
        String xml = iso20022.pain001("MSG-1", "Bankster",
                List.of(transfer("100.00", PaymentRail.SEPA_SCT)));

        assertTrue(xml.contains("<IBAN>EE717700771001735865</IBAN>"));
        assertTrue(xml.contains("<IBAN>DE04500700100532013000</IBAN>"));
        assertTrue(xml.contains("<BICFI>LHVBEE22</BICFI>"));
        assertTrue(xml.contains("<BICFI>DEUTDEFF</BICFI>"));
        assertTrue(xml.contains("<EndToEndId>E2E-REF-0001</EndToEndId>"));
        assertTrue(xml.contains("<Ustrd>Invoice 2026-0915</Ustrd>"));
        assertTrue(xml.contains("<ChrgBr>SLEV</ChrgBr>"));
        assertTrue(xml.contains("Ccy=\"EUR\">100.00<"));
    }

    @Test
    void anEmptyBatchIsRefusedRatherThanProducingAnEmptyFile() {
        assertThrows(IllegalArgumentException.class,
                () -> iso20022.pain001("MSG-1", "Bankster", List.of()));
    }

    // --- pacs.008 ---------------------------------------------------------

    @Test
    void pacsEightCarriesTheInterbankSettlementBlock() {
        String xml = iso20022.pacs008("PACS-1", transfer("100.00", PaymentRail.SEPA_INST), "TIPS");

        assertTrue(xml.contains("urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08"));
        // CLRG says the two banks settle through a clearing system rather than across
        // accounts they hold with each other.
        assertTrue(xml.contains("<SttlmMtd>CLRG</SttlmMtd>"));
        assertTrue(xml.contains("TIPS"));
        assertTrue(xml.contains("<IntrBkSttlmAmt Ccy=\"EUR\">100.00</IntrBkSttlmAmt>"));
        assertTrue(xml.contains("<TxId>ct_1</TxId>"));
        assertTrue(xml.contains("<EndToEndId>E2E-REF-0001</EndToEndId>"),
                "the payer's reference must pass through unchanged");
    }

    // --- camt.053 round trip ---------------------------------------------

    private BankStatement statement() {
        LocalDate day = LocalDate.of(2026, 9, 15);
        return new BankStatement("STMT-1", "EE717700771001735865", day, day,
                Money.of("EUR", "1000.00"), Money.of("EUR", "880.00"),
                List.of(
                        new StatementEntry("NTRY-1", Money.of("EUR", "150.00"), false, day, day,
                                "Klaus Weber", "DE04500700100532013000", "Invoice 2026-0915",
                                "E2E-REF-0001", "ESCT"),
                        new StatementEntry("NTRY-2", Money.of("EUR", "30.00"), true, day, day,
                                "Northbound Payments", null, "Card settlement",
                                "E2E-REF-0002", "RCDT")));
    }

    @Test
    void aStatementSurvivesGenerationAndParsingIntact() {
        BankStatement original = statement();

        BankStatement reparsed = iso20022.parseCamt053(iso20022.camt053(original));

        assertEquals(original.statementId(), reparsed.statementId());
        assertEquals(original.accountIban(), reparsed.accountIban());
        assertEquals(original.fromDate(), reparsed.fromDate());
        assertEquals(original.toDate(), reparsed.toDate());
        assertEquals(original.openingBalance(), reparsed.openingBalance());
        assertEquals(original.closingBalance(), reparsed.closingBalance());
        assertEquals(2, reparsed.entries().size());
    }

    @Test
    void everyFieldReconciliationNeedsSurvivesTheRoundTrip() {
        BankStatement reparsed = iso20022.parseCamt053(iso20022.camt053(statement()));
        StatementEntry debit = reparsed.debits().get(0);
        StatementEntry credit = reparsed.credits().get(0);

        assertEquals("NTRY-1", debit.entryReference());
        assertEquals(Money.of("EUR", "150.00"), debit.amount());
        assertFalse(debit.credit());
        assertEquals("Klaus Weber", debit.counterpartyName());
        assertEquals("DE04500700100532013000", debit.counterpartyIban());
        assertEquals("Invoice 2026-0915", debit.remittanceInformation());
        // The field that makes automatic matching possible at all.
        assertEquals("E2E-REF-0001", debit.endToEndId());
        assertEquals(LocalDate.of(2026, 9, 15), debit.bookingDate());

        assertTrue(credit.credit());
        assertEquals("Northbound Payments", credit.counterpartyName());
    }

    @Test
    void signedAmountsFollowTheCreditDebitIndicator() {
        BankStatement reparsed = iso20022.parseCamt053(iso20022.camt053(statement()));

        assertEquals(Money.of("EUR", "-150.00"), reparsed.debits().get(0).signedAmount());
        assertEquals(Money.of("EUR", "30.00"), reparsed.credits().get(0).signedAmount());
    }

    @Test
    void theStatementIsSelfCheckingOnItsOwnBalances() {
        BankStatement consistent = statement();
        assertEquals(Money.of("EUR", "880.00"), consistent.computedClosingBalance());
        assertTrue(consistent.isSelfConsistent());

        BankStatement truncated = new BankStatement(consistent.statementId(),
                consistent.accountIban(), consistent.fromDate(), consistent.toDate(),
                consistent.openingBalance(), consistent.closingBalance(),
                List.of(consistent.entries().get(0)));

        assertFalse(truncated.isSelfConsistent(),
                "a file missing entries must be detectable before it is reconciled against");
    }

    @Test
    void aNegativeBalanceRoundTripsWithItsSign() {
        LocalDate day = LocalDate.of(2026, 9, 15);
        BankStatement overdrawn = new BankStatement("STMT-2", "EE717700771001735865", day, day,
                Money.of("EUR", "-50.00"), Money.of("EUR", "-150.00"),
                List.of(new StatementEntry("NTRY-1", Money.of("EUR", "100.00"), false, day, day,
                        "Somebody", null, "payment", "E2E-1", "ESCT")));

        BankStatement reparsed = iso20022.parseCamt053(iso20022.camt053(overdrawn));

        assertEquals(Money.of("EUR", "-50.00"), reparsed.openingBalance());
        assertEquals(Money.of("EUR", "-150.00"), reparsed.closingBalance());
        assertTrue(reparsed.isSelfConsistent());
    }

    @Test
    void theBankTransactionCodeDistinguishesIssuedFromReceived() {
        String xml = iso20022.camt053(statement());

        assertTrue(xml.contains("<Cd>PMNT</Cd>"));
        assertTrue(xml.contains("<Cd>ICDT</Cd>"), "issued credit transfer");
        assertTrue(xml.contains("<Cd>RCDT</Cd>"), "received credit transfer");
    }

    // --- Parser hardening -------------------------------------------------

    @Test
    void theParserDoesNotResolveExternalEntities() {
        // A statement file arrives from outside. A default-configured parser would
        // happily read a local file here.
        String withDoctype = """
                <?xml version="1.0"?>
                <!DOCTYPE Document [ <!ENTITY secret SYSTEM "file:///etc/passwd"> ]>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.08">
                  <BkToCstmrStmt><Stmt><Id>&secret;</Id></Stmt></BkToCstmrStmt>
                </Document>
                """;

        assertThrows(IllegalArgumentException.class, () -> iso20022.parseCamt053(withDoctype));
    }

    @Test
    void nonsenseInputIsRejectedWithAClearFailure() {
        assertThrows(IllegalArgumentException.class, () -> iso20022.parseCamt053("not xml"));
        assertThrows(IllegalArgumentException.class,
                () -> iso20022.parseCamt053("<Document></Document>"));
    }

    @Test
    void theParserIsNotStrictAboutTheCamtVersionNamespace() {
        // The camt version in use varies by bank and country; being strict here means
        // a parser that breaks whenever a bank upgrades.
        String olderVersion = iso20022.camt053(statement())
                .replace("camt.053.001.08", "camt.053.001.02");

        BankStatement reparsed = iso20022.parseCamt053(olderVersion);
        assertEquals(2, reparsed.entries().size());
    }

    @Test
    void entriesWithoutOptionalDetailStillParse() {
        String minimal = """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.08">
                  <BkToCstmrStmt>
                    <Stmt>
                      <Id>STMT-MIN</Id>
                      <Acct><Id><IBAN>EE717700771001735865</IBAN></Id><Ccy>EUR</Ccy></Acct>
                      <Ntry>
                        <Amt Ccy="EUR">12.34</Amt>
                        <CdtDbtInd>CRDT</CdtDbtInd>
                      </Ntry>
                    </Stmt>
                  </BkToCstmrStmt>
                </Document>
                """;

        BankStatement parsed = iso20022.parseCamt053(minimal);

        assertEquals("STMT-MIN", parsed.statementId());
        assertEquals(1, parsed.entries().size());
        assertEquals(Money.of("EUR", "12.34"), parsed.entries().get(0).amount());
        assertTrue(parsed.entries().get(0).credit());
        assertEquals(Money.zero("EUR"), parsed.openingBalance(), "absent balances default to zero");
    }
}
