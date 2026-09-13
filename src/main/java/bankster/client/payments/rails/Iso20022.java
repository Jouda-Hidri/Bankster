package bankster.client.payments.rails;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import bankster.client.payments.Money;
import bankster.client.payments.rails.BankStatement.StatementEntry;

/**
 * ISO 20022 message generation and parsing.
 *
 * <p>ISO 20022 is the common language of modern payments, and it replaced a
 * generation of formats — MT messages, national batch formats, bilateral CSVs —
 * for reasons that are worth stating, because they explain why migrating to it is
 * worth the cost:
 *
 * <ul>
 *   <li><b>Structured data.</b> An MT103 has a 140-character free-text field for
 *       remittance information; ISO 20022 has typed fields for invoice numbers,
 *       tax references and structured creditor references. That is the difference
 *       between a human reading a bank statement to work out which invoice was
 *       paid and software doing it.</li>
 *   <li><b>One model, many messages.</b> The same party, account and amount
 *       components appear in initiation ({@code pain}), interbank clearing
 *       ({@code pacs}) and reporting ({@code camt}), so a payment can be followed
 *       from instruction to statement without translating between formats at each
 *       step.</li>
 *   <li><b>Richer diagnostics.</b> Rejections carry standard reason codes rather
 *       than prose, which is what lets a sender distinguish "retry when funded"
 *       from "this account no longer exists" automatically.</li>
 * </ul>
 *
 * <p>Three messages are implemented here, one from each family: {@code pain.001}
 * to instruct, {@code pacs.008} to clear, {@code camt.053} to report. The
 * {@code camt.053} parser is what feeds reconciliation.
 *
 * <p>The XML parser is configured with secure processing and external entity
 * resolution disabled. A camt.053 arrives from outside the system, and an XML
 * parser left at its defaults is an XXE vulnerability that can read local files.
 */
@Component
public class Iso20022 {

    private static final String PAIN_001_NAMESPACE = "urn:iso:std:iso:20022:tech:xsd:pain.001.001.09";
    private static final String PACS_008_NAMESPACE = "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08";
    private static final String CAMT_053_NAMESPACE = "urn:iso:std:iso:20022:tech:xsd:camt.053.001.08";

    private static final DateTimeFormatter ISO_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final Clock clock;

    public Iso20022(Clock clock) {
        this.clock = clock;
    }

    // --- pain.001: customer credit transfer initiation --------------------

