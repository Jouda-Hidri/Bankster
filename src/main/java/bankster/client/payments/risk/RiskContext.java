package bankster.client.payments.risk;

import java.time.Instant;

import bankster.client.payments.Money;

/**
 * Everything the fraud engine is allowed to see about one attempt.
 *
 * <p>Note what is absent: there is no PAN. The card is identified by its
 * payment account reference and BIN, which is enough to recognise the same card
 * across merchants and to know who issued it, while keeping the risk engine
 * outside PCI scope.
 *
 * @param cardPar          stable cross-merchant reference for the card
 * @param issuerCountry    where the card was issued, from the BIN table
 * @param ipCountry        where the shopper appears to be
 * @param billingCountry   what the shopper claims
 * @param mcc              merchant category code — some categories are far more
 *                         attractive to fraudsters than others
 * @param cardholderPresent false for e-commerce and merchant-initiated payments,
 *                          where liability and fraud rates both differ
 */
public record RiskContext(
        String paymentId,
        String merchantId,
        String cardPar,
        String bin,
        Money amount,
        String issuerCountry,
        String ipCountry,
        String billingCountry,
        String ipAddress,
        String mcc,
        boolean cardholderPresent,
        boolean recurring,
        Instant at) {
}
