package bankster.client.psd2;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire types for the Berlin Group NextGenPSD2 Account Information Service.
 *
 * <p>Only the fields Bankster consumes are modelled; unknown properties are
 * ignored by the default Jackson configuration, so ASPSP-specific extensions
 * do not break deserialisation.
 */
public final class Psd2Dtos {

    private Psd2Dtos() {
    }

    // --- OAuth2 ---------------------------------------------------------

    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") Long expiresIn,
            String scope) {
    }

    // --- Consent --------------------------------------------------------

    /** {@code access} block of a consent request. */
    public record AccountAccess(String availableAccounts) {

        public static AccountAccess allAccounts() {
            return new AccountAccess("allAccounts");
        }
    }

    public record ConsentRequest(
            AccountAccess access,
            boolean recurringIndicator,
            LocalDate validUntil,
            int frequencyPerDay,
            boolean combinedServiceIndicator) {
    }

    public record Link(String href) {
    }

    public record ConsentResponse(
            String consentId,
            String consentStatus,
            @JsonProperty("_links") Map<String, Link> links) {

        /** URL the PSU must visit to authenticate and sign the consent (SCA redirect approach). */
        public String scaRedirectUrl() {
            Link link = links == null ? null : links.get("scaRedirect");
            return link == null ? null : link.href();
        }
    }

    public record ConsentStatusResponse(String consentStatus) {
    }

    // --- Accounts -------------------------------------------------------

    public record Account(
            String resourceId,
            String iban,
            String ownerName,
            String currency,
            String name,
            String product,
            String cashAccountType,
            String status) {

        /** Falls back to the IBAN when the ASPSP supplies no nickname. */
        public String displayName() {
            return name == null || name.isBlank() ? iban : name;
        }
    }

    public record AccountsResponse(List<Account> accounts) {
    }

    // --- Balances -------------------------------------------------------

    public record Amount(String currency, String amount) {
    }

    public record Balance(String balanceType, Amount balanceAmount) {
    }

    public record BalancesResponse(List<Balance> balances) {
    }

    // --- Transactions ---------------------------------------------------

    public record AccountReference(String iban) {
    }

    public record Transaction(
            String entryReference,
            LocalDate bookingDate,
            Amount transactionAmount,
            String creditorName,
            AccountReference creditorAccount,
            String debtorName,
            AccountReference debtorAccount,
            String remittanceInformationUnstructured,
            String remittanceInformationStructured,
            String bankTransactionCode) {

        /** The counterparty, whichever side of the transaction it sits on. */
        public String counterparty() {
            if (creditorName != null && !creditorName.isBlank()) {
                return creditorName;
            }
            if (debtorName != null && !debtorName.isBlank()) {
                return debtorName;
            }
            return "";
        }

        public String description() {
            if (remittanceInformationUnstructured != null && !remittanceInformationUnstructured.isBlank()) {
                return remittanceInformationUnstructured;
            }
            return remittanceInformationStructured == null ? "" : remittanceInformationStructured;
        }
    }

    public record PendingTransaction(
            LocalDate reservationDate,
            Amount transactionAmount,
            String remittanceInformationUnstructured) {
    }

    public record AccountTransactions(
            List<Transaction> booked,
            List<PendingTransaction> pending) {
    }

    public record TransactionsResponse(
            AccountReference account,
            AccountTransactions transactions) {

        public List<Transaction> booked() {
            return transactions == null || transactions.booked() == null
                    ? List.of()
                    : transactions.booked();
        }

        public List<PendingTransaction> pending() {
            return transactions == null || transactions.pending() == null
                    ? List.of()
                    : transactions.pending();
        }
    }

    // --- Errors ---------------------------------------------------------

    public record TppMessage(String category, String code, String text) {
    }

    public record ErrorResponse(List<TppMessage> tppMessages) {
    }
}
