package bankster.client.payments.cards;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import bankster.client.payments.Money;

/**
 * The issuing side of a card transaction.
 *
 * <p>Issuing and acquiring are the two halves of the card business and are
 * frequently confused. The <b>issuer</b> is the cardholder's bank: it gives out
 * the card, carries the credit risk, decides whether to approve an
 * authorization, and is the party a cardholder complains to when they want a
 * chargeback. The <b>acquirer</b> is the merchant's bank: it signs up the
 * merchant, takes on the risk that the merchant fails to deliver, and pays the
 * merchant out. They never speak directly — the scheme sits between them,
 * routing the message and setting the rules that bind both.
 *
 * <p>The direction of money follows from that: the issuer pays the acquirer the
 * transaction amount less <em>interchange</em>, which is the fee the acquirer
 * side pays the issuer side for each transaction. Interchange is the largest
 * component of the merchant's cost and it flows towards the issuer, which is
 * why issuing is the profitable half of the industry.
 *
 * <p>This class stands in for that bank so the full lifecycle can be exercised
 * without a real scheme connection. It keeps balances, applies authorization
 * holds and releases them, and answers in ISO 8583 response codes. Certain BINs
 * are wired to fixed outcomes so that declines, retryable failures and stolen
 * cards can be demonstrated deterministically.
 */
@Component
public class IssuerSimulator {

    /** How long an unused authorization hold survives before the issuer drops it. */
    public static final Duration HOLD_LIFETIME = Duration.ofDays(7);

    private final Clock clock;
    private final Map<String, CardAccount> accounts = new ConcurrentHashMap<>();
    private final Map<String, Hold> holds = new ConcurrentHashMap<>();

    public IssuerSimulator(Clock clock) {
        this.clock = clock;
    }

    /** A card account on the issuer's books. */
    public static final class CardAccount {

        private final String par;
        private final String issuerCountry;
        private final Money creditLimit;
        private Money settledBalance;
        private Money heldAmount;
        private boolean blocked;

        public CardAccount(String par, String issuerCountry, Money creditLimit) {
            this.par = par;
            this.issuerCountry = issuerCountry;
            this.creditLimit = creditLimit;
            this.settledBalance = Money.zero(creditLimit.currency());
            this.heldAmount = Money.zero(creditLimit.currency());
        }

        /** Credit limit less what is already spent and what is currently held. */
        public Money availableToSpend() {
            return creditLimit.minus(settledBalance).minus(heldAmount);
        }

        public String par() {
            return par;
        }

        public String issuerCountry() {
            return issuerCountry;
        }

        public Money creditLimit() {
            return creditLimit;
        }

        public Money settledBalance() {
            return settledBalance;
        }

        public Money heldAmount() {
            return heldAmount;
        }

        public boolean isBlocked() {
            return blocked;
        }

        public void block() {
            blocked = true;
        }
    }

    private record Hold(String par, Money amount, Instant placedAt) {
    }

    /**
     * @param availableBalance what the issuer would tell the cardholder is left,
     *                         after holds — which is why an authorization for an
     *                         amount that "should" fit can still be declined
     */
    public record IssuerResponse(
            DeclineCode responseCode,
            String authorizationCode,
            String holdReference,
            String issuerCountry,
            Money availableBalance) {

        public boolean isApproved() {
            return responseCode.isApproval();
        }
    }

    // --- Portfolio --------------------------------------------------------

    public CardAccount openAccount(String par, String issuerCountry, Money creditLimit) {
        CardAccount account = new CardAccount(par, issuerCountry, creditLimit);
        accounts.put(par, account);
        return account;
    }

    public Optional<CardAccount> account(String par) {
        return Optional.ofNullable(accounts.get(par));
    }

    // --- Authorization ----------------------------------------------------

