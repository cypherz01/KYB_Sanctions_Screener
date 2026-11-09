package com.cypherz01.kyb.scoring;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The versioned rule configuration in {@code scoring-rules.yml} (spec §9).
 *
 * @param version recorded on every check as {@code scoringConfigVersion}
 * @param points points per factor code (every code must be present)
 * @param sanctionsPossibleScale extra points for a POSSIBLE match, scaled by similarity between the
 *     POSSIBLE and STRONG thresholds
 * @param regulatedSicCodes SIC code to description, for FCA_NOT_AUTHORISED_FOR_ACTIVITY
 */
@ConfigurationProperties("kyb.scoring")
public record ScoringProperties(
        String version,
        Thresholds thresholds,
        Map<FactorCode, Integer> points,
        int sanctionsPossibleScale,
        int recentlyIncorporatedDays,
        OfficerChurn officerChurn,
        List<String> inactiveStatuses,
        List<String> ukCountries,
        Map<String, String> regulatedSicCodes,
        Ownership ownership) {

    public record Thresholds(int review, int fail) {}

    public record OfficerChurn(int windowDays, int minChanges) {}

    /**
     * @param failAtPercent the lower bound at which a strong upstream match fails automatically
     * @param maxDepth how many levels above the company to fetch; 1 means only its direct parents
     * @param maxCompanies how many parent companies one check may fetch
     */
    public record Ownership(double failAtPercent, int maxDepth, int maxCompanies) {}
}
