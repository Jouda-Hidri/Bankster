package bankster.client.payments.rails;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import bankster.client.payments.Money;
import bankster.client.payments.core.AuditTrail;
import bankster.client.payments.core.IdempotencyStore;
import bankster.client.payments.core.Outbox;
import bankster.client.payments.rails.CreditTransfer.ChargeBearer;
import bankster.client.payments.rails.SepaRouter.RailRequest;
import bankster.client.payments.rails.SepaRouter.Urgency;
import bankster.client.payments.risk.AmlService;
import bankster.client.payments.risk.NameMatching;

/**
 * Initiates and tracks outbound credit transfers.
 *
 * <p>The order of the checks is the substance of this class, and it is not
 * arbitrary. Each step is placed where it is because doing it later would be a
 * defect:
 *
 * <ol>
 *   <li><b>Format validation</b> first, because it is free and catches typos
 *       before anything irreversible happens.</li>
 *   <li><b>Sanctions and AML</b> second, ahead of every other substantive check.
 *       Screening is mandatory and the hit must be recorded whether or not the
 *       payment would have failed for some other reason — a sanctions match that
 *       is masked by an earlier rejection is a match that was never reported, and
 *       the reporting is the obligation.</li>
 *   <li><b>Confirmation of Payee</b> next. Checking the beneficiary name against
 *       the account has to happen before the payment leaves, because an instant
 *       transfer cannot be recalled once accepted — which is exactly why
 *       authorised push payment fraud targets instant rails.</li>
 *   <li><b>Funds</b> before booking, so the ledger never shows a customer
 *       balance that went negative.</li>
 *   <li><b>Routing</b> last, once the payment is known to be one we will
 *       actually send.</li>
 * </ol>
 *
 * <p>Settlement is then modelled honestly per rail: an instant transfer reaches
 * {@code ACSC} within seconds, while a standard transfer sits at {@code ACSP}
 * until its value date arrives and {@link #settleDueTransfers()} runs.
 */
@Service
public class SepaPaymentService {

    private static final Logger log = LoggerFactory.getLogger(SepaPaymentService.class);

    /** Similarity at which a beneficiary name is accepted as matching. */
    private static final double NAME_MATCH_THRESHOLD = 0.80;

    private final Clock clock;
    private final SepaRouter router;
    private final IbanBicDirectory bicDirectory;
    private final AmlService amlService;
    private final TransferBookkeeper bookkeeper;
    private final AuditTrail auditTrail;
    private final Outbox outbox;
    private final IdempotencyStore idempotency;

    private final Map<String, CreditTransfer> transfers = new ConcurrentHashMap<>();

    /** Beneficiary names known for an IBAN, for Confirmation of Payee. */
    private final Map<String, String> accountNames = new ConcurrentHashMap<>();

    public SepaPaymentService(Clock clock, SepaRouter router, IbanBicDirectory bicDirectory,
                             AmlService amlService, TransferBookkeeper bookkeeper,
                             AuditTrail auditTrail, Outbox outbox, IdempotencyStore idempotency) {
        this.clock = clock;
        this.router = router;
        this.bicDirectory = bicDirectory;
        this.amlService = amlService;
        this.bookkeeper = bookkeeper;
        this.auditTrail = auditTrail;
        this.outbox = outbox;
        this.idempotency = idempotency;
    }

    public record InitiateTransferCommand(
            String customerId,
            String debtorName,
            String debtorIban,
            String creditorName,
            String creditorIban,
            String creditorCountry,
            Money amount,
            String remittanceInformation,
            Urgency urgency,
            ChargeBearer chargeBearer,
            String endToEndId,
            String idempotencyKey) {

        /** The ordinary case: a euro transfer the payer would like to arrive now. */
        public static InitiateTransferCommand instant(String customerId, String debtorName, String debtorIban,
                                                      String creditorName, String creditorIban,
                                                      Money amount, String remittance, String idempotencyKey) {
            return new InitiateTransferCommand(customerId, debtorName, debtorIban, creditorName, creditorIban,
                    creditorIban.substring(0, 2), amount, remittance, Urgency.INSTANT, ChargeBearer.SLEV,
                    null, idempotencyKey);
        }

        String fingerprint() {
            return customerId + "|" + debtorIban + "|" + creditorIban + "|"
                    + amount.currency() + amount.minorUnits() + "|" + remittanceInformation;
        }
    }

