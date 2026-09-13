package bankster.client.payments.cards;

import java.time.YearMonth;

/**
 * A surrogate for a card that can be stored and passed around freely.
 *
 * <p>Everything downstream of the vault — payments, risk, settlement,
 * chargebacks — works with this rather than a {@link Pan}, which is what keeps
 * those components outside PCI scope.
 *
 * @param token      the merchant-scoped surrogate; useless if stolen from
 *                   another merchant's database
 * @param par        payment account reference, stable across merchants for the
 *                   same underlying card. It carries no PAN digits, so it is
 *                   safe to store, and it is what makes cross-merchant velocity
 *                   checks possible without a central PAN store
 * @param bin        preserved from the PAN so scheme routing and BIN-level risk
 *                   rules still work on the token alone
 */
public record CardToken(
        String token,
        String par,
        CardScheme scheme,
        String bin,
        String last4,
        String merchantId,
        YearMonth expiry) {

    public String masked() {
        return bin + "******" + last4;
    }

    public boolean isExpiredAt(YearMonth now) {
        return expiry.isBefore(now);
    }
}
