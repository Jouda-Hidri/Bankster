package bankster.client.payments.cards;

import bankster.client.payments.Money;

/**
 * The pricing attached to card volume.
 *
 * <p>Three distinct fees are deliberately modelled separately, because they
 * flow to different parties and collapsing them hides the actual economics:
 *
 * <ul>
 *   <li><b>Merchant discount</b> is what the merchant is charged. It is our
 *       revenue, and it is the only one of the three the merchant sees.</li>
 *   <li><b>Interchange</b> is paid from the acquiring side to the issuer on
 *       every transaction. It is our cost, it is the largest component of the
 *       merchant discount, and in the EEA it is capped by regulation at 0.2% for
 *       consumer debit and 0.3% for consumer credit — a cap that does not apply
 *       to commercial cards, which is why they are priced differently.</li>
 *   <li><b>Scheme fees</b> are assessments paid to Visa or Mastercard for
 *       running the network. Also our cost.</li>
 * </ul>
 *
 * <p>What is left after the two costs is the margin. A processor quoting a
 * merchant discount below its own interchange is losing money per transaction,
 * which is visible only if the fees are tracked apart.
 */
public record FeeSchedule(
        int merchantDiscountBasisPoints,
        Money fixedFeePerTransaction,
        int interchangeBasisPoints,
        Money schemeFeePerTransaction) {

    /** Typical EEA consumer-debit pricing: 0.9% + €0.05, over 0.2% capped interchange. */
    public static FeeSchedule standardEeaDebit(String currency) {
        return new FeeSchedule(90, Money.of(currency, "0.05"), 20, Money.of(currency, "0.02"));
    }

    /** Consumer credit: higher interchange cap, so a higher merchant discount. */
    public static FeeSchedule standardEeaCredit(String currency) {
        return new FeeSchedule(140, Money.of(currency, "0.05"), 30, Money.of(currency, "0.02"));
    }

    /** Commercial and non-EEA cards fall outside the caps and cost materially more. */
    public static FeeSchedule uncappedCommercial(String currency) {
        return new FeeSchedule(240, Money.of(currency, "0.10"), 150, Money.of(currency, "0.03"));
    }

    /** What we charge the merchant on this amount. */
    public Money merchantDiscount(Money amount) {
        return amount.percentageBasisPoints(merchantDiscountBasisPoints).plus(fixedFeePerTransaction);
    }

    /** What we pay the issuer. */
    public Money interchange(Money amount) {
        return amount.percentageBasisPoints(interchangeBasisPoints);
    }

    /** What we pay the scheme. */
    public Money schemeFee() {
        return schemeFeePerTransaction;
    }

    /** Merchant discount less interchange and scheme fees. */
    public Money margin(Money amount) {
        return merchantDiscount(amount).minus(interchange(amount)).minus(schemeFee());
    }

    /** What the merchant actually receives. */
    public Money netToMerchant(Money amount) {
        return amount.minus(merchantDiscount(amount));
    }
}
