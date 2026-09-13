package bankster.client.payments.risk;

import java.util.List;

/**
 * The fraud engine's verdict on one attempt.
 *
 * <p>{@code reasons} is not decoration. A score with no explanation cannot be
 * argued with by an analyst reviewing a queue, cannot be defended to a
 * regulator asking why a customer was refused, and cannot be debugged when the
 * false-positive rate moves. Every rule that fired records itself.
 */
public record RiskAssessment(int score, RiskBand band, RiskDecision decision, List<RiskReason> reasons) {

    public RiskAssessment {
        reasons = List.copyOf(reasons);
    }

    /** One rule that contributed to the score. */
    public record RiskReason(String rule, int points, String detail) {
    }

    public boolean isDeclined() {
        return decision == RiskDecision.DECLINE;
    }

    public boolean requiresChallenge() {
        return decision == RiskDecision.CHALLENGE;
    }

    /** Flat summary for logs, the console and the audit trail. */
    public String explain() {
        if (reasons.isEmpty()) {
            return "no rules fired";
        }
        return String.join(", ", reasons.stream()
                .map(reason -> reason.rule() + " (+" + reason.points() + "): " + reason.detail())
                .toList());
    }
}
