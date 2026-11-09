package com.cypherz01.kyb.monitoring;

import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Compares two dataset versions (ADR-0005). */
public class DatasetDeltas {

    /**
     * What screening reads about an entity, as one comparable value. Arrays and names are sorted, so a
     * reordering upstream is not a change. Captions and source lists are left out, as neither can change
     * a result.
     */
    private static final String SIGNATURE =
            """
            ROW(e.schema_type,
                ARRAY(SELECT unnest(e.birth_dates) ORDER BY 1),
                ARRAY(SELECT unnest(e.nationalities) ORDER BY 1),
                ARRAY(SELECT unnest(e.countries) ORDER BY 1),
                ARRAY(SELECT unnest(e.topics) ORDER BY 1),
                ARRAY(SELECT n.name_kind || ':' || n.name_original FROM sanctions_name n
                       WHERE n.entity_id = e.id ORDER BY 1))::text
            """;

    private static final String SQL =
            """
            WITH older AS (SELECT e.external_id, %1$s AS signature FROM sanctions_entity e WHERE e.dataset_version_id = ?),
                 newer AS (SELECT e.external_id, %1$s AS signature FROM sanctions_entity e WHERE e.dataset_version_id = ?)
            SELECT COALESCE(o.external_id, n.external_id) AS external_id,
                   o.external_id IS NULL AS added,
                   n.external_id IS NULL AS removed
              FROM older o FULL JOIN newer n ON o.external_id = n.external_id
             WHERE o.signature IS DISTINCT FROM n.signature
            """
                    .formatted(SIGNATURE);

    private final JdbcTemplate jdbc;

    public DatasetDeltas(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Compares names, birth dates, nationalities, countries, topics and schema. */
    public DatasetDelta between(long olderVersionId, long newerVersionId) {
        Set<String> added = new HashSet<>();
        Set<String> removed = new HashSet<>();
        Set<String> changed = new HashSet<>();
        jdbc.query(
                SQL,
                rs -> {
                    String id = rs.getString("external_id");
                    if (rs.getBoolean("added")) {
                        added.add(id);
                    } else if (rs.getBoolean("removed")) {
                        removed.add(id);
                    } else {
                        changed.add(id);
                    }
                },
                olderVersionId,
                newerVersionId);
        return new DatasetDelta(Set.copyOf(added), Set.copyOf(removed), Set.copyOf(changed));
    }
}
