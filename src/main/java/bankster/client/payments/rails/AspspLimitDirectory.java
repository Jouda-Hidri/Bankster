package bankster.client.payments.rails;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import bankster.client.payments.Money;

/**
 * Per-institution SEPA Instant receiving limits, for Berlin Group NextGenPSD2 ASPSPs.
 *
 * <p>The scheme-level ceiling is gone, so the maximum on an instant transfer is now data
 * rather than a rule: each institution sets its own, for the liquidity and fraud reasons
 * that made it a rule in the first place. This is where that data lives.
 *
 * <p>Read once from {@code berlin-group-aspsp-limits.json} at start-up and immutable
 * thereafter. Changing a limit means editing the file and restarting. There is no update
 * API, so routing behaviour cannot move without a commit to review.
 */
@Component
public class AspspLimitDirectory {

    private static final Logger log = LoggerFactory.getLogger(AspspLimitDirectory.class);

    static final String REFERENCE_FILE = "berlin-group-aspsp-limits.json";

    /** SEPA Instant carries euro only, so a declared limit needs no currency of its own. */
    private static final String SCHEME_CURRENCY = "EUR";

    private final Map<String, AspspInstantLimit> limits;

    // Annotated because the package-private constructor below makes the choice ambiguous
    // otherwise, and Spring falls back to looking for a no-arg one.
    @Autowired
    public AspspLimitDirectory() {
        this(REFERENCE_FILE);
    }

    /** Loads from a named classpath resource, so tests can supply their own. */
    AspspLimitDirectory(String resourceName) {
        this.limits = Map.copyOf(load(resourceName));
    }

    /** What this institution accepts, if the file says anything about it. */
    public Optional<AspspInstantLimit> forBic(Bic bic) {
        return Optional.ofNullable(limits.get(bic.institutionBic()));
    }

    /**
     * The ceiling to apply to a transfer to this institution, given our own.
     *
     * <p>The lower of the two binds. An unknown institution, or one that has declared no
     * ceiling, contributes no constraint — our own limit still applies, and reachability
     * has already decided separately whether the bank is on the scheme at all.
     */
    public EffectiveLimit effectiveLimit(Optional<Bic> beneficiary, Money ourLimit) {
        Optional<AspspInstantLimit> entry = beneficiary.flatMap(this::forBic);
        Optional<Money> theirs = entry.flatMap(AspspInstantLimit::maximum);

        if (theirs.isPresent() && theirs.get().isLessThan(ourLimit)) {
            return new EffectiveLimit(theirs.get(), Bound.BENEFICIARY_INSTITUTION, entry);
        }
        return new EffectiveLimit(ourLimit, Bound.SENDING_INSTITUTION, entry);
    }

    /** Which side's policy is actually constraining the transfer. */
    public enum Bound {
        SENDING_INSTITUTION,
        BENEFICIARY_INSTITUTION
    }

    /**
     * @param entry the beneficiary's record, when the file holds one — carried so the
     *              caller can name the institution in the reason it gives the payer
     */
    public record EffectiveLimit(Money amount, Bound bound, Optional<AspspInstantLimit> entry) {

        /** Names the side whose policy bit, for the explanation shown to the payer. */
        public String describeBound() {
            if (bound == Bound.SENDING_INSTITUTION) {
                return "this institution's instant transfer limit";
            }
            return entry.map(limit -> limit.institutionName() + "'s instant receiving limit")
                    .orElse("the beneficiary institution's instant receiving limit");
        }
    }

    public List<AspspInstantLimit> all() {
        return limits.values().stream()
                .sorted((a, b) -> a.bic().value().compareTo(b.bic().value()))
                .toList();
    }

    public int size() {
        return limits.size();
    }

    /**
     * Reads the reference file.
     *
     * <p>A malformed or missing file is logged and tolerated rather than fatal: without it
     * every transfer falls back to our own limit, which is conservative and still correct,
     * whereas refusing to start would take payments down over a data file. A malformed
     * <em>entry</em> is skipped individually for the same reason — one bad line should not
     * discard the other institutions.
     */
    private Map<String, AspspInstantLimit> load(String resourceName) {
        Map<String, AspspInstantLimit> loaded = new LinkedHashMap<>();
        try (InputStream stream = new ClassPathResource(resourceName).getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(stream);
            List<String> names = new ArrayList<>();

            for (JsonNode entry : root.path("aspsps")) {
                Optional<Bic> bic = Bic.parse(entry.path("bic").asText());
                if (bic.isEmpty()) {
                    log.warn("Skipping ASPSP entry with an unusable BIC: {}",
                            entry.path("bic").asText());
                    continue;
                }
                try {
                    Optional<Money> maximum = entry.hasNonNull("maximum")
                            ? Optional.of(Money.of(SCHEME_CURRENCY, entry.get("maximum").asText()))
                            : Optional.empty();

                    loaded.put(bic.get().institutionBic(), new AspspInstantLimit(
                            bic.get(),
                            entry.path("name").asText(bic.get().value()),
                            entry.path("country").asText(bic.get().countryCode()),
                            maximum));
                    names.add(bic.get().institutionBic());
                } catch (RuntimeException e) {
                    log.warn("Skipping unusable ASPSP entry for {}: {}",
                            bic.get().institutionBic(), e.getMessage());
                }
            }
            log.info("Loaded SEPA Instant limits for {} Berlin Group ASPSPs from {}: {}",
                    names.size(), resourceName, String.join(", ", names));
        } catch (Exception e) {
            log.warn("Could not read {} ({}); transfers will be bounded by this institution's "
                    + "own limit alone", resourceName, e.getMessage());
        }
        return loaded;
    }
}