    /**
     * Builds a {@code pain.001} — the message a customer or corporate sends its
     * bank to instruct payments.
     *
     * <p>Two details carry real meaning. {@code CtrlSum} is the sum of all
     * instructed amounts, and the receiving bank checks it: a file whose control
     * sum does not match its contents is rejected whole, which is the protection
     * against a truncated upload being executed in part. And {@code LclInstrm/Cd}
     * set to {@code INST} is how SEPA Instant is actually requested — the same
     * message with that element absent is a standard transfer, which is the
     * instruction-level counterpart of the routing decision.
     */
    public String pain001(String messageId, String initiatingParty, List<CreditTransfer> transfers) {
        if (transfers.isEmpty()) {
            throw new IllegalArgumentException("A pain.001 needs at least one transfer");
        }
        Document document = newDocument();
        Element root = document.createElementNS(PAIN_001_NAMESPACE, "Document");
        document.appendChild(root);

        Element initiation = child(root, "CstmrCdtTrfInitn");

        Money controlSum = totalOf(transfers);
        Element groupHeader = child(initiation, "GrpHdr");
        text(groupHeader, "MsgId", messageId);
        text(groupHeader, "CreDtTm", nowAsIsoDateTime());
        text(groupHeader, "NbOfTxs", String.valueOf(transfers.size()));
        text(groupHeader, "CtrlSum", controlSum.toDecimal().toPlainString());
        text(child(groupHeader, "InitgPty"), "Nm", initiatingParty);

        // One PmtInf block per (rail, execution date, debtor) grouping. The demo
        // instructs a single debtor, so one block carries the batch.
        CreditTransfer first = transfers.get(0);
        Element paymentInfo = child(initiation, "PmtInf");
        text(paymentInfo, "PmtInfId", messageId + "-1");
        text(paymentInfo, "PmtMtd", "TRF");
        text(paymentInfo, "BtchBookg", "false");
        text(paymentInfo, "NbOfTxs", String.valueOf(transfers.size()));
        text(paymentInfo, "CtrlSum", controlSum.toDecimal().toPlainString());

        Element paymentType = child(paymentInfo, "PmtTpInf");
        text(child(paymentType, "SvcLvl"), "Cd", "SEPA");
        if (first.rail() == PaymentRail.SEPA_INST) {
            // The element that asks for the instant rail rather than the batch one.
            text(child(paymentType, "LclInstrm"), "Cd", "INST");
        }

        text(child(paymentInfo, "ReqdExctnDt"), "Dt", first.requestedExecutionDate().toString());

        text(child(paymentInfo, "Dbtr"), "Nm", first.debtor().name());
        text(child(child(paymentInfo, "DbtrAcct"), "Id"), "IBAN", first.debtor().iban().value());
        first.debtor().bic().ifPresent(bic ->
                text(child(child(paymentInfo, "DbtrAgt"), "FinInstnId"), "BICFI", bic.value()));
        text(paymentInfo, "ChrgBr", first.chargeBearer().name());

        for (CreditTransfer transfer : transfers) {
            Element transaction = child(paymentInfo, "CdtTrfTxInf");
            Element paymentId = child(transaction, "PmtId");
            text(paymentId, "InstrId", transfer.transferId());
            text(paymentId, "EndToEndId", transfer.endToEndId());

            Element instructedAmount = child(child(transaction, "Amt"), "InstdAmt");
            instructedAmount.setAttribute("Ccy", transfer.amount().currency());
            instructedAmount.setTextContent(transfer.amount().toDecimal().toPlainString());

            transfer.creditor().bic().ifPresent(bic ->
                    text(child(child(transaction, "CdtrAgt"), "FinInstnId"), "BICFI", bic.value()));
            text(child(transaction, "Cdtr"), "Nm", transfer.creditor().name());
            text(child(child(transaction, "CdtrAcct"), "Id"), "IBAN", transfer.creditor().iban().value());

            if (transfer.remittanceInformation() != null && !transfer.remittanceInformation().isBlank()) {
                text(child(transaction, "RmtInf"), "Ustrd", transfer.remittanceInformation());
            }
        }

        return serialise(document);
    }

    // --- pacs.008: financial institution credit transfer ------------------

    /**
     * Builds a {@code pacs.008} — the interbank message that actually clears a
     * transfer between institutions.
     *
     * <p>The difference from {@code pain.001} is the settlement block.
     * {@code SttlmMtd} of {@code CLRG} says the two banks settle through a
     * clearing system rather than across accounts they hold with each other, and
     * the {@code ClrSys} code names which one. It is also where
     * {@code IntrBkSttlmAmt} appears — the amount moving between the banks, which
     * is not necessarily the amount the customer instructed once charges are
     * involved.
     */
    public String pacs008(String messageId, CreditTransfer transfer, String clearingSystem) {
        Document document = newDocument();
        Element root = document.createElementNS(PACS_008_NAMESPACE, "Document");
        document.appendChild(root);

        Element credit = child(root, "FIToFICstmrCdtTrf");

        Element groupHeader = child(credit, "GrpHdr");
        text(groupHeader, "MsgId", messageId);
        text(groupHeader, "CreDtTm", nowAsIsoDateTime());
        text(groupHeader, "NbOfTxs", "1");
        Element totalAmount = child(groupHeader, "TtlIntrBkSttlmAmt");
        totalAmount.setAttribute("Ccy", transfer.amount().currency());
        totalAmount.setTextContent(transfer.amount().toDecimal().toPlainString());
        text(child(groupHeader, "IntrBkSttlmDt"), "Dt", transfer.requestedExecutionDate().toString());

        Element settlement = child(groupHeader, "SttlmInf");
        text(settlement, "SttlmMtd", "CLRG");
        text(child(child(settlement, "ClrSys"), "Cd"), "Cd", clearingSystem);

        Element transaction = child(credit, "CdtTrfTxInf");
        Element paymentId = child(transaction, "PmtId");
        text(paymentId, "InstrId", transfer.transferId());
        text(paymentId, "EndToEndId", transfer.endToEndId());
        text(paymentId, "TxId", transfer.transferId());

        Element settlementAmount = child(transaction, "IntrBkSttlmAmt");
        settlementAmount.setAttribute("Ccy", transfer.amount().currency());
        settlementAmount.setTextContent(transfer.amount().toDecimal().toPlainString());

        text(transaction, "ChrgBr", transfer.chargeBearer().name());

        transfer.debtor().bic().ifPresent(bic ->
                text(child(child(transaction, "DbtrAgt"), "FinInstnId"), "BICFI", bic.value()));
        text(child(transaction, "Dbtr"), "Nm", transfer.debtor().name());
        text(child(child(transaction, "DbtrAcct"), "Id"), "IBAN", transfer.debtor().iban().value());

        transfer.creditor().bic().ifPresent(bic ->
                text(child(child(transaction, "CdtrAgt"), "FinInstnId"), "BICFI", bic.value()));
        text(child(transaction, "Cdtr"), "Nm", transfer.creditor().name());
        text(child(child(transaction, "CdtrAcct"), "Id"), "IBAN", transfer.creditor().iban().value());

        if (transfer.remittanceInformation() != null && !transfer.remittanceInformation().isBlank()) {
            text(child(transaction, "RmtInf"), "Ustrd", transfer.remittanceInformation());
        }

        return serialise(document);
    }

