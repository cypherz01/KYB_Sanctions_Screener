package com.cypherz01.kyb.monitoring;

import com.cypherz01.kyb.matching.MatchBand;
import com.cypherz01.kyb.matching.MatchCandidate;
import com.cypherz01.kyb.matching.MatchScore;
import com.cypherz01.kyb.matching.MatchSubject;
import com.cypherz01.kyb.matching.MatchingProperties;
import com.cypherz01.kyb.matching.NameMatcher;
import com.cypherz01.kyb.matching.NameNormaliser;
import com.cypherz01.kyb.matching.NormalisedName;
import com.cypherz01.kyb.matching.PartialDate;
import com.cypherz01.kyb.matching.SubjectType;
import com.cypherz01.kyb.sanctions.model.NameKind;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Screens only what changed against the monitored subjects, so the work scales with the size of the
 * change rather than the number of subscriptions (ADR-0005). It uses the normaliser, the retrieval rule
 * and the matcher of forward screening, in the same orientation of subject compared with listed name,
 * so the matcher's asymmetric rules still apply.
 */
public class ReverseScreener {

    private static final String NAMES =
            """
            SELECT n.id, n.name_original, n.name_kind, n.tokens, n.name_normalised, n.name_phonetic,
                   e.id AS entity_id, e.external_id, e.schema_type, e.caption, e.birth_dates, e.countries,
                   e.nationalities, e.source_lists, e.program_ids, e.topics
              FROM sanctions_entity e JOIN sanctions_name n ON n.entity_id = e.id
             WHERE e.dataset_version_id = :version AND e.external_id IN (:ids)
            """;

    /** The same two arms as CandidateRetriever: trigram similarity, or an exact phonetic key. */
    private static final String SUBJECTS =
            """
            SELECT id, subscription_id, published_name, screened_name, subject_type, birth_date, countries
              FROM monitoring_subject
             WHERE name_normalised % :normalised OR name_phonetic = :phonetic
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate tx;
    private final NameNormaliser normaliser;
    private final NameMatcher matcher;
    private final MatchingProperties.Retrieval retrieval;

    public ReverseScreener(
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            NameNormaliser normaliser,
            NameMatcher matcher,
            MatchingProperties.Retrieval retrieval) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.tx = tx;
        this.normaliser = normaliser;
        this.matcher = matcher;
        this.retrieval = retrieval;
    }

    /**
     * @param datasetVersionId the version the added and changed entities are read from
     * @return every monitored subject that matches an added or changed entity
     */
    public Set<ReverseHit> screen(DatasetDelta delta, long datasetVersionId) {
        Set<String> ids = new HashSet<>(delta.added());
        ids.addAll(delta.changed());
        if (ids.isEmpty()) {
            return Set.of();
        }
        return tx.execute(status -> {
            // Transaction-local, so the pooled connection is not left with a changed threshold.
            jdbc.queryForObject(
                    "SELECT set_config('pg_trgm.similarity_threshold', ?, true)",
                    String.class,
                    Double.toString(retrieval.trigramThreshold()));
            return screenNames(ids, datasetVersionId);
        });
    }

    private Set<ReverseHit> screenNames(Set<String> ids, long datasetVersionId) {
        List<ListedName> names = named.query(
                NAMES,
                new MapSqlParameterSource()
                        .addValue("version", datasetVersionId)
                        .addValue("ids", ids),
                (rs, i) -> listedName(rs));

        // All of an entity's names, keeping the best per subject and entity, as forward screening does.
        Map<SubjectEntity, MatchScore> best = new HashMap<>();
        for (ListedName listed : names) {
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("normalised", listed.normalised())
                    .addValue("phonetic", listed.phonetic());
            for (WatchedSubject subject : named.query(SUBJECTS, params, (rs, i) -> watchedSubject(rs))) {
                if (!subject.type().schemas().contains(listed.candidate().schema())) {
                    continue;
                }
                MatchScore score = matcher.score(subject.match(), listed.candidate());
                if (score.band() != null) {
                    best.merge(
                            new SubjectEntity(subject, listed.candidate().externalId()),
                            score,
                            (a, b) -> b.similarity() > a.similarity() ? b : a);
                }
            }
        }

        Set<ReverseHit> hits = new HashSet<>();
        best.forEach((key, score) -> {
            MatchBand band = score.band();
            hits.add(new ReverseHit(
                    key.subject().subscriptionId(), key.subject().publishedName(), key.externalId(), band));
        });
        return Set.copyOf(hits);
    }

    private ListedName listedName(ResultSet rs) throws SQLException {
        String original = rs.getString("name_original");
        MatchCandidate candidate = new MatchCandidate(
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
        return new ListedName(candidate, rs.getString("name_normalised"), rs.getString("name_phonetic"));
    }

    private WatchedSubject watchedSubject(ResultSet rs) throws SQLException {
        SubjectType type = SubjectType.valueOf(rs.getString("subject_type"));
        String birth = rs.getString("birth_date");
        PartialDate birthDate = birth == null ? null : PartialDate.parse(birth).orElse(null);
        NormalisedName name = normaliser.normalise(rs.getString("screened_name"), type);
        return new WatchedSubject(
                rs.getLong("id"),
                rs.getObject("subscription_id", UUID.class),
                rs.getString("published_name"),
                type,
                new MatchSubject(name, type, birthDate, Set.copyOf(list(rs.getArray("countries")))));
    }

    private static List<String> list(Array array) throws SQLException {
        return array == null ? new ArrayList<>() : Arrays.asList((String[]) array.getArray());
    }

    private record ListedName(MatchCandidate candidate, String normalised, String phonetic) {}

    private record WatchedSubject(
            long id, UUID subscriptionId, String publishedName, SubjectType type, MatchSubject match) {}

    private record SubjectEntity(WatchedSubject subject, String externalId) {}
}
