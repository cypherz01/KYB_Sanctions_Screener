package com.cypherz01.kyb.matching;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Name matching settings (spec §8). Every list, weight and threshold lives in application.yml, so
 * tuning is a config change that the accuracy report (README) can be regenerated against.
 *
 * @param honorifics titles removed from person names ({@code mr}, {@code dr}, {@code sheikh}, ...)
 * @param legalForms legal-form words/phrases removed from the end of organisation names
 * @param leadingLegalForms legal forms written before the name ({@code OOO}, {@code The}), removed
 *     from the start
 * @param weights how the four name-similarity components are combined (they should sum to 1)
 * @param tokens token-level scoring constants
 * @param bands similarity thresholds: at or above {@code strong} is STRONG, at or above {@code
 *     possible} is POSSIBLE, below that the candidate is discarded
 * @param birthDate adjustment when both sides have a birth date
 * @param country adjustment when both sides have a country/nationality
 * @param retrieval stage 1 (candidate retrieval in Postgres)
 * @param maxMatches matches returned per screened name (best first)
 * @param sourceListLabels display labels for OpenSanctions source dataset ids
 */
@ConfigurationProperties("kyb.matching")
public record MatchingProperties(
        List<String> honorifics,
        List<String> legalForms,
        List<String> leadingLegalForms,
        Weights weights,
        Tokens tokens,
        Bands bands,
        BirthDate birthDate,
        Country country,
        Retrieval retrieval,
        int maxMatches,
        Map<String, String> sourceListLabels) {

    public record Weights(double jaroWinkler, double tokenOverlap, double phonetic, double alignment) {}

    /**
     * @param initialSimilarity a single-letter token against a token starting with that letter
     * @param phoneticSimilarity floor for two tokens with the same phonetic key
     * @param extraTokenPenalty maximum reduction when the longer name has unmatched tokens
     */
    public record Tokens(double initialSimilarity, double phoneticSimilarity, double extraTokenPenalty) {}

    public record Bands(double strong, double possible) {}

    /**
     * @param exactBoost added when the full dates agree
     * @param partialBoost added when only year (or year and month) agree
     * @param yearTolerance years apart still treated as neutral (data-entry slack)
     * @param mismatchPenalty subtracted when the years differ by more than the tolerance
     */
    public record BirthDate(double exactBoost, double partialBoost, int yearTolerance, double mismatchPenalty) {}

    public record Country(double matchBoost, double mismatchPenalty) {}

    /**
     * @param trigramThreshold pg_trgm similarity threshold for the {@code %} operator
     * @param maxCandidates candidates scored per subject (spec §8.2: about 50)
     */
    public record Retrieval(double trigramThreshold, int maxCandidates) {}
}
