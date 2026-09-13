package bankster.client.payments.rails;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import bankster.client.payments.Money;
import bankster.client.payments.core.AuditTrail;
import bankster.client.payments.core.Outbox;
import bankster.client.payments.rails.CorrespondentNetwork.Correspondent;
import bankster.client.payments.rails.FxRates.FxQuote;

/**
 * Cross-border payments over SWIFT.
 *
 * <p>The point of keeping this separate from {@link SepaPaymentService} is that
 * the guarantees are different in kind, not just in degree. A SEPA transfer has a
 * known arrival time, a fixed fee, a standard rejection code, and the beneficiary
 * receives exactly the amount sent. A SWIFT payment has an estimated arrival time
 * that depends on how many correspondents are in the chain and what their cut-offs
 * are, charges that may be deducted in transit by institutions the sender never
 * chose, an FX spread that is usually larger than all the explicit fees combined,
 * and no reliable negative confirmation — a payment that has not arrived may still
 * be moving.
 *
 * <p>The single most useful thing this service does is tell the sender, before
 * they commit, what the beneficiary will actually receive: amount converted at the
 * client rate, less the charges each hop in the chain will take.
 *
 * <p>The MT103 generated here is the legacy format. The ISO 20022 equivalent,
 * {@code pacs.008}, is produced by {@link Iso20022} — the cross-border migration
 * to it under CBPR+ is what makes the richer structured data in that message
 * available internationally.
 */
@Service
public class SwiftService {

    private static final DateTimeFormatter VALUE_DATE = DateTimeFormatter.ofPattern("yyMMdd");

    /** Our own BIC as the sending institution. */
    public static final Bic SENDER_BIC = new Bic("BNKSEE2A");

    private final Clock clock;
    private final CorrespondentNetwork network;
    private final FxRates fxRates;
    private final TransferBookkeeper bookkeeper;
    private final AuditTrail auditTrail;
    private final Outbox outbox;

    public SwiftService(Clock clock, CorrespondentNetwork network, FxRates fxRates,
                        TransferBookkeeper bookkeeper, AuditTrail auditTrail, Outbox outbox) {
        this.clock = clock;
        this.network = network;
        this.fxRates = fxRates;
        this.bookkeeper = bookkeeper;
        this.auditTrail = auditTrail;
        this.outbox = outbox;
    }

    public record CrossBorderInstruction(
            String customerId,
            PartyDetails debtor,
            PartyDetails creditor,
            String creditorAccount,
            String creditorCountry,
            Money amount,
            String targetCurrency,
            String remittanceInformation,
            CreditTransfer.ChargeBearer chargeBearer) {
    }

    /**
     * @param amountCredited  what the beneficiary actually receives
     * @param deductedCharges what the chain takes out of the principal in transit
     */
    public record CrossBorderQuote(
            Money amountDebited,
            Money amountCredited,
            FxQuote fx,
            Money senderFee,
            Money deductedCharges,
            Duration expectedTransit,
            List<String> correspondentChain,
            boolean routable,
            String explanation) {

        /** Everything the payment costs, including the spread customers do not see. */
        public Money totalCostInSourceCurrency() {
            return senderFee.plus(fx.spreadCost());
        }
    }

    public record CrossBorderResult(
            String reference,
            boolean accepted,
            String message,
            CrossBorderQuote quote,
            Instant expectedArrival,
            String mt103) {
    }

    /**
     * Prices a payment without sending it.
     *
     * <p>Quoting separately matters: under the EU's cross-border payments rules a
     * payer has to be told the charges and the FX markup before they commit, and
     * the number they need is the one the beneficiary will see.
     */
    public CrossBorderQuote quote(CrossBorderInstruction instruction) {
        String targetCurrency = instruction.targetCurrency() == null
                ? instruction.amount().currency()
                : instruction.targetCurrency().toUpperCase();

        if (!fxRates.supports(targetCurrency)) {
            return unroutable(instruction, "no FX rate is available for " + targetCurrency);
        }

        List<Correspondent> chain = network.chainTo(instruction.creditorCountry(), targetCurrency);
        if (chain.isEmpty()) {
            return unroutable(instruction,
                    "no correspondent route reaches " + instruction.creditorCountry()
                            + " in " + targetCurrency);
        }

        FxQuote fx = fxRates.quote(instruction.amount(), targetCurrency);
        Money senderFee = senderFeeFor(instruction.amount());
        Money deducted = instruction.chargeBearer() == CreditTransfer.ChargeBearer.DEBT
                // Sender pays everything, so nothing is taken out of the principal.
                ? Money.zero(targetCurrency)
                : network.deductedFeesFor(chain, targetCurrency);

        Money credited = fx.converted().minus(deducted);
        Money debited = instruction.chargeBearer() == CreditTransfer.ChargeBearer.CRED
                ? instruction.amount()
                : instruction.amount().plus(senderFee);

        String explanation = "routed via "
                + String.join(" → ", chain.stream().map(Correspondent::name).toList())
                + "; " + (fx.isConversion()
                ? "converted at " + fx.clientRate().stripTrailingZeros().toPlainString()
                + " (mid " + fx.midRate().stripTrailingZeros().toPlainString()
                + ", spread " + fx.spreadBasisPoints() + " bps)"
                : "no conversion")
                + (deducted.isPositive() ? "; " + deducted + " deducted in transit" : "");

        return new CrossBorderQuote(debited, credited, fx, senderFee, deducted,
                network.transitTimeFor(chain),
                chain.stream().map(correspondent -> correspondent.bic().value()).toList(),
                true, explanation);
    }

