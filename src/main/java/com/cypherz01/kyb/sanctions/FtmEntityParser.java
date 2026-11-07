package com.cypherz01.kyb.sanctions;

import com.cypherz01.kyb.sanctions.model.EntityName;
import com.cypherz01.kyb.sanctions.model.NameKind;
import com.cypherz01.kyb.sanctions.model.SanctionsEntityRecord;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Turns one FollowTheMoney entity (one line of {@code entities.ftm.json}) into a screenable record,
 * or filters it out. Pure and stateless, so every extraction rule can be unit-tested.
 *
 * <p>The file also holds entities that cannot be screened (Sanction, Address, Ownership, Vessel,
 * ...) and people who are only related to targets. Only entities with a configured schema and at
 * least one configured topic are kept.
 */
public class FtmEntityParser {

    private final Set<String> schemas;
    private final Set<String> topics;

    public FtmEntityParser(Set<String> schemas, Set<String> topics) {
        this.schemas = Set.copyOf(schemas);
        this.topics = Set.copyOf(topics);
    }

    /**
     * @return the record, or empty if the entity is filtered out or has no usable name
     * @throws MalformedEntityException if the entity has no id or schema
     */
    public Optional<SanctionsEntityRecord> parse(JsonNode entity) {
        String id = text(entity.path("id"));
        if (id == null) {
            throw new MalformedEntityException("entity without an id");
        }
        String schema = text(entity.path("schema"));
        if (schema == null) {
            throw new MalformedEntityException("entity " + id + " without a schema");
        }
        if (!schemas.contains(schema)) {
            return Optional.empty();
        }

        JsonNode props = entity.path("properties");
        List<String> entityTopics = values(props, UnaryOperator.identity(), "topics");
        if (entityTopics.stream().noneMatch(topics::contains)) {
            return Optional.empty();
        }

        String caption = text(entity.path("caption"));
        List<EntityName> names = names(props, caption);
        if (names.isEmpty()) {
            return Optional.empty(); // nothing to screen against
        }

        UnaryOperator<String> lower = s -> s.toLowerCase(Locale.ROOT);
        return Optional.of(new SanctionsEntityRecord(
                id,
                schema,
                caption != null ? caption : names.get(0).value(),
                names,
                values(props, UnaryOperator.identity(), "birthDate"),
                values(props, lower, "country", "jurisdiction", "mainCountry"),
                values(props, lower, "nationality", "citizenship"),
                entityTopics,
                values(entity, UnaryOperator.identity(), "datasets"),
                values(props, UnaryOperator.identity(), "programId"),
                entity));
    }

    /**
     * Primary names, then aliases, then previous names; the first kind seen wins on duplicates.
     * {@code weakAlias} is never used: FtM documents it as unsuitable for screening.
     */
    private static List<EntityName> names(JsonNode props, String caption) {
        Map<String, NameKind> names = new LinkedHashMap<>();
        values(props, UnaryOperator.identity(), "name").forEach(n -> names.putIfAbsent(n, NameKind.PRIMARY));
        if (names.isEmpty()) {
            composedName(props).or(() -> Optional.ofNullable(caption)).ifPresent(n -> names.put(n, NameKind.PRIMARY));
        }
        values(props, UnaryOperator.identity(), "alias").forEach(n -> names.putIfAbsent(n, NameKind.ALIAS));
        values(props, UnaryOperator.identity(), "previousName").forEach(n -> names.putIfAbsent(n, NameKind.PREVIOUS));

        List<EntityName> result = new ArrayList<>(names.size());
        names.forEach((value, kind) -> result.add(new EntityName(value, kind)));
        return result;
    }

    /** Some people are published only as name parts. */
    private static Optional<String> composedName(JsonNode props) {
        String first = firstValue(props, "firstName");
        String last = firstValue(props, "lastName");
        if (first == null && last == null) {
            return Optional.empty();
        }
        List<String> parts = new ArrayList<>(3);
        for (String part : new String[] {first, firstValue(props, "middleName"), last}) {
            if (part != null) {
                parts.add(part);
            }
        }
        return Optional.of(String.join(" ", parts));
    }

    private static String firstValue(JsonNode props, String property) {
        List<String> values = values(props, UnaryOperator.identity(), property);
        return values.isEmpty() ? null : values.get(0);
    }

    /** Trimmed, non-blank, de-duplicated values of the given array properties, in order. */
    private static List<String> values(JsonNode node, UnaryOperator<String> transform, String... properties) {
        Set<String> out = new LinkedHashSet<>();
        for (String property : properties) {
            for (JsonNode v : node.path(property)) {
                String s = text(v);
                if (s != null) {
                    out.add(transform.apply(s));
                }
            }
        }
        return List.copyOf(out);
    }

    private static String text(JsonNode node) {
        if (!node.isTextual()) {
            return null;
        }
        String s = node.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
