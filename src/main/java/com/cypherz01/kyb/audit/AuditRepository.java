package com.cypherz01.kyb.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Append-only storage of snapshots and events (spec §6): insert and read, nothing else. The database
 * also rejects updates and deletes (V6), so this is not only a matter of good behaviour here.
 */
public class AuditRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AuditRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insertSnapshots(UUID checkId, List<SourceSnapshot> snapshots) {
        List<Object[]> rows = new ArrayList<>(snapshots.size());
        for (SourceSnapshot s : snapshots) {
            rows.add(new Object[] {
                checkId, s.source(), s.operation(), utc(s.fetchedAt()), s.fromCache(), s.stale(), write(s.payload())
            });
        }
        jdbc.batchUpdate(
                """
                INSERT INTO kyb_check_source_snapshot (check_id, source, operation, fetched_at, from_cache, stale, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?::json)
                """,
                rows);
    }

    public void append(UUID checkId, AuditEvent event) {
        jdbc.update(
                "INSERT INTO audit_event (check_id, event_type, occurred_at, details) VALUES (?, ?, ?, ?::jsonb)",
                checkId,
                event.eventType().name(),
                utc(event.occurredAt()),
                write(event.details()));
    }

    public List<SourceSnapshot> findSnapshots(UUID checkId) {
        return jdbc.query(
                """
                SELECT source, operation, fetched_at, from_cache, stale, payload::text AS payload
                  FROM kyb_check_source_snapshot WHERE check_id = ? ORDER BY id
                """,
                (rs, i) -> new SourceSnapshot(
                        rs.getString("source"),
                        rs.getString("operation"),
                        instant(rs),
                        rs.getBoolean("from_cache"),
                        rs.getBoolean("stale"),
                        read(rs.getString("payload"))),
                checkId);
    }

    /** @return the check's events, in the order they were appended */
    public List<AuditEvent> findEvents(UUID checkId) {
        return jdbc.query(
                "SELECT event_type, occurred_at, details::text AS details FROM audit_event"
                        + " WHERE check_id = ? ORDER BY id",
                (rs, i) -> new AuditEvent(
                        AuditEventType.valueOf(rs.getString("event_type")),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        read(rs.getString("details"))),
                checkId);
    }

    private String write(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Audit data not serialisable", e);
        }
    }

    private JsonNode read(String stored) {
        try {
            return json.readTree(stored);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored audit data is not valid JSON", e);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs) throws SQLException {
        OffsetDateTime t = rs.getObject("fetched_at", OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
