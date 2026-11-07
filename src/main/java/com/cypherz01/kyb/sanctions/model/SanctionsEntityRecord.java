package com.cypherz01.kyb.sanctions.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * A screenable entity extracted from one FtM line. Lists keep upstream order, without duplicates.
 *
 * @param names primary names first, then aliases, then previous names. {@code weakAlias} is
 *     excluded because FtM marks it as unsuitable for screening.
 * @param birthDates partial ISO dates exactly as published ({@code 1971}, {@code 1971-03}, ...)
 * @param countries {@code country}, {@code jurisdiction}, {@code mainCountry}, lower-cased
 * @param nationalities {@code nationality}, {@code citizenship}, lower-cased
 * @param sourceLists the OpenSanctions source datasets listing the entity
 * @param programIds sanction programme ids ({@code programId})
 * @param raw the entity exactly as published
 */
public record SanctionsEntityRecord(
        String externalId,
        String schema,
        String caption,
        List<EntityName> names,
        List<String> birthDates,
        List<String> countries,
        List<String> nationalities,
        List<String> topics,
        List<String> sourceLists,
        List<String> programIds,
        JsonNode raw) {}
