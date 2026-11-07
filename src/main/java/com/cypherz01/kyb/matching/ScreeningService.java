package com.cypherz01.kyb.matching;

/**
 * Screens one name against the active sanctions dataset. It validates and normalises the
 * request, retrieves candidates (trigram similarity unioned with phonetic-key matches, only
 * comparable schemas, capped), scores each with {@link NameMatcher}, keeps the best name per
 * entity, and returns the banded matches.
 */
public class ScreeningService {

    /**
     * @throws InvalidScreeningRequestException blank or unusable name, invalid birth date or country
     * @throws SanctionsDataUnavailableException no active dataset (nothing imported yet)
     */
    public ScreeningResult screen(ScreeningRequest request) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
