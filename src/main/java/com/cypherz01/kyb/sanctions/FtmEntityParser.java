package com.cypherz01.kyb.sanctions;

import com.cypherz01.kyb.sanctions.model.SanctionsEntityRecord;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.Set;

/**
 * Turns one FollowTheMoney entity (one line of {@code entities.ftm.json}) into a screenable record,
 * or filters it out. Pure and stateless, so every extraction rule can be unit-tested.
 *
 * <p>The file also holds entities that cannot be screened (Sanction, Address, Ownership, Vessel,
 * ...) and people who are only related to targets. Only entities with a configured schema and at
 * least one configured topic are kept.
 */
public class FtmEntityParser {

    public FtmEntityParser(Set<String> schemas, Set<String> topics) {}

    /**
     * @return the record, or empty if the entity is filtered out or has no usable name
     * @throws MalformedEntityException if the entity has no id or schema
     */
    public Optional<SanctionsEntityRecord> parse(JsonNode entity) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
