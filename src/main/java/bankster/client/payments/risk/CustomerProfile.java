package bankster.client.payments.risk;

import java.time.Instant;

import bankster.client.payments.Money;

/**
 * The customer record AML decisions are made against.
 *
 * <p>{@code politicallyExposed} is tracked separately from the risk rating
 * because a PEP is not presumed to be a criminal — the obligation is enhanced
 * due diligence and senior sign-off, not refusal.
 *
 * @param expectedMonthlyVolume what the customer said they would move at
 *                              onboarding. Monitoring is largely the business
 *                              of noticing when behaviour departs from this
 */
public record CustomerProfile(
        String customerId,
        String fullName,
        String countryOfResidence,
        String nationality,
        KycStatus kycStatus,
        boolean politicallyExposed,
        Money expectedMonthlyVolume,
        Instant verifiedAt) {

    public CustomerProfile withKycStatus(KycStatus status, Instant at) {
        return new CustomerProfile(customerId, fullName, countryOfResidence, nationality,
                status, politicallyExposed, expectedMonthlyVolume, at);
    }
}