    // --- camt.053: bank to customer statement ----------------------------

    /**
     * Builds a {@code camt.053} end-of-day statement.
     *
     * <p>Generated here so that reconciliation can be demonstrated against a real
     * message rather than an internal object: the parser that reads it is the same
     * one that would read a bank's file.
     */
    public String camt053(BankStatement statement) {
        Document document = newDocument();
        Element root = document.createElementNS(CAMT_053_NAMESPACE, "Document");
        document.appendChild(root);

        Element report = child(root, "BkToCstmrStmt");
        Element groupHeader = child(report, "GrpHdr");
        text(groupHeader, "MsgId", statement.statementId());
        text(groupHeader, "CreDtTm", nowAsIsoDateTime());

        Element stmt = child(report, "Stmt");
        text(stmt, "Id", statement.statementId());
        text(stmt, "CreDtTm", nowAsIsoDateTime());

        Element period = child(stmt, "FrToDt");
        text(period, "FrDtTm", statement.fromDate().atStartOfDay().format(ISO_DATE_TIME));
        text(period, "ToDtTm", statement.toDate().atTime(23, 59, 59).format(ISO_DATE_TIME));

        Element account = child(stmt, "Acct");
        text(child(account, "Id"), "IBAN", statement.accountIban());
        text(account, "Ccy", statement.openingBalance().currency());

        appendBalance(stmt, "OPBD", statement.openingBalance(), statement.fromDate());
        appendBalance(stmt, "CLBD", statement.closingBalance(), statement.toDate());

        for (StatementEntry entry : statement.entries()) {
            appendEntry(stmt, entry);
        }

        return serialise(document);
    }

    private void appendBalance(Element statement, String code, Money amount, LocalDate date) {
        Element balance = child(statement, "Bal");
        text(child(child(balance, "Tp"), "CdOrPrtry"), "Cd", code);
        Element amountElement = child(balance, "Amt");
        amountElement.setAttribute("Ccy", amount.currency());
        amountElement.setTextContent(amount.abs().toDecimal().toPlainString());
        text(balance, "CdtDbtInd", amount.isNegative() ? "DBIT" : "CRDT");
        text(child(balance, "Dt"), "Dt", date.toString());
    }

    private void appendEntry(Element statement, StatementEntry entry) {
        Element element = child(statement, "Ntry");
        text(element, "NtryRef", entry.entryReference());
        Element amount = child(element, "Amt");
        amount.setAttribute("Ccy", entry.amount().currency());
        amount.setTextContent(entry.amount().toDecimal().toPlainString());
        text(element, "CdtDbtInd", entry.credit() ? "CRDT" : "DBIT");
        text(child(element, "Sts"), "Cd", "BOOK");
        text(child(element, "BookgDt"), "Dt", entry.bookingDate().toString());
        text(child(element, "ValDt"), "Dt", entry.valueDate().toString());

        // Bank transaction code: domain, family, sub-family. PMNT/ICDT/ESCT is a
        // SEPA credit transfer issued; RCDT is one received.
        Element domain = child(child(element, "BkTxCd"), "Domn");
        text(domain, "Cd", "PMNT");
        Element family = child(domain, "Fmly");
        text(family, "Cd", entry.credit() ? "RCDT" : "ICDT");
        text(family, "SubFmlyCd", entry.bankTransactionCode() == null ? "ESCT" : entry.bankTransactionCode());

        Element details = child(child(element, "NtryDtls"), "TxDtls");
        if (entry.endToEndId() != null) {
            text(child(details, "Refs"), "EndToEndId", entry.endToEndId());
        }
        Element parties = child(details, "RltdPties");
        String partyElement = entry.credit() ? "Dbtr" : "Cdtr";
        String accountElement = entry.credit() ? "DbtrAcct" : "CdtrAcct";
        if (entry.counterpartyName() != null) {
            text(child(parties, partyElement), "Nm", entry.counterpartyName());
        }
        if (entry.counterpartyIban() != null) {
            text(child(child(parties, accountElement), "Id"), "IBAN", entry.counterpartyIban());
        }
        if (entry.remittanceInformation() != null) {
            text(child(details, "RmtInf"), "Ustrd", entry.remittanceInformation());
        }
    }

