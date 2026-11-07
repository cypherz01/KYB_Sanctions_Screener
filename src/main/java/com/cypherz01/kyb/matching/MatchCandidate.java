package com.cypherz01.kyb.matching;

import com.cypherz01.kyb.sanctions.model.NameKind;
import java.util.List;

/**
 * One sanctions name retrieved for scoring, with its entity's secondary identifiers.
 *
 * @param matchedName the sanctions name as published
 * @param birthDates partial ISO dates as published
 * @param countries the entity's countries (country, jurisdiction, mainCountry)
 * @param nationalities the entity's nationalities (nationality, citizenship)
 * @param sourceLists OpenSanctions source dataset ids
 */
public record MatchCandidate(
        long nameId,
        long entityId,
        String externalId,
        String schema,
        String caption,
        String matchedName,
        NameKind nameKind,
        NormalisedName name,
        List<String> birthDates,
        List<String> countries,
        List<String> nationalities,
        List<String> sourceLists,
        List<String> programIds,
        List<String> topics) {}
