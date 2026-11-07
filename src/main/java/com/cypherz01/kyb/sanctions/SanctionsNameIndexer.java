package com.cypherz01.kyb.sanctions;

import com.cypherz01.kyb.matching.NameNormaliser;
import com.cypherz01.kyb.matching.NormalisedName;
import com.cypherz01.kyb.matching.SubjectType;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the stored matcher columns ({@code name_normalised}, {@code name_phonetic}, {@code tokens})
 * in step with {@link com.cypherz01.kyb.matching.NameNormaliser#VERSION}. Imports fill them as
 * they load. This re-indexes completed versions loaded before M3, or by an older normaliser, in
 * place, without downloading again (the unchanged-checksum skip would otherwise keep them stale
 * forever).
 */
public class SanctionsNameIndexer {

    private static final Logger log = LoggerFactory.getLogger(SanctionsNameIndexer.class);
    private static final int BATCH = 2_000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final NameNormaliser normaliser;

    public SanctionsNameIndexer(JdbcTemplate jdbc, TransactionTemplate tx, NameNormaliser normaliser) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.normaliser = normaliser;
    }

    /**
     * Re-normalises every name of each COMPLETED version whose {@code normaliser_version} differs
     * from the current one, then records the current version on it.
     *
     * @return the number of dataset versions re-indexed
     */
    public int reindexStaleVersions() {
        // Active version first: it is the one screening needs.
        List<Long> stale = jdbc.queryForList(
                """
                SELECT id FROM sanctions_dataset_version
                 WHERE status = 'COMPLETED' AND normaliser_version IS DISTINCT FROM ?
                 ORDER BY is_active DESC, id DESC
                """,
                Long.class,
                NameNormaliser.VERSION);
        for (long versionId : stale) {
            long start = System.nanoTime();
            long names = reindex(versionId);
            jdbc.update(
                    "UPDATE sanctions_dataset_version SET normaliser_version = ? WHERE id = ?",
                    NameNormaliser.VERSION,
                    versionId);
            log.info(
                    "re-indexed sanctions names version={} names={} normaliser={} durationMs={}",
                    versionId,
                    names,
                    NameNormaliser.VERSION,
                    (System.nanoTime() - start) / 1_000_000);
        }
        return stale.size();
    }

    /** Keyset-paginated, one transaction per batch, so memory and lock time stay bounded. */
    private long reindex(long versionId) {
        long lastId = 0;
        long total = 0;
        while (true) {
            List<Object[]> rows = jdbc.query(
                    """
                    SELECT n.id, n.name_original, e.schema_type
                      FROM sanctions_name n JOIN sanctions_entity e ON e.id = n.entity_id
                     WHERE n.dataset_version_id = ? AND n.id > ?
                     ORDER BY n.id LIMIT ?
                    """,
                    (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3)},
                    versionId,
                    lastId,
                    BATCH);
            if (rows.isEmpty()) {
                return total;
            }
            List<Object[]> updates = new ArrayList<>(rows.size());
            for (Object[] row : rows) {
                NormalisedName n = normaliser.normalise((String) row[1], SubjectType.forSchema((String) row[2]));
                updates.add(new Object[] {n.normalised(), n.tokens().toArray(String[]::new), n.phoneticKey(), row[0]});
            }
            tx.executeWithoutResult(s -> jdbc.batchUpdate(
                    "UPDATE sanctions_name SET name_normalised = ?, tokens = ?, name_phonetic = ? WHERE id = ?",
                    updates));
            lastId = (Long) rows.get(rows.size() - 1)[0];
            total += rows.size();
        }
    }
}
