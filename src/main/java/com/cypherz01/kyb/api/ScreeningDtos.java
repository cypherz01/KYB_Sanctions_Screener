package com.cypherz01.kyb.api;

import com.cypherz01.kyb.matching.ScreeningMatch;
import com.cypherz01.kyb.sanctions.SanctionsAttribution;
import java.util.List;

/** Bodies for {@code POST /api/v1/screen/name} (spec §7). */
public final class ScreeningDtos {

    private ScreeningDtos() {}

    /**
     * @param type {@code PERSON}, {@code ORGANISATION} or {@code ANY} (default), case-insensitive.
     *     A string, not the enum, so a bad value gets our 400 message, not a Jackson error.
     * @param birthDate optional {@code yyyy}, {@code yyyy-MM} or {@code yyyy-MM-dd}
     * @param country optional ISO 3166-1 alpha-2 code
     */
    public record ScreenNameRequest(String name, String type, String birthDate, String country) {}

    public record DatasetRef(long id, String version) {}

    public record ScreenNameResponse(
            String query,
            String subjectType,
            String normalisedQuery,
            DatasetRef datasetVersion,
            int candidatesConsidered,
            List<ScreeningMatch> matches,
            SanctionsAttribution source) {}
}
