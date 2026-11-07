package com.cypherz01.kyb.matching;

import com.cypherz01.kyb.sanctions.model.NameKind;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stage 1 of matching (spec §8.2): finds the few sanctions names worth scoring, so the Java scorer
 * never sees the whole dataset. It unions two indexed arms, each capped and limited to schemas the
 * subject may match:
 *
 * <ul>
 *   <li>trigram similarity ({@code %}, GIN index) on the normalised name, which absorbs typos,
 *       word order and partial names;
 *   <li>exact match on the sorted phonetic key, which catches romanisation variants whose spelling
 *       differs too much for trigrams.
 * </ul>
 *
 * Results are ranked by trigram similarity and capped at {@code max-candidates}.
 */
public class CandidateRetriever {

    private static final String SQL =
            """
            WITH trigram AS (
                SELECT n.id, similarity(n.name_normalised, :q) AS sim
                  FROM sanctions_name n JOIN sanctions_entity e ON e.id = n.entity_id
                 WHERE n.dataset_version_id = :version AND n.name_normalised % :q
                   AND e.schema_type IN (:schemas)
                 ORDER BY sim DESC, n.id
                 LIMIT :max
            ), phonetic AS (
                SELECT n.id, similarity(n.name_normalised, :q) AS sim
                  FROM sanctions_name n JOIN sanctions_entity e ON e.id = n.entity_id
                 WHERE n.dataset_version_id = :version AND n.name_phonetic = :phonetic
                   AND e.schema_type IN (:schemas)
                 ORDER BY sim DESC, n.id
                 LIMIT :max
            ), ids AS (
                SELECT id, max(sim) AS sim FROM (SELECT * FROM trigram UNION ALL SELECT * FROM phonetic) u
                 GROUP BY id
                 ORDER BY sim DESC, id
                 LIMIT :max
            )
            SELECT n.id, n.name_original, n.name_kind, n.tokens, e.id AS entity_id, e.external_id, e.schema_type,
                   e.caption, e.birth_dates, e.countries, e.nationalities, e.source_lists, e.program_ids, e.topics
              FROM ids JOIN sanctions_name n ON n.id = ids.id JOIN sanctions_entity e ON e.id = n.entity_id
             ORDER BY ids.sim DESC, n.id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate readOnlyTx;
    private final NameNormaliser normaliser;
    private final MatchingProperties.Retrieval config;

    public CandidateRetriever(
            NamedParameterJdbcTemplate jdbc,
            TransactionTemplate readOnlyTx,
            NameNormaliser normaliser,
            MatchingProperties.Retrieval config) {
        this.jdbc = jdbc;
        this.readOnlyTx = readOnlyTx;
        this.normaliser = normaliser;
        this.config = config;
    }

    public List<MatchCandidate> retrieve(long datasetVersionId, MatchSubject subject) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("q", subject.name().normalised())
                .addValue("phonetic", subject.name().phoneticKey())
                .addValue("version", datasetVersionId)
                .addValue("schemas", List.copyOf(subject.type().schemas()))
                .addValue("max", config.maxCandidates());
        return readOnlyTx.execute(status -> {
            // Transaction-local, so the pooled connection is not left with a changed threshold.
            jdbc.getJdbcTemplate()
                    .queryForObject(
                            "SELECT set_config('pg_trgm.similarity_threshold', ?, true)",
                            String.class,
                            Double.toString(config.trigramThreshold()));
            return jdbc.query(SQL, params, (rs, i) -> candidate(rs));
        });
    }

    private MatchCandidate candidate(ResultSet rs) throws SQLException {
        String original = rs.getString("name_original");
        return new MatchCandidate(
                rs.getLong("id"),
                rs.getLong("entity_id"),
                rs.getString("external_id"),
                rs.getString("schema_type"),
                rs.getString("caption"),
                original,
                NameKind.valueOf(rs.getString("name_kind")),
                normaliser.fromTokens(original, list(rs.getArray("tokens"))),
                list(rs.getArray("birth_dates")),
                list(rs.getArray("countries")),
                list(rs.getArray("nationalities")),
                list(rs.getArray("source_lists")),
                list(rs.getArray("program_ids")),
                list(rs.getArray("topics")));
    }

    private static List<String> list(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
