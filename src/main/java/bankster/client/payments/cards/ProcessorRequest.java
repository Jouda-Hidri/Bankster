package bankster.client.payments.cards;

import bankster.client.payments.Money;

/**
 * An authorization request as handed to a processor.
 *
 * <p>The authentication result travels with it because the issuer's decision
 * depends on it: the same transaction that is declined for want of strong
 * customer authentication is approved when the cryptogram is attached.
 */
public record ProcessorRequest(
        String paymentId,
        CardToken card,
        Money amount,
        ThreeDSecureResult authentication,
        String merchantId,
        String mcc,
        String orderReference,
        boolean cardholderPresent,
        boolean recurring) {
}
