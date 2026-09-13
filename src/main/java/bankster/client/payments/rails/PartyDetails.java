package bankster.client.payments.rails;

import java.util.Optional;

/**
 * A party to a transfer — debtor or creditor.
 *
 * <p>The name is carried as well as the account because the two are checked
 * against each other. Confirmation of Payee, mandatory for euro transfers under
 * the Instant Payments Regulation, has the sending bank verify that the name the
 * payer typed matches the account they typed before the payment goes out. It
 * exists because the single most effective push-payment fraud is persuading
 * someone to send money to the right-looking name at the wrong account, and
 * nothing in the IBAN itself catches that.
 */
public record PartyDetails(String name, Iban iban, Optional<Bic> bic, String country, String addressLine) {

    public static PartyDetails of(String name, Iban iban) {
        return new PartyDetails(name, iban, Optional.empty(), iban.countryCode(), null);
    }

    public static PartyDetails of(String name, Iban iban, Bic bic) {
        return new PartyDetails(name, iban, Optional.of(bic), iban.countryCode(), null);
    }

    public PartyDetails withBic(Bic resolved) {
        return new PartyDetails(name, iban, Optional.of(resolved), country, addressLine);
    }

    /** What is safe to show in a list or a log. */
    public String display() {
        return name + " (" + iban.masked() + ")";
    }
}
