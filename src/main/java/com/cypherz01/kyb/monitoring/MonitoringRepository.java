package com.cypherz01.kyb.monitoring;

import com.cypherz01.kyb.check.Subject;
import com.cypherz01.kyb.matching.NameNormaliser;
import com.cypherz01.kyb.matching.NormalisedName;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** The monitoring tables. Claims use FOR UPDATE SKIP LOCKED, so several workers cannot collide. */
public class MonitoringRepository {

    /** The single row saying what subjects were last screened against. */
    public record State(Long lastDatasetVersionId, String fingerprint) {}

    public record Run(long id, long datasetVersionId) {}

    /** A claimed re-check, with what it needs to run. */
    public record Job(long id, UUID subscriptionId, String trigger, String companyNumber, UUID latestCheckId) {}

    private static final String SUBSCRIPTION_COLUMNS =
            "id, company_number, created_at, latest_check_id, next_recheck_at";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final NameNormaliser normaliser;
    private final ObjectMapper json;

    public MonitoringRepository(JdbcTemplate jdbc, NameNormaliser normaliser, ObjectMapper json) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.normaliser = normaliser;
        this.json = json;
    }

    // ---------- subscriptions ----------

    public void insertSubscription(Subscription s) {
        jdbc.update(
                """
                INSERT INTO monitoring_subscription (id, company_number, created_at, latest_check_id, next_recheck_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                s.subscriptionId(),
                s.companyNumber(),
                utc(s.createdAt()),
                s.latestCheckId(),
                utc(s.nextRecheckAt()));
    }

    public Optional<Subscription> findActive(String companyNumber) {
        return jdbc
                .query(
                        "SELECT " + SUBSCRIPTION_COLUMNS
                                + " FROM monitoring_subscription WHERE company_number = ? AND ended_at IS NULL",
                        (rs, i) -> subscription(rs),
                        companyNumber)
                .stream()
                .findFirst();
    }

    /**
     * As {@link #findActive}, holding the row until this transaction ends, so checks of one company
     * compare in turn: the second sees what the first left. It does not block a job being queued.
     */
    public Optional<Subscription> lockActive(String companyNumber) {
        return jdbc
                .query(
                        "SELECT " + SUBSCRIPTION_COLUMNS + " FROM monitoring_subscription"
                                + " WHERE company_number = ? AND ended_at IS NULL FOR NO KEY UPDATE",
                        (rs, i) -> subscription(rs),
                        companyNumber)
                .stream()
                .findFirst();
    }

    /**
     * Keeps the record alerts refer to, and deletes the watched names, which is the point of stopping.
     * The job goes first: a worker holds its job and then takes the subscription, so the same order here
     * waits for a running re-check rather than deadlocking with it.
     */
    public void end(UUID subscriptionId, Instant at) {
        jdbc.update("DELETE FROM monitoring_job WHERE subscription_id = ?", subscriptionId);
        jdbc.update("UPDATE monitoring_subscription SET ended_at = ? WHERE id = ?", utc(at), subscriptionId);
        jdbc.update("DELETE FROM monitoring_subject WHERE subscription_id = ?", subscriptionId);
        jdbc.update("DELETE FROM monitoring_match WHERE subscription_id = ?", subscriptionId);
    }

    /** Points the subscription at its newest check, and replaces who it watches and what they match. */
    public void refresh(
            UUID subscriptionId,
            UUID latestCheckId,
            Instant nextRecheckAt,
            List<Subject> subjects,
            Set<String> matched) {
        jdbc.update(
                "UPDATE monitoring_subscription SET latest_check_id = ?, next_recheck_at = ? WHERE id = ?",
                latestCheckId,
                utc(nextRecheckAt),
                subscriptionId);
        replaceIndex(subscriptionId, subjects, matched);
    }

    public void replaceIndex(UUID subscriptionId, List<Subject> subjects, Set<String> matched) {
        jdbc.update("DELETE FROM monitoring_subject WHERE subscription_id = ?", subscriptionId);
        jdbc.update("DELETE FROM monitoring_match WHERE subscription_id = ?", subscriptionId);
        for (Subject s : subjects) {
            NormalisedName name = normaliser.normalise(s.screenedName(), s.type());
            if (name.tokens().isEmpty()) {
                continue; // cannot be screened in either direction
            }
            jdbc.update(con -> {
                var ps = con.prepareStatement(
                        """
                                INSERT INTO monitoring_subject (subscription_id, published_name, screened_name,
                                    subject_type, birth_date, countries, name_normalised, name_phonetic)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                """);
                ps.setObject(1, subscriptionId);
                ps.setString(2, s.publishedName());
                ps.setString(3, s.screenedName());
                ps.setString(4, s.type().name());
                ps.setString(5, s.birthDate() == null ? null : s.birthDate().toString());
                ps.setArray(6, con.createArrayOf("text", s.countries().toArray()));
                ps.setString(7, name.normalised());
                ps.setString(8, name.phoneticKey());
                return ps;
            });
        }
        for (String externalId : matched) {
            jdbc.update(
                    "INSERT INTO monitoring_match (subscription_id, external_id) VALUES (?, ?)",
                    subscriptionId,
                    externalId);
        }
    }

    /** @return the subscriptions whose latest check matched any of these entities */
    public List<UUID> subscriptionsMatching(Collection<String> externalIds) {
        if (externalIds.isEmpty()) {
            return List.of();
        }
        return named.queryForList(
                "SELECT DISTINCT subscription_id FROM monitoring_match WHERE external_id IN (:ids)",
                new MapSqlParameterSource("ids", externalIds),
                UUID.class);
    }

    // ---------- runs and state ----------

    /** @return the state, locked for this transaction, or empty if another worker holds it */
    public Optional<State> lockState() {
        return jdbc
                .query(
                        "SELECT last_dataset_version_id, fingerprint FROM monitoring_state FOR UPDATE SKIP LOCKED",
                        (rs, i) -> new State(
                                rs.getObject("last_dataset_version_id", Long.class), rs.getString("fingerprint")))
                .stream()
                .findFirst();
    }

    public void saveState(Long lastDatasetVersionId, String fingerprint) {
        jdbc.update(
                "UPDATE monitoring_state SET last_dataset_version_id = ?, fingerprint = ?",
                lastDatasetVersionId,
                fingerprint);
    }

    public List<Run> pendingRuns() {
        return jdbc.query(
                "SELECT id, dataset_version_id FROM monitoring_run WHERE finished_at IS NULL ORDER BY id",
                (rs, i) -> new Run(rs.getLong("id"), rs.getLong("dataset_version_id")));
    }

    public void finishRun(long runId, Instant at) {
        jdbc.update("UPDATE monitoring_run SET finished_at = ? WHERE id = ?", utc(at), runId);
    }

    // ---------- jobs ----------

    /** One pending re-check per subscription is enough, however many things ask for it. */
    public void enqueue(Collection<UUID> subscriptionIds, String trigger, Instant at) {
        for (UUID id : subscriptionIds) {
            // If that company's re-check is running, this waits for it and then queues another, as the one
            // running may have used an older list. A job waiting to retry is brought forward.
            jdbc.update(
                    """
                    INSERT INTO monitoring_job (subscription_id, trigger, created_at, next_attempt_at)
                    VALUES (?, ?, ?, ?) ON CONFLICT (subscription_id)
                    DO UPDATE SET next_attempt_at = LEAST(monitoring_job.next_attempt_at, EXCLUDED.next_attempt_at)
                    """,
                    id,
                    trigger,
                    utc(at),
                    utc(at));
        }
    }

    public void enqueueAllActive(String trigger, Instant at) {
        enqueue(
                jdbc.queryForList("SELECT id FROM monitoring_subscription WHERE ended_at IS NULL", UUID.class),
                trigger,
                at);
    }

    /** Moves each due subscription on by one interval, so a second worker cannot queue it again. */
    public void enqueueDuePeriodic(Instant now, Instant next) {
        List<UUID> due = jdbc.queryForList(
                """
                UPDATE monitoring_subscription SET next_recheck_at = ?
                 WHERE ended_at IS NULL AND next_recheck_at <= ?
                RETURNING id
                """,
                UUID.class,
                utc(next),
                utc(now));
        // Nothing here waits: this holds the subscription rows a running re-check is about to take, and
        // that re-check, or a job already queued, makes this one unnecessary anyway.
        for (UUID id : due) {
            jdbc.update(
                    """
                    INSERT INTO monitoring_job (subscription_id, trigger, created_at, next_attempt_at)
                    VALUES (?, 'PERIODIC', ?, ?) ON CONFLICT (subscription_id) DO NOTHING
                    """,
                    id,
                    utc(now),
                    utc(now));
        }
    }

    public Optional<Job> claimDueJob(Instant now) {
        return jdbc
                .query(
                        """
                        SELECT j.id, j.subscription_id, j.trigger, s.company_number, s.latest_check_id
                          FROM monitoring_job j JOIN monitoring_subscription s ON s.id = j.subscription_id
                         WHERE j.next_attempt_at <= ?
                         ORDER BY j.next_attempt_at, j.id
                         LIMIT 1
                           FOR UPDATE OF j SKIP LOCKED
                        """,
                        (rs, i) -> new Job(
                                rs.getLong("id"),
                                rs.getObject("subscription_id", UUID.class),
                                rs.getString("trigger"),
                                rs.getString("company_number"),
                                rs.getObject("latest_check_id", UUID.class)),
                        utc(now))
                .stream()
                .findFirst();
    }

    public void completeJob(long jobId) {
        jdbc.update("DELETE FROM monitoring_job WHERE id = ?", jobId);
    }

    public void retryJob(long jobId, Instant nextAttemptAt, String error) {
        jdbc.update(
                "UPDATE monitoring_job SET attempts = attempts + 1, next_attempt_at = ?, last_error = ? WHERE id = ?",
                utc(nextAttemptAt),
                error,
                jobId);
    }

    // ---------- alerts and the outbox ----------

    public void insertAlert(Alert a) {
        jdbc.update(
                """
                INSERT INTO monitoring_alert (id, subscription_id, company_number, check_id, previous_check_id, type,
                    trigger, decision_before, decision_after, matches_added, matches_removed, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                """,
                a.alertId(),
                a.subscriptionId(),
                a.companyNumber(),
                a.checkId(),
                a.previousCheckId(),
                a.type(),
                a.trigger(),
                a.decisionBefore(),
                a.decisionAfter(),
                a.matchesAdded().toString(),
                a.matchesRemoved().toString(),
                utc(a.occurredAt()));
    }

    public void insertOutbox(UUID alertId, String body, Instant at) {
        jdbc.update(
                "INSERT INTO webhook_outbox (alert_id, body, status, next_attempt_at) VALUES (?, ?, 'PENDING', ?)",
                alertId,
                body,
                utc(at));
    }

    /** @param companyNumber narrows the list, or null for every company */
    public List<Alert> alerts(String companyNumber) {
        String where = companyNumber == null ? "" : " WHERE company_number = ?";
        Object[] args = companyNumber == null ? new Object[0] : new Object[] {companyNumber};
        return jdbc.query(
                """
                SELECT id, subscription_id, company_number, check_id, previous_check_id, type, trigger, decision_before,
                       decision_after, matches_added::text AS added, matches_removed::text AS removed, occurred_at
                  FROM monitoring_alert
                """
                        + where + " ORDER BY occurred_at DESC, id",
                (rs, i) -> alert(rs),
                args);
    }

    private Alert alert(ResultSet rs) throws SQLException {
        try {
            return new Alert(
                    rs.getObject("id", UUID.class),
                    rs.getObject("subscription_id", UUID.class),
                    rs.getString("company_number"),
                    rs.getObject("check_id", UUID.class),
                    rs.getObject("previous_check_id", UUID.class),
                    rs.getString("type"),
                    rs.getString("trigger"),
                    rs.getString("decision_before"),
                    rs.getString("decision_after"),
                    json.readTree(rs.getString("added")),
                    json.readTree(rs.getString("removed")),
                    rs.getObject("occurred_at", OffsetDateTime.class).toInstant());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored alert is not valid JSON", e);
        }
    }

    private static Subscription subscription(ResultSet rs) throws SQLException {
        return new Subscription(
                rs.getObject("id", UUID.class),
                rs.getString("company_number"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("latest_check_id", UUID.class),
                rs.getObject("next_recheck_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