    /**
     * Parses a {@code camt.053} into a statement.
     *
     * <p>Namespace-agnostic on element names, because the camt version in use
     * varies by bank and by country and the element names that matter have not
     * changed across versions. Being strict about the namespace here would mean a
     * parser that breaks every time a bank upgrades.
     */
    public BankStatement parseCamt053(String xml) {
        Document document = parse(xml);

        Element statement = firstElement(document.getDocumentElement(), "Stmt")
                .orElseThrow(() -> new IllegalArgumentException("No Stmt element — not a camt.053"));

        String statementId = textOf(statement, "Id").orElse("unknown");
        String accountIban = firstElement(statement, "Acct")
                .flatMap(account -> firstElement(account, "Id"))
                .flatMap(id -> textOf(id, "IBAN"))
                .orElseThrow(() -> new IllegalArgumentException("Statement has no account IBAN"));
        String currency = firstElement(statement, "Acct")
                .flatMap(account -> textOf(account, "Ccy"))
                .orElse("EUR");

        Money opening = balanceOf(statement, "OPBD", currency);
        Money closing = balanceOf(statement, "CLBD", currency);

        LocalDate from = firstElement(statement, "FrToDt")
                .flatMap(period -> textOf(period, "FrDtTm"))
                .map(value -> LocalDate.parse(value.substring(0, 10)))
                .orElse(LocalDate.now(clock));
        LocalDate to = firstElement(statement, "FrToDt")
                .flatMap(period -> textOf(period, "ToDtTm"))
                .map(value -> LocalDate.parse(value.substring(0, 10)))
                .orElse(from);

        List<StatementEntry> entries = new ArrayList<>();
        for (Element entry : elements(statement, "Ntry")) {
            entries.add(parseEntry(entry, currency));
        }

        return new BankStatement(statementId, accountIban, from, to, opening, closing, entries);
    }

    private StatementEntry parseEntry(Element entry, String fallbackCurrency) {
        Element amountElement = firstElement(entry, "Amt")
                .orElseThrow(() -> new IllegalArgumentException("Entry has no amount"));
        String currency = amountElement.getAttribute("Ccy").isBlank()
                ? fallbackCurrency
                : amountElement.getAttribute("Ccy");
        Money amount = Money.of(currency, amountElement.getTextContent().trim());
        boolean credit = textOf(entry, "CdtDbtInd").map("CRDT"::equals).orElse(true);

        LocalDate bookingDate = firstElement(entry, "BookgDt")
                .flatMap(date -> textOf(date, "Dt"))
                .map(LocalDate::parse)
                .orElse(LocalDate.now(clock));
        LocalDate valueDate = firstElement(entry, "ValDt")
                .flatMap(date -> textOf(date, "Dt"))
                .map(LocalDate::parse)
                .orElse(bookingDate);

        Optional<Element> details = firstElement(entry, "NtryDtls")
                .flatMap(nested -> firstElement(nested, "TxDtls"));

        String endToEndId = details
                .flatMap(txDetails -> firstElement(txDetails, "Refs"))
                .flatMap(refs -> textOf(refs, "EndToEndId"))
                .orElse(null);

        Optional<Element> parties = details.flatMap(txDetails -> firstElement(txDetails, "RltdPties"));
        String counterpartyName = parties
                .flatMap(party -> firstElement(party, credit ? "Dbtr" : "Cdtr"))
                .flatMap(party -> textOf(party, "Nm"))
                .orElse(null);
        String counterpartyIban = parties
                .flatMap(party -> firstElement(party, credit ? "DbtrAcct" : "CdtrAcct"))
                .flatMap(account -> firstElement(account, "Id"))
                .flatMap(id -> textOf(id, "IBAN"))
                .orElse(null);

        String remittance = details
                .flatMap(txDetails -> firstElement(txDetails, "RmtInf"))
                .flatMap(info -> textOf(info, "Ustrd"))
                .orElse(null);

        String subFamily = firstElement(entry, "BkTxCd")
                .flatMap(code -> firstElement(code, "Domn"))
                .flatMap(domain -> firstElement(domain, "Fmly"))
                .flatMap(family -> textOf(family, "SubFmlyCd"))
                .orElse(null);

        return new StatementEntry(
                textOf(entry, "NtryRef").orElse(null),
                amount, credit, bookingDate, valueDate,
                counterpartyName, counterpartyIban, remittance, endToEndId, subFamily);
    }