    /**
     * Sends the payment: debits the nostro at the first correspondent and emits
     * the MT103.
     *
     * <p>The nostro debit is the step that can fail for a reason that has nothing
     * to do with the customer — if the account is not pre-funded, the payment
     * cannot go however good the instruction is. Liquidity management is a real
     * operational constraint in correspondent banking, not an accounting detail.
     */
    public CrossBorderResult send(CrossBorderInstruction instruction) {
        CrossBorderQuote quote = quote(instruction);
        String reference = "SWF" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 13).toUpperCase();

        if (!quote.routable()) {
            auditTrail.record(instruction.customerId(), "swift.rejected", reference,
                    Map.of("reason", quote.explanation()));
            return new CrossBorderResult(reference, false, quote.explanation(), quote, null, null);
        }

        // The payer's own balance, checked before anything is booked, so the ledger
        // never shows a customer balance that went negative.
        Money available = bookkeeper.customerFunds(instruction.customerId(), quote.amountDebited().currency());
        if (available.isLessThan(quote.amountDebited())) {
            String message = "balance " + available + " is short of the "
                    + quote.amountDebited() + " required";
            auditTrail.record(instruction.customerId(), "swift.insufficient_funds", reference,
                    Map.of("available", available.toString(),
                            "required", quote.amountDebited().toString()));
            return new CrossBorderResult(reference, false, message, quote, null, null);
        }

        Correspondent first = network.find(quote.correspondentChain().get(0).substring(0, 8))
                .orElseThrow(() -> new IllegalStateException("Correspondent vanished from the network"));

        Money nostroDebit = fxRates.quote(instruction.amount(), first.nostroBalance().currency()).converted();
        if (!first.debitNostro(nostroDebit)) {
            String message = "the nostro account at " + first.name() + " holds "
                    + first.nostroBalance() + ", which cannot cover " + nostroDebit;
            auditTrail.record("treasury", "swift.nostro_insufficient", reference,
                    Map.of("correspondent", first.name(), "required", nostroDebit.toString()));
            return new CrossBorderResult(reference, false, message, quote, null, null);
        }

        // Book it like any other outbound payment: the payer's balance is debited
        // now, and the money sits as an in-transit liability until the far end
        // confirms. A cross-border payment is in transit for days, so booking it
        // straight to the clearing balance would misstate both for most of its life.
        bookkeeper.recordInstruction(reference, instruction.customerId(),
                instruction.amount(), quote.senderFee());

        Instant expectedArrival = clock.instant().plus(quote.expectedTransit());
        String mt103 = mt103(reference, instruction, quote);

        auditTrail.record(instruction.customerId(), "swift.sent", reference, Map.of(
                "creditor", instruction.creditor().name(),
                "debited", quote.amountDebited().toString(),
                "credited", quote.amountCredited().toString(),
                "chain", String.join(" → ", quote.correspondentChain()),
                "fxClientRate", quote.fx().clientRate().toPlainString(),
                "spreadCost", quote.fx().spreadCost().toString(),
                "deductedInTransit", quote.deductedCharges().toString(),
                "expectedArrival", expectedArrival.toString()));
        outbox.append("swift.sent", reference, Map.of(
                "customerId", instruction.customerId(),
                "debited", quote.amountDebited().toString(),
                "credited", quote.amountCredited().toString(),
                "expectedArrival", expectedArrival.toString()));

