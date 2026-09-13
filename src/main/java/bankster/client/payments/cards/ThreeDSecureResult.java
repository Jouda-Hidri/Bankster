package bankster.client.payments.cards;

/**
 * The outcome of a 3-D Secure authentication.
 *
 * @param eci               electronic commerce indicator, passed on to the
 *                          issuer in the authorization. It is the field that
 *                          actually carries the liability position: Visa 05
 *                          means fully authenticated, 06 attempted, 07 not
 *                          authenticated
 * @param cavv              the cryptogram proving authentication happened; an
 *                          authorization claiming ECI 05 without it is refused
 * @param dsTransactionId   directory server reference, required in
 *                          authorization for 3DS2 and quoted in any dispute
 * @param liabilityShift    whether a fraud chargeback would fall on the issuer
 *                          rather than the merchant. The commercial point of
 *                          the whole exercise
 */
public record ThreeDSecureResult(
        Outcome outcome,
        String version,
        String eci,
        String cavv,
        String dsTransactionId,
        boolean liabilityShift,
        ScaExemption exemption,
        String reason) {

    public enum Outcome {

        /** Authenticated on issuer risk data alone — no shopper interaction. */
        FRICTIONLESS,

        /** The issuer wants the shopper to do something: an app prompt, an OTP. */
        CHALLENGE_REQUIRED,

        /** The challenge was presented and passed. */
        CHALLENGE_PASSED,

        /** The challenge was presented and failed or abandoned. */
        CHALLENGE_FAILED,

        /** SCA was not required; an exemption was claimed instead. */
        EXEMPTED,

        /**
         * The issuer is not participating. Attempting is still evidence of good
         * faith and, for Visa and Mastercard, still shifts liability.
         */
        ATTEMPTED,

        /** The issuer refused outright — usually its own fraud rules. */
        REJECTED,

        /** The directory server or access control server could not be reached. */
        UNAVAILABLE
    }

    public boolean isAuthenticated() {
        return outcome == Outcome.FRICTIONLESS || outcome == Outcome.CHALLENGE_PASSED
                || outcome == Outcome.ATTEMPTED;
    }

    public boolean requiresChallenge() {
        return outcome == Outcome.CHALLENGE_REQUIRED;
    }

    /** Whether authorization may proceed at all. */
    public boolean canProceedToAuthorization() {
        return isAuthenticated() || outcome == Outcome.EXEMPTED;
    }
}