    private Money balanceOf(Element statement, String code, String currency) {
        for (Element balance : elements(statement, "Bal")) {
            String balanceCode = firstElement(balance, "Tp")
                    .flatMap(type -> firstElement(type, "CdOrPrtry"))
                    .flatMap(codeOrProprietary -> textOf(codeOrProprietary, "Cd"))
                    .orElse("");
            if (!code.equals(balanceCode)) {
                continue;
            }
            Element amountElement = firstElement(balance, "Amt").orElseThrow();
            String balanceCurrency = amountElement.getAttribute("Ccy").isBlank()
                    ? currency : amountElement.getAttribute("Ccy");
            Money amount = Money.of(balanceCurrency, amountElement.getTextContent().trim());
            boolean debit = textOf(balance, "CdtDbtInd").map("DBIT"::equals).orElse(false);
            return debit ? amount.negate() : amount;
        }
        return Money.zero(currency);
    }

    // --- DOM helpers ------------------------------------------------------

    private Document newDocument() {
        try {
            return secureFactory().newDocumentBuilder().newDocument();
        } catch (Exception e) {
            throw new IllegalStateException("Could not create an XML document", e);
        }
    }

    private Document parse(String xml) {
        try {
            DocumentBuilder builder = secureFactory().newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not parse the ISO 20022 message: " + e.getMessage(), e);
        }
    }

    /**
     * A parser that will not fetch external entities.
     *
     * <p>Statement files arrive from outside. A default-configured parser will
     * happily resolve a {@code <!DOCTYPE>} pointing at {@code /etc/passwd} or at a
     * URL, which turns reading a bank file into arbitrary file disclosure and a
     * denial-of-service vector.
     */
    private DocumentBuilderFactory secureFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(true);
        return factory;
    }

    private String serialise(Document document) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(writer));
            return writer.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise the ISO 20022 message", e);
        }
    }

    private Element child(Element parent, String name) {
        Element element = parent.getOwnerDocument().createElementNS(
                parent.getNamespaceURI(), name);
        parent.appendChild(element);
        return element;
    }

    private void text(Element parent, String name, String value) {
        Element element = child(parent, name);
        element.setTextContent(value);
    }

    /** First descendant with this local name, at any depth. */
    private Optional<Element> firstElement(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && localName.equals(node.getLocalName())) {
                return Optional.of((Element) node);
            }
        }
        // Not a direct child — search deeper.
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                Optional<Element> found = firstElement((Element) node, localName);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    /** Direct children with this local name. */
    private List<Element> elements(Element parent, String localName) {
        List<Element> found = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && localName.equals(node.getLocalName())) {
                found.add((Element) node);
            }
        }
        return found;
    }

    private Optional<String> textOf(Element parent, String localName) {
        return elements(parent, localName).stream()
                .findFirst()
                .map(element -> element.getTextContent().trim())
                .filter(value -> !value.isEmpty());
    }

    private String nowAsIsoDateTime() {
        return ZonedDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).format(ISO_DATE_TIME);
    }

    private Money totalOf(List<CreditTransfer> transfers) {
        Money total = Money.zero(transfers.get(0).amount().currency());
        for (CreditTransfer transfer : transfers) {
            total = total.plus(transfer.amount());
        }
        return total;
    }
}