        return new CrossBorderResult(reference, true,
                "sent; the beneficiary should receive " + quote.amountCredited(),
                quote, expectedArrival, mt103);
    }

    /**
     * Builds an MT103 single customer credit transfer.
     *
     * <p>The field numbers are not decoration — an MT103 is positional and
     * tag-driven, and the tags mean specific things. {@code :32A:} carries value
     * date, currency and the settled amount; {@code :33B:} the original
     * instructed amount before conversion; {@code :36:} the exchange rate applied;
     * {@code :71A:} who bears the charges. Amounts use a comma as the decimal
     * separator and no thousands separator, which is a frequent source of
     * integration bugs.
     */
    public String mt103(String reference, CrossBorderInstruction instruction, CrossBorderQuote quote) {
        Correspondent last = network.find(
                        quote.correspondentChain().get(quote.correspondentChain().size() - 1).substring(0, 8))
                .orElseThrow();
        LocalDate valueDate = LocalDate.now(clock).plusDays(quote.expectedTransit().toDays());

        StringBuilder message = new StringBuilder();
        message.append("{1:F01").append(pad(SENDER_BIC.value(), 12)).append("0000000000}\n");
        message.append("{2:I103").append(pad(last.bic().value(), 12)).append("N}\n");
        message.append("{4:\n");
        message.append(":20:").append(reference).append('\n');
        message.append(":23B:CRED\n");
        message.append(":32A:").append(VALUE_DATE.format(valueDate))
                .append(quote.amountCredited().currency())
                .append(swiftAmount(quote.amountCredited())).append('\n');

        if (quote.fx().isConversion()) {
            message.append(":33B:").append(instruction.amount().currency())
                    .append(swiftAmount(instruction.amount())).append('\n');
            message.append(":36:").append(quote.fx().clientRate()
                    .stripTrailingZeros().toPlainString().replace('.', ',')).append('\n');
        }

        message.append(":50K:/").append(instruction.debtor().iban().value()).append('\n');
        message.append(instruction.debtor().name().toUpperCase()).append('\n');
        if (instruction.debtor().addressLine() != null) {
            message.append(instruction.debtor().addressLine().toUpperCase()).append('\n');
        }

        // Intermediary, when the chain has more than one hop.
        if (quote.correspondentChain().size() > 1) {
            message.append(":56A:").append(quote.correspondentChain().get(0)).append('\n');
        }
        message.append(":57A:").append(last.bic().value()).append('\n');

        message.append(":59:/").append(instruction.creditorAccount()).append('\n');
        message.append(instruction.creditor().name().toUpperCase()).append('\n');
        message.append(instruction.creditorCountry().toUpperCase()).append('\n');

        if (instruction.remittanceInformation() != null && !instruction.remittanceInformation().isBlank()) {
            message.append(":70:").append(truncate(instruction.remittanceInformation(), 140)).append('\n');
        }
        message.append(":71A:").append(chargeCode(instruction.chargeBearer())).append('\n');
        message.append("-}");
        return message.toString();
    }

    // --- Helpers ----------------------------------------------------------

    /** SWIFT amounts use a comma decimal separator and always show it. */
    static String swiftAmount(Money amount) {
        return amount.toDecimal().toPlainString().replace('.', ',');
    }

    private static String chargeCode(CreditTransfer.ChargeBearer bearer) {
        return switch (bearer) {
            case DEBT -> "OUR";
            case CRED -> "BEN";
            case SHAR, SLEV -> "SHA";
        };
    }

    private Money senderFeeFor(Money amount) {
        return Money.of(amount.currency(), "25.00").plus(amount.percentageBasisPoints(10));
    }

    private CrossBorderQuote unroutable(CrossBorderInstruction instruction, String reason) {
        Money zero = Money.zero(instruction.amount().currency());
        return new CrossBorderQuote(zero, zero,
                new FxQuote(instruction.amount().currency(), instruction.amount().currency(),
                        java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, 0,
                        instruction.amount(), instruction.amount(), zero),
                zero, zero, Duration.ZERO, List.of(), false, reason);
    }

    private static String pad(String value, int length) {
        return value.length() >= length ? value.substring(0, length)
                : value + "X".repeat(length - value.length());
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    public Optional<Correspondent> correspondentFor(String country, String currency) {
        List<Correspondent> chain = network.chainTo(country, currency);
        return chain.isEmpty() ? Optional.empty() : Optional.of(chain.get(chain.size() - 1));
    }

    /**
     * Confirms the far end has credited the beneficiary, clearing the in-transit
     * liability.
     *
     * <p>Driven by an incoming confirmation in production. Worth noting that SWIFT
     * gives no reliable negative confirmation: a payment that has not been confirmed
     * may still be moving, which is why the in-transit balance has to be aged rather
     * than assumed settled after a fixed interval.
     */
    public void confirmArrival(String reference, Money amount) {
        bookkeeper.recordSettlement(reference, amount);
        auditTrail.record("correspondent", "swift.confirmed", reference,
                java.util.Map.of("amount", amount.toString()));
        outbox.append("swift.confirmed", reference, java.util.Map.of("amount", amount.toString()));
    }
}