    /**
     * Decides on an authorization and, if approved, places a hold.
     *
     * <p>A hold is not a debit. It reduces what the cardholder can spend but
     * moves no money and creates no receivable — that only happens at capture.
     * Modelling the two as one step is the most common way a card integration
     * ends up with a ledger that cannot be reconciled.
     */
    public IssuerResponse authorize(CardToken card, Money amount, ThreeDSecureResult authentication) {
        expireStaleHolds();

        DeclineCode scripted = scriptedOutcomeFor(card.bin());
        if (scripted != null && !scripted.isApproval()) {
            return decline(scripted, card);
        }

        if (card.isExpiredAt(YearMonth.now(clock))) {
            return decline(DeclineCode.EXPIRED_CARD, card);
        }

        CardAccount account = accounts.get(card.par());
        if (account == null) {
            return decline(DeclineCode.INVALID_CARD_NUMBER, card);
        }
        if (account.isBlocked()) {
            return decline(DeclineCode.RESTRICTED_CARD, card);
        }

        // An issuer may insist on authentication for an EEA transaction that
        // arrived without it. This is the soft decline a merchant is expected to
        // answer by retrying with 3-D Secure.
        if (requiresAuthentication(amount, authentication)) {
            return decline(DeclineCode.STRONG_AUTHENTICATION_REQUIRED, card);
        }

        if (!amount.currency().equals(account.creditLimit().currency())) {
            return decline(DeclineCode.TRANSACTION_NOT_PERMITTED, card);
        }
        if (amount.isGreaterThan(account.availableToSpend())) {
            return decline(DeclineCode.INSUFFICIENT_FUNDS, card);
        }

        String holdReference = UUID.randomUUID().toString();
        synchronized (account) {
            account.heldAmount = account.heldAmount.plus(amount);
        }
        holds.put(holdReference, new Hold(card.par(), amount, clock.instant()));

        return new IssuerResponse(DeclineCode.APPROVED, authorizationCode(), holdReference,
                account.issuerCountry(), account.availableToSpend());
    }

    /**
     * Converts a hold into a debit. Capturing less than was authorized releases
     * the difference, which is what makes partial capture safe for the
     * cardholder.
     */
    public boolean capture(String holdReference, Money amount) {
        Hold hold = holds.remove(holdReference);
        if (hold == null) {
            return false;
        }
        CardAccount account = accounts.get(hold.par());
        if (account == null) {
            return false;
        }
        synchronized (account) {
            account.heldAmount = account.heldAmount.minus(hold.amount());
            account.settledBalance = account.settledBalance.plus(amount);
        }
        return true;
    }

    /** Releases a hold without debiting — a void, or an expiry. */
    public boolean releaseHold(String holdReference) {
        Hold hold = holds.remove(holdReference);
        if (hold == null) {
            return false;
        }
        CardAccount account = accounts.get(hold.par());
        if (account == null) {
            return false;
        }
        synchronized (account) {
            account.heldAmount = account.heldAmount.minus(hold.amount());
        }
        return true;
    }

    /** Credits the cardholder — a refund or a chargeback going their way. */
    public boolean credit(String par, Money amount) {
        CardAccount account = accounts.get(par);
        if (account == null) {
            return false;
        }
        synchronized (account) {
            account.settledBalance = account.settledBalance.minus(amount);
        }
        return true;
    }

    /**
     * Drops holds the issuer has been carrying too long. Real issuers do this
     * silently, which is why a merchant who waits too long to capture finds the
     * authorization gone.
     */
    public int expireStaleHolds() {
        Instant cutoff = clock.instant().minus(HOLD_LIFETIME);
        int expired = 0;
        for (Map.Entry<String, Hold> entry : Map.copyOf(holds).entrySet()) {
            if (entry.getValue().placedAt().isBefore(cutoff)) {
                releaseHold(entry.getKey());
                expired++;
            }
        }
        return expired;
    }

    public boolean holdExists(String holdReference) {
        return holds.containsKey(holdReference);
    }

    private boolean requiresAuthentication(Money amount, ThreeDSecureResult authentication) {
        if (authentication == null) {
            return true;
        }
        if (authentication.canProceedToAuthorization()) {
            // An exemption above the low-value ceiling is refused by issuers that
            // do not accept the acquirer's risk analysis.
            return authentication.exemption() == ScaExemption.TRANSACTION_RISK_ANALYSIS
                    && amount.isGreaterThan(Money.of(amount.currency(), "250.00"));
        }
        return true;
    }

    /**
     * Fixed outcomes for reserved BINs, mirroring the test card ranges every
     * processor publishes.
     */
    private DeclineCode scriptedOutcomeFor(String bin) {
        return switch (bin) {
            case "400002" -> DeclineCode.INSUFFICIENT_FUNDS;
            case "400003" -> DeclineCode.DO_NOT_HONOUR;
            case "400004" -> DeclineCode.STOLEN_CARD;
            case "400005" -> DeclineCode.ISSUER_UNAVAILABLE;
            case "400006" -> DeclineCode.STRONG_AUTHENTICATION_REQUIRED;
            default -> null;
        };
    }

    private IssuerResponse decline(DeclineCode code, CardToken card) {
        CardAccount account = accounts.get(card.par());
        return new IssuerResponse(code, null, null,
                account == null ? "??" : account.issuerCountry(),
                account == null ? Money.zero("EUR") : account.availableToSpend());
    }

    private String authorizationCode() {
        return UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }
}
