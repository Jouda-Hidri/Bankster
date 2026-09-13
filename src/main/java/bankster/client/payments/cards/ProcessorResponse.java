package bankster.client.payments.cards;

import bankster.client.payments.Money;

/**
 * A processor's answer.
 *
 * <p>{@code processorReference} is the handle every later operation needs:
 * capture, refund and void are all expressed against it, and a chargeback
 * arrives quoting it. Losing it means losing the ability to act on the payment
 * at all, which is why it is persisted before anything else.
 *
 * @param retryable whether a different processor is worth trying. True only for
 *                  infrastructure failures — an issuer decline is a decision,
 *                  and re-sending it elsewhere is both futile and, for schemes
 *                  that count re-attempts, punishable
 */
public record ProcessorResponse(
        boolean approved,
        DeclineCode responseCode,
        String processorId,
        String processorReference,
        String authorizationCode,
        Money amount,
        boolean retryable,
        String message) {

    public static ProcessorResponse approved(String processorId, String processorReference,
                                             String authorizationCode, Money amount) {
        return new ProcessorResponse(true, DeclineCode.APPROVED, processorId, processorReference,
                authorizationCode, amount, false, "approved");
    }

    public static ProcessorResponse declined(String processorId, DeclineCode code, Money amount) {
        return new ProcessorResponse(false, code, processorId, null, null, amount,
                code.isTechnicalFailure(), code.description());
    }

    /** The processor itself failed — nothing is known about the issuer's view. */
    public static ProcessorResponse unavailable(String processorId, String message, Money amount) {
        return new ProcessorResponse(false, DeclineCode.ISSUER_UNAVAILABLE, processorId, null, null,
                amount, true, message);
    }
}