    public record TransferResult(CreditTransfer transfer, boolean accepted, String message, boolean replayed) {

        /** True when the payer asked for instant and did not get it. */
        public boolean wasDowngraded() {
            return transfer.routing() != null && transfer.routing().downgraded();
        }
    }

    // --- Initiation -------------------------------------------------------

    public TransferResult initiate(InitiateTransferCommand command) {
        IdempotencyStore.Outcome<TransferResult> outcome = idempotency.execute(
                command.idempotencyKey(), command.fingerprint(), () -> doInitiate(command));
        TransferResult result = outcome.value();
        return outcome.replayed()
                ? new TransferResult(result.transfer(), result.accepted(), result.message(), true)
                : result;
    }

    private TransferResult doInitiate(InitiateTransferCommand command) {
        Instant now = clock.instant();

        // 1. Format. A transfer to a structurally invalid IBAN is rejected before
        // it can touch anything else.
        Iban.ValidationResult debtorCheck = Iban.validate(command.debtorIban());
        if (!debtorCheck.valid()) {
            return rejectedBeforeBooking(command, RejectionReason.AC01,
                    "payer IBAN: " + debtorCheck.reason(), now);
        }
        Iban.ValidationResult creditorCheck = Iban.validate(command.creditorIban());
        if (!creditorCheck.valid()) {
            return rejectedBeforeBooking(command, RejectionReason.AC01,
                    "beneficiary IBAN: " + creditorCheck.reason(), now);
        }

        Iban debtorIban = new Iban(command.debtorIban());
        Iban creditorIban = new Iban(command.creditorIban());
        Optional<Bic> creditorBic = bicDirectory.resolve(creditorIban);

        PartyDetails debtor = creditorBicOrPlain(command.debtorName(), debtorIban);
        PartyDetails creditor = creditorBic
                .map(bic -> PartyDetails.of(command.creditorName(), creditorIban, bic))
                .orElseGet(() -> PartyDetails.of(command.creditorName(), creditorIban));

        // 2. Sanctions and AML, ahead of every other substantive check, so that a
        // hit is always screened for and always recorded.
        AmlService.AmlAssessment aml = amlService.screenTransaction(new AmlService.MonitoredTransaction(
                command.customerId(), AmlService.Direction.DEBIT, command.amount(),
                command.creditorName(), command.creditorCountry(), now));
        if (aml.isBlocked()) {
            return rejectedBeforeBooking(command, RejectionReason.RR04,
                    "blocked by compliance: " + String.join(", ", aml.flags()), now);
        }

        // 3. Confirmation of Payee, before anything irreversible.
        Optional<String> nameMismatch = confirmPayee(creditorIban, command.creditorName());
        if (nameMismatch.isPresent()) {
            return rejectedBeforeBooking(command, RejectionReason.BE01, nameMismatch.get(), now);
        }

        // 4. Routing, so the fee is known before funds are checked.
        RailDecision routing = router.route(new RailRequest(
                debtorIban, creditorIban, creditorBic, command.amount(), command.urgency()));
        if (routing.rail() == PaymentRail.SWIFT && !creditorIban.isSepa()) {
            log.info("Transfer to {} leaves SEPA; routed to correspondent banking",
                    creditorIban.countryCode());
        }

        // 5. Funds. Checked against the payer's own balance, fee included.
        Money required = command.chargeBearer() == ChargeBearer.CRED
                ? command.amount()
                : command.amount().plus(routing.fee());
        Money available = bookkeeper.customerFunds(command.customerId(), command.amount().currency());
        if (available.isLessThan(required)) {
            return rejectedBeforeBooking(command, RejectionReason.AM04,
                    "balance " + available + " is short of the " + required + " required", now);
        }

        CreditTransfer transfer = new CreditTransfer(
                "ct_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                command.endToEndId() == null
                        ? "E2E-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase()
                        : command.endToEndId(),
                debtor, creditor, command.amount(), routing.fee(), command.remittanceInformation(),
                command.chargeBearer(), LocalDate.now(clock), now);
        transfer.setRouting(routing);
        transfers.put(transfer.transferId(), transfer);

        transfer.transitionTo(TransferStatus.TECHNICALLY_VALIDATED,
                "IBAN check digits and mandatory fields verified", now);
        transfer.transitionTo(TransferStatus.ACCEPTED,
                "payee confirmed, compliance cleared, funds available", now);

        bookkeeper.recordInstruction(transfer.transferId(), command.customerId(),
                command.amount(), routing.fee());

        transfer.transitionTo(TransferStatus.SETTLEMENT_IN_PROGRESS,
                "submitted on " + routing.rail().displayName(), now);

        auditTrail.record(command.customerId(), "transfer.instructed", transfer.transferId(), Map.of(
                "endToEndId", transfer.endToEndId(),
                "creditor", creditor.display(),
                "amount", command.amount().toString(),
                "rail", routing.rail().name(),
                "requestedRail", routing.requestedRail().name(),
                "downgraded", String.valueOf(routing.downgraded()),
                "routing", routing.explanation(),
                "expectedSettlement", routing.expectedSettlement().toString()));
        outbox.append("transfer.instructed", transfer.transferId(), Map.of(
                "customerId", command.customerId(),
                "amount", command.amount().toString(),
                "rail", routing.rail().name(),
                "downgraded", String.valueOf(routing.downgraded())));

        if (routing.downgraded()) {
            // The payer asked for something they did not get, so say so
            // explicitly rather than letting them assume.
            outbox.append("transfer.rail_downgraded", transfer.transferId(), Map.of(
                    "requested", routing.requestedRail().name(),
                    "actual", routing.rail().name(),
                    "reason", routing.rejections().isEmpty()
                            ? routing.explanation()
                            : routing.rejections().get(0).reason(),
                    "customerMessage", routing.customerFacingSummary()));
        }

        // An instant transfer settles within the scheme's ten-second window, so
        // there is nothing to wait for.
        if (routing.rail() == PaymentRail.SEPA_INST) {
            settle(transfer.transferId());
        }

        return new TransferResult(transfer, true, routing.customerFacingSummary(), false);
    }

