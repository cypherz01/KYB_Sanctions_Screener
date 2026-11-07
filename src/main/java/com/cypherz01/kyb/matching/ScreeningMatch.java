package com.cypherz01.kyb.matching;

import com.cypherz01.kyb.sanctions.model.NameKind;
import java.util.List;

/**
 * One sanctioned entity that matched, scored on its best-matching name.
 *
 * @param sanctionsEntityId the OpenSanctions entity id
 * @param lists display labels of the source lists (configured), raw dataset id when unlabelled
 * @param similarity rounded to 3 decimal places
 */
public record ScreeningMatch(
        String sanctionsEntityId,
        String schema,
        String caption,
        String matchedName,
        NameKind nameKind,
        double similarity,
        MatchBand band,
        List<String> lists,
        List<String> programIds,
        List<String> birthDates,
        List<String> countries,
        List<String> evidence) {}
