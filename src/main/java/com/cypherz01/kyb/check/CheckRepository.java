package com.cypherz01.kyb.check;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Append-only storage of checks (spec §6, §11): insert and read. There is no update or delete. */
public class CheckRepository {

    private final JdbcTemplate jdbc;

    public CheckRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(
            UUID id,
            String companyNumber,
            Instant requestedAt,
            Instant completedAt,
            String decision,
            int score,
            long datasetVersionId,
            String scoringConfigVersion,
            String engineVersion,
            String requestJson,
            String responseJson) {
        jdbc.update(
                """
                INSERT INTO kyb_check (id, company_number, requested_at, completed_at, decision, score,
                    sanctions_dataset_version_id, scoring_config_version, engine_version, request, response)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::json)
                """,
                id,
                companyNumber,
                OffsetDateTime.ofInstant(requestedAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(completedAt, ZoneOffset.UTC),
                decision,
                score,
                datasetVersionId,
                scoringConfigVersion,
                engineVersion,
                requestJson,
                responseJson);
    }

    public Optional<StoredCheck> find(UUID id) {
        return jdbc
                .query(
                        """
                        SELECT id, company_number, requested_at, completed_at, decision, score,
                               sanctions_dataset_version_id, scoring_config_version, engine_version,
                               request::text AS request, response::text AS response
                          FROM kyb_check WHERE id = ?
                        """,
                        (rs, i) -> new StoredCheck(
                                rs.getObject("id", UUID.class),
                                rs.getString("company_number"),
                                rs.getObject("requested_at", OffsetDateTime.class)
                                        .toInstant(),
                                rs.getObject("completed_at", OffsetDateTime.class)
                                        .toInstant(),
                                rs.getString("decision"),
                                rs.getInt("score"),
                                rs.getLong("sanctions_dataset_version_id"),
                                rs.getString("scoring_config_version"),
                                rs.getString("engine_version"),
                                rs.getString("request"),
                                rs.getString("response")),
                        id)
                .stream()
                .findFirst();
    }

    /** @return the company's most recent check */
    public Optional<UUID> findLatestId(String companyNumber) {
        return jdbc
                .queryForList(
                        "SELECT id FROM kyb_check WHERE company_number = ? ORDER BY requested_at DESC LIMIT 1",
                        UUID.class,
                        companyNumber)
                .stream()
                .findFirst();
    }

    public Optional<String> findResponse(UUID id) {
        return jdbc.queryForList("SELECT response::text FROM kyb_check WHERE id = ?", String.class, id).stream()
                .findFirst();
    }
}
