package bankster.client.payments.settlement;

import java.time.LocalDate;
import java.util.List;

import bankster.client.payments.Money;

/**
 * The settlement file a processor sends, line by line.
 *
 * <p>This is the external record of what the processor believes it settled, and it
 * is the middle leg of a three-way reconciliation: internal ledger against
 * processor report against bank statement. It is a distinct source from the bank
 * statement and both are needed, because the two failure modes are different. If
 * the processor's report disagrees with our ledger, one of us has mis-recorded a
 * transaction. If the report agrees with our ledger but the bank statement shows a
 * different amount arriving, the money did not move as reported — a different
 * problem with a different remedy.
 *
 * <p>Processors publish these as CSV or a proprietary format; the shape is
 * consistently a header with totals and a line per transaction, which is what is
 * modelled here.
 */
public record ProcessorSettlementReport(
        String reportId,
        String processorId,
        String merchantId,
        LocalDate settlementDate,
        String currency,
        Money grossAmount,
        Money feeAmount,
        Money netAmount,
        List<Line> lines) {

    public ProcessorSettlementReport {
        lines = List.copyOf(lines);
    }

    public enum LineType {
        SALE,
        REFUND,
        CHARGEBACK,
        CHARGEBACK_REVERSAL,
        FEE
    }

    /**
     * @param itemId             the individual capture, refund or dispute this line
     *                           represents. It is the matching key, and it has to be
     *                           at this granularity: a payment captured in two
     *                           shipments legitimately produces two sale lines, and
     *                           keying on the payment instead would make the second
     *                           look like a duplicate of the first
     * @param processorReference the handle the processor knows the transaction by
     * @param paymentId          our own id, echoed back when the processor supports
     *                           it. Where it is absent, matching has to fall back
     *                           on the processor reference
     */
    public record Line(
            String itemId,
            String processorReference,
            String paymentId,
            LineType type,
            Money gross,
            Money fee,
            LocalDate transactionDate) {

        /** Signed contribution of this line to the net settlement. */
        public Money signedNet() {
            Money net = gross.minus(fee);
            return switch (type) {
                case SALE, CHARGEBACK_REVERSAL -> net;
                case REFUND, CHARGEBACK, FEE -> net.negate();
            };
        }
    }

    /** Sum of the lines — should equal the header's net, and is checked. */
    public Money computedNet() {
        Money total = Money.zero(currency);
        for (Line line : lines) {
            total = total.plus(line.signedNet());
        }
        return total;
    }

    /**
     * Whether the file's own header and detail agree.
     *
     * <p>Checked before reconciling anything against it: a report whose header does
     * not match its lines has been truncated in transit, and matching against a
     * partial file manufactures breaks that do not exist.
     */
    public boolean isSelfConsistent() {
        return computedNet().equals(netAmount);
    }

    public List<Line> linesOfType(LineType type) {
        return lines.stream().filter(line -> line.type() == type).toList();
    }
}