    // --- Settlement -------------------------------------------------------

    /** Confirms settlement — driven by the rail's confirmation in production. */
    public CreditTransfer settle(String transferId) {
        CreditTransfer transfer = require(transferId);
        Instant now = clock.instant();

        if (transfer.status() != TransferStatus.SETTLEMENT_IN_PROGRESS) {
            return transfer;
        }
        bookkeeper.recordSettlement(transferId, transfer.amount());
        transfer.transitionTo(TransferStatus.SETTLED,
                "settled on " + transfer.rail().displayName(), now);

        auditTrail.record("rail", "transfer.settled", transferId, Map.of(
                "rail", transfer.rail().name(),
                "amount", transfer.amount().toString(),
                "credited", transfer.amountCredited().toString()));
        outbox.append("transfer.settled", transferId, Map.of(
                "amount", transfer.amount().toString(),
                "rail", transfer.rail().name()));
        return transfer;
    }

    /**
     * Settles every transfer whose value date has arrived. This is the batch
     * rails' equivalent of the instant rail's immediate confirmation.
     */
    public int settleDueTransfers() {
        Instant now = clock.instant();
        int settled = 0;
        for (CreditTransfer transfer : transfers.values()) {
            if (transfer.status() == TransferStatus.SETTLEMENT_IN_PROGRESS
                    && transfer.routing() != null
                    && !now.isBefore(transfer.routing().expectedSettlement())) {
                settle(transfer.transferId());
                settled++;
            }
        }
        return settled;
    }

    /**
     * Handles an R-transaction: the beneficiary's bank has sent the money back.
     *
     * <p>Only possible after settlement, and only on the non-instant rails as a
     * practical matter — an instant transfer is irrevocable, and the money can
     * only come back if the beneficiary agrees to a recall.
     */
    public CreditTransfer returnTransfer(String transferId, String customerId, RejectionReason reason) {
        CreditTransfer transfer = require(transferId);
        Instant now = clock.instant();

        bookkeeper.recordReturn(transferId, customerId, transfer.amount());
        transfer.transitionTo(TransferStatus.RETURNED,
                "returned by the beneficiary's bank: " + reason.code() + " " + reason.description(), now);

        auditTrail.record("beneficiary-bank", "transfer.returned", transferId, Map.of(
                "reasonCode", reason.code(),
                "reason", reason.description(),
                "amount", transfer.amount().toString(),
                "feeRetained", transfer.fee().toString()));
        outbox.append("transfer.returned", transferId, Map.of(
                "reasonCode", reason.code(),
                "amount", transfer.amount().toString()));
        return transfer;
    }

