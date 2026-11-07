package com.cypherz01.kyb.sanctions;

import com.cypherz01.kyb.matching.NameNormaliser;
import com.cypherz01.kyb.matching.NormalisedName;
import com.cypherz01.kyb.matching.SubjectType;
import com.cypherz01.kyb.sanctions.model.EntityName;
import com.cypherz01.kyb.sanctions.model.SanctionsEntityRecord;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persistence for dataset versions and their entities/names. Every write that must be all-or-nothing
 * (a batch, the activation swap, a cleanup) runs in its own transaction.
 */
public class SanctionsRepository {

    private static final String VERSION_COLUMNS =
            "id, source_url, version_label, checksum, entity_count, name_count, started_at, finished_at,"
                    + " status, is_active, error";

    private static final String INSERT_ENTITY =
            """
            INSERT INTO sanctions_entity (id, dataset_version_id, external_id, schema_type, caption, birth_dates,
                countries, nationalities, topics, source_lists, program_ids, raw)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            """;

    private static final String INSERT_NAME =
            """
            INSERT INTO sanctions_name (entity_id, dataset_version_id, name_original, name_kind, is_alias,
                name_normalised, tokens, name_phonetic)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final RowMapper<DatasetVersion> VERSION = (rs, i) -> new DatasetVersion(
            rs.getLong("id"),
            rs.getString("source_url"),
            rs.getString("version_label"),
            rs.getString("checksum"),
            rs.getObject("entity_count", Long.class),
            rs.getObject("name_count", Long.class),
            instant(rs, "started_at"),
            instant(rs, "finished_at"),
            DatasetStatus.valueOf(rs.getString("status")),
            rs.getBoolean("is_active"),
            rs.getString("error"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final NameNormaliser normaliser;

    public SanctionsRepository(JdbcTemplate jdbc, TransactionTemplate tx, NameNormaliser normaliser) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.normaliser = normaliser;
    }

    /**
     * @throws ImportAlreadyRunningException a LOADING version already exists (unique index)
     */
    public long insertLoading(String sourceUrl, Instant startedAt) {
        try {
            return jdbc.queryForObject(
                    "INSERT INTO sanctions_dataset_version (source_url, started_at, status) VALUES (?, ?, 'LOADING')"
                            + " RETURNING id",
                    Long.class,
                    sourceUrl,
                    utc(startedAt));
        } catch (DuplicateKeyException e) {
            Long running = jdbc
                    .query(
                            "SELECT id FROM sanctions_dataset_version WHERE status = 'LOADING'",
                            (rs, i) -> rs.getLong(1))
                    .stream()
                    .findFirst()
                    .orElse(null);
            throw new ImportAlreadyRunningException(
                    "A sanctions import is already running" + (running != null ? " (version " + running + ")" : ""), e);
        }
    }

    /** Records what the index says this run is importing, as soon as it is known. */
    public void describe(long versionId, String versionLabel, String sourceUrl) {
        jdbc.update(
                "UPDATE sanctions_dataset_version SET version_label = ?, source_url = ? WHERE id = ?",
                versionLabel,
                sourceUrl,
                versionId);
    }

    /** Inserts one batch of entities and their names in a single transaction. */
    public void insertBatch(long versionId, List<SanctionsEntityRecord> batch) {
        if (batch.isEmpty()) {
            return;
        }
        tx.executeWithoutResult(status -> {
            // Pre-allocate ids so names can reference their entity without a round trip per row.
            List<Long> ids = jdbc.queryForList(
                    "SELECT nextval('sanctions_entity_id_seq') FROM generate_series(1, ?)", Long.class, batch.size());

            jdbc.batchUpdate(INSERT_ENTITY, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    SanctionsEntityRecord e = batch.get(i);
                    ps.setLong(1, ids.get(i));
                    ps.setLong(2, versionId);
                    ps.setString(3, e.externalId());
                    ps.setString(4, e.schema());
                    ps.setString(5, e.caption());
                    setTextArray(ps, 6, e.birthDates());
                    setTextArray(ps, 7, e.countries());
                    setTextArray(ps, 8, e.nationalities());
                    setTextArray(ps, 9, e.topics());
                    setTextArray(ps, 10, e.sourceLists());
                    setTextArray(ps, 11, e.programIds());
                    ps.setString(12, e.raw().toString()); // Jackson's toString is valid JSON
                }

                @Override
                public int getBatchSize() {
                    return batch.size();
                }
            });

            List<Object[]> names = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                SubjectType type = SubjectType.forSchema(batch.get(i).schema());
                for (EntityName n : batch.get(i).names()) {
                    NormalisedName nn = normaliser.normalise(n.value(), type);
                    names.add(new Object[] {
                        ids.get(i),
                        versionId,
                        n.value(),
                        n.kind().name(),
                        n.isAlias(),
                        nn.normalised(),
                        nn.tokens().toArray(String[]::new),
                        nn.phoneticKey()
                    });
                }
            }
            jdbc.batchUpdate(INSERT_NAME, names);
        });
    }

    /**
     * The atomic swap: the previous active version is deactivated and this one activated in one
     * transaction, so readers see either the old dataset or the new one, never neither or both.
     */
    public void activate(long versionId, String checksum, long entityCount, long nameCount, Instant finishedAt) {
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE sanctions_dataset_version SET is_active = FALSE WHERE is_active");
            int updated = jdbc.update(
                    """
                    UPDATE sanctions_dataset_version
                       SET status = 'COMPLETED', is_active = TRUE, checksum = ?, entity_count = ?, name_count = ?,
                           finished_at = ?, normaliser_version = ?
                     WHERE id = ? AND status = 'LOADING'
                    """,
                    checksum,
                    entityCount,
                    nameCount,
                    utc(finishedAt),
                    NameNormaliser.VERSION, // names were normalised as they were inserted
                    versionId);
            if (updated != 1) {
                // e.g. recovered as interrupted meanwhile. Roll back so the old version stays active.
                throw new IllegalStateException("Version " + versionId + " is no longer LOADING; not activated");
            }
        });
    }

    /** Upstream unchanged: record the run, keep nothing it may have loaded. */
    public void markSkipped(long versionId, String checksum, Instant finishedAt) {
        finish(versionId, DatasetStatus.SKIPPED, checksum, null, finishedAt);
    }

    /** Removes everything this run loaded and records why it failed. */
    public void markFailed(long versionId, String error, Instant finishedAt) {
        finish(versionId, DatasetStatus.FAILED, null, error, finishedAt);
    }

    private void finish(long versionId, DatasetStatus status, String checksum, String error, Instant finishedAt) {
        tx.executeWithoutResult(s -> {
            jdbc.update("DELETE FROM sanctions_entity WHERE dataset_version_id = ?", versionId);
            jdbc.update(
                    """
                    UPDATE sanctions_dataset_version
                       SET status = ?, checksum = COALESCE(?, checksum), error = ?, finished_at = ?
                     WHERE id = ? AND status = 'LOADING'
                    """,
                    status.name(),
                    checksum,
                    error,
                    utc(finishedAt),
                    versionId);
        });
    }

    /** @return the ids of versions still marked LOADING */
    public List<Long> findLoadingIds() {
        return jdbc.queryForList(
                "SELECT id FROM sanctions_dataset_version WHERE status = 'LOADING' ORDER BY id", Long.class);
    }

    /**
     * Keeps the newest {@code keep} completed versions (the active one included) and prunes older
     * ones: their rows are deleted, their version record stays as PRUNED.
     *
     * @return the number of versions pruned
     */
    public int prune(int keep, Instant now) {
        List<Long> old = jdbc.queryForList(
                """
                SELECT id FROM sanctions_dataset_version
                 WHERE status = 'COMPLETED' AND NOT is_active
                 ORDER BY id DESC OFFSET ?
                """,
                Long.class,
                Math.max(keep - 1, 0));
        for (long id : old) {
            tx.executeWithoutResult(s -> {
                jdbc.update("DELETE FROM sanctions_entity WHERE dataset_version_id = ?", id);
                jdbc.update(
                        "UPDATE sanctions_dataset_version SET status = 'PRUNED', error = NULL WHERE id = ?"
                                + " AND status = 'COMPLETED' AND NOT is_active",
                        id);
            });
        }
        return old.size();
    }

    public Optional<DatasetVersion> findActive() {
        return jdbc
                .query("SELECT " + VERSION_COLUMNS + " FROM sanctions_dataset_version WHERE is_active", VERSION)
                .stream()
                .findFirst();
    }

    public Optional<DatasetVersion> findLatest() {
        return jdbc
                .query(
                        "SELECT " + VERSION_COLUMNS + " FROM sanctions_dataset_version ORDER BY id DESC LIMIT 1",
                        VERSION)
                .stream()
                .findFirst();
    }

    public boolean isLoading() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sanctions_dataset_version WHERE status = 'LOADING')", Boolean.class));
    }

    public Map<String, Long> countBySchema(long versionId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT schema_type, count(*) FROM sanctions_entity WHERE dataset_version_id = ?
                 GROUP BY schema_type ORDER BY schema_type
                """,
                rs -> {
                    counts.put(rs.getString(1), rs.getLong(2));
                },
                versionId);
        return counts;
    }

    private static void setTextArray(PreparedStatement ps, int index, List<String> values) throws SQLException {
        Connection c = ps.getConnection();
        ps.setArray(index, c.createArrayOf("text", values.toArray()));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
