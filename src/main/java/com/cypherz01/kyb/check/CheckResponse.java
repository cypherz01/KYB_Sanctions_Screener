package com.cypherz01.kyb.check;

import com.cypherz01.kyb.fca.model.FcaAvailability;
import com.cypherz01.kyb.fca.model.FcaFirmMatch;
import com.cypherz01.kyb.fca.model.FcaWarningMatch;
import com.cypherz01.kyb.matching.ScreeningMatch;
import com.cypherz01.kyb.scoring.Decision;
import com.cypherz01.kyb.scoring.Factor;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The result of a KYB check (spec §7), stored verbatim and served unchanged by GET.
 *
 * @param factors contributing factors with their points and evidence
 * @param notes things considered but deliberately not scored, and parties not screenable
 * @param subjects every party screened, with any retained sanctions matches
 * @param sources provenance of each source used (fetch time, cache, staleness, dataset version)
 */
public record CheckResponse(
        UUID checkId,
        String companyNumber,
        String companyName,
        Decision decision,
        int score,
        Thresholds thresholds,
        List<Factor> factors,
        List<String> notes,
        List<ScreenedSubject> subjects,
        Regulatory regulatory,
        List<SourceRef> sources,
        String engineVersion,
        String scoringConfigVersion,
        Instant requestedAt,
        Instant completedAt) {

    public record Thresholds(int review, int fail) {}

    /**
     * @param birthMonth {@code yyyy-MM} or null
     * @param matches retained sanctions matches, best first
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScreenedSubject(
            List<String> roles, String name, String type, String birthMonth, List<ScreeningMatch> matches) {}

    /** The FCA Register picture (M4), or availability UNAVAILABLE with the reason in evidence. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Regulatory(
            FcaAvailability availability,
            FcaFirmMatch authorisedFirm,
            List<FcaWarningMatch> warnings,
            List<String> evidence) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SourceRef(
            String name, Instant fetchedAt, Boolean fromCache, Boolean stale, String datasetVersion, String licence) {}
}
