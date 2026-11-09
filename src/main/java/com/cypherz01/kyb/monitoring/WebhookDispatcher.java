package com.cypherz01.kyb.monitoring;

import com.cypherz01.kyb.config.BackoffPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers the outbox at least once, signed, with backoff (ADR-0006). A row stays locked while it is
 * sent, so another worker skips it rather than sending it twice.
 */
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private static final String HMAC = "HmacSHA256";

    private record Delivery(UUID alertId, String body, int attempts) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MonitoringProperties.Webhook props;
    private final BackoffPolicy backoff;
    private final Clock clock;
    private final MeterRegistry meters;
    private final HttpClient http;

    public WebhookDispatcher(
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            MonitoringProperties.Webhook props,
            BackoffPolicy backoff,
            Clock clock,
            MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.props = props;
        this.backoff = backoff;
        this.clock = clock;
        this.meters = meters;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.timeout())
                .build();
    }

    /** Sends every webhook that is due. */
    public void deliverDue() {
        if (!props.configured()) {
            return;
        }
        while (Boolean.TRUE.equals(tx.execute(status -> deliverOne()))) {
            // until nothing is due
        }
    }

    private boolean deliverOne() {
        Instant now = clock.instant();
        List<Delivery> claimed = jdbc.query(
                """
                SELECT alert_id, body, attempts FROM webhook_outbox
                 WHERE status = 'PENDING' AND next_attempt_at <= ?
                 ORDER BY next_attempt_at, alert_id
                 LIMIT 1
                   FOR UPDATE SKIP LOCKED
                """,
                (rs, i) ->
                        new Delivery(rs.getObject("alert_id", UUID.class), rs.getString("body"), rs.getInt("attempts")),
                utc(now));
        if (claimed.isEmpty()) {
            return false;
        }
        Delivery delivery = claimed.get(0);
        Optional<String> failure = send(delivery, now);
        int attempts = delivery.attempts() + 1;
        if (failure.isEmpty()) {
            jdbc.update(
                    "UPDATE webhook_outbox SET status = 'DELIVERED', attempts = ?, delivered_at = ?, last_error = NULL"
                            + " WHERE alert_id = ?",
                    attempts,
                    utc(now),
                    delivery.alertId());
            count("delivered");
        } else if (attempts >= props.maxAttempts()) {
            jdbc.update(
                    "UPDATE webhook_outbox SET status = 'FAILED', attempts = ?, last_error = ? WHERE alert_id = ?",
                    attempts,
                    failure.get(),
                    delivery.alertId());
            count("failed");
            log.error(
                    "webhook alert={} failed for good after {} attempts: {}",
                    delivery.alertId(),
                    attempts,
                    failure.get());
        } else {
            jdbc.update(
                    "UPDATE webhook_outbox SET attempts = ?, next_attempt_at = ?, last_error = ? WHERE alert_id = ?",
                    attempts,
                    utc(now.plus(backoff.delayFor(attempts, Optional.empty()))),
                    failure.get(),
                    delivery.alertId());
            count("retried");
        }
        return true;
    }

    /** @return why it failed, or empty if the receiver took it */
    private Optional<String> send(Delivery delivery, Instant now) {
        long start = System.nanoTime();
        String timestamp = Long.toString(now.getEpochSecond());
        HttpRequest request = HttpRequest.newBuilder(URI.create(props.url()))
                .timeout(props.timeout())
                .header("Content-Type", "application/json")
                .header("X-KYB-Event-Id", delivery.alertId().toString())
                .header("X-KYB-Timestamp", timestamp)
                .header("X-KYB-Signature", "sha256=" + sign(timestamp + "." + delivery.body()))
                .POST(HttpRequest.BodyPublishers.ofString(delivery.body(), StandardCharsets.UTF_8))
                .build();
        Optional<String> failure;
        String status;
        try {
            int code =
                    http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            status = Integer.toString(code);
            failure = code / 100 == 2 ? Optional.empty() : Optional.of("Receiver answered " + code);
        } catch (IOException e) {
            status = "error";
            failure = Optional.of(e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = "interrupted";
            failure = Optional.of("Interrupted while sending");
        }
        log.info(
                "external call source=webhook operation=deliver status={} latencyMs={}",
                status,
                (System.nanoTime() - start) / 1_000_000);
        return failure;
    }

    private String sign(String text) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(props.secret().getBytes(StandardCharsets.UTF_8), HMAC));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(HMAC + " unavailable", e);
        }
    }

    private void count(String outcome) {
        meters.counter("kyb.monitoring.webhooks", "outcome", outcome).increment();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