    // --- Confirmation of Payee --------------------------------------------

    /** Registers the name on an account, as a directory or the scheme would supply it. */
    public void registerAccountName(String iban, String accountHolderName) {
        accountNames.put(new Iban(iban).value(), accountHolderName);
    }

    /**
     * Compares the name the payer typed against the name on the account.
     *
     * <p>Returns the mismatch description, or empty when the names agree or the
     * account is not one we can verify. Matching is fuzzy on purpose: "J Smith"
     * and "John Smith" are the same person, and rejecting on an initial would
     * make the control unusable.
     */
    public Optional<String> confirmPayee(Iban creditorIban, String claimedName) {
        String registered = accountNames.get(creditorIban.value());
        if (registered == null) {
            // Unverifiable rather than mismatched — the beneficiary's bank does
            // not participate, or the account is unknown to us.
            return Optional.empty();
        }
        double similarity = NameMatching.similarityOf(registered, claimedName);
        if (similarity >= NAME_MATCH_THRESHOLD) {
            return Optional.empty();
        }
        return Optional.of("the account is held by a different name than '" + claimedName
                + "' (Confirmation of Payee similarity " + String.format(java.util.Locale.ROOT, "%.2f", similarity) + ")");
    }

    // --- Queries ----------------------------------------------------------

    public Optional<CreditTransfer> find(String transferId) {
        return Optional.ofNullable(transfers.get(transferId));
    }

    public CreditTransfer require(String transferId) {
        return find(transferId).orElseThrow(
                () -> new IllegalArgumentException("Unknown transfer: " + transferId));
    }

    public List<CreditTransfer> all() {
        return transfers.values().stream()
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .toList();
    }

    public List<CreditTransfer> withStatus(TransferStatus status) {
        return all().stream().filter(transfer -> transfer.status() == status).toList();
    }

    /** Transfers instructed but not yet settled — should equal the in-transit balance. */
    public List<CreditTransfer> unsettled() {
        return withStatus(TransferStatus.SETTLEMENT_IN_PROGRESS);
    }

    // --- Helpers ----------------------------------------------------------

    private PartyDetails creditorBicOrPlain(String name, Iban iban) {
        return bicDirectory.resolve(iban)
                .map(bic -> PartyDetails.of(name, iban, bic))
                .orElseGet(() -> PartyDetails.of(name, iban));
    }

    /**
     * Rejects before any ledger entry exists.
     *
     * <p>Deliberately distinct from a rejection after booking: nothing has to be
     * unwound, because nothing was recorded. The transfer is still created and
     * kept, because a payer is entitled to see why their instruction failed.
     */
    private TransferResult rejectedBeforeBooking(InitiateTransferCommand command, RejectionReason reason,
                                                 String detail, Instant now) {
        Iban debtorIban = Iban.parse(command.debtorIban())
                .orElseGet(() -> Iban.build("EE", "0000000000000000"));
        Iban creditorIban = Iban.parse(command.creditorIban())
                .orElseGet(() -> Iban.build("EE", "0000000000000001"));

        CreditTransfer transfer = new CreditTransfer(
                "ct_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                "E2E-REJECTED",
                PartyDetails.of(command.debtorName(), debtorIban),
                PartyDetails.of(command.creditorName(), creditorIban),
                command.amount(), Money.zero(command.amount().currency()),
                command.remittanceInformation(), command.chargeBearer(),
                LocalDate.now(clock), now);
        transfers.put(transfer.transferId(), transfer);
        transfer.reject(reason.code(), detail, now);

        auditTrail.record(command.customerId(), "transfer.rejected", transfer.transferId(), Map.of(
                "reasonCode", reason.code(),
                "reason", detail,
                "retryable", String.valueOf(reason.isRetryable())));
        outbox.append("transfer.rejected", transfer.transferId(), Map.of(
                "reasonCode", reason.code(),
                "reason", detail,
                "retryable", String.valueOf(reason.isRetryable())));

        return new TransferResult(transfer, false, reason.code() + " " + reason.description()
                + " — " + detail, false);
    }

    /** The zone the scheme calendar is expressed in, exposed for the console. */
    public ZoneId schemeZone() {
        return ZoneId.of("Europe/Brussels");
    }
}
