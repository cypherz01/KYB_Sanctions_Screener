package com.cypherz01.kyb.config;

import com.cypherz01.kyb.check.CheckAuditService;
import com.cypherz01.kyb.check.CheckRepository;
import com.cypherz01.kyb.check.CheckService;
import com.cypherz01.kyb.check.ChecksProperties;
import com.cypherz01.kyb.matching.MatchingProperties;
import com.cypherz01.kyb.matching.NameMatcher;
import com.cypherz01.kyb.matching.NameNormaliser;
import com.cypherz01.kyb.monitoring.DatasetDeltas;
import com.cypherz01.kyb.monitoring.MonitoringCheckListener;
import com.cypherz01.kyb.monitoring.MonitoringProperties;
import com.cypherz01.kyb.monitoring.MonitoringRepository;
import com.cypherz01.kyb.monitoring.MonitoringScheduler;
import com.cypherz01.kyb.monitoring.MonitoringService;
import com.cypherz01.kyb.monitoring.MonitoringWorker;
import com.cypherz01.kyb.monitoring.ReverseScreener;
import com.cypherz01.kyb.monitoring.WebhookDispatcher;
import com.cypherz01.kyb.sanctions.SanctionsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MonitoringProperties.class)
class MonitoringConfig {

    @Bean
    MonitoringRepository monitoringRepository(JdbcTemplate jdbc, NameNormaliser normaliser, ObjectMapper json) {
        return new MonitoringRepository(jdbc, normaliser, json);
    }

    @Bean
    DatasetDeltas datasetDeltas(JdbcTemplate jdbc) {
        return new DatasetDeltas(jdbc);
    }

    @Bean
    ReverseScreener reverseScreener(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            NameNormaliser normaliser,
            NameMatcher matcher,
            MatchingProperties matching) {
        return new ReverseScreener(
                jdbc, new TransactionTemplate(transactions), normaliser, matcher, matching.retrieval());
    }

    @Bean
    MonitoringCheckListener monitoringCheckListener(
            MonitoringRepository repository,
            CheckRepository checks,
            MonitoringProperties properties,
            ObjectMapper json,
            Clock clock) {
        return new MonitoringCheckListener(repository, checks, properties, json, clock);
    }

    @Bean
    MonitoringService monitoringService(
            MonitoringRepository repository,
            CheckService checkService,
            CheckAuditService audit,
            CheckRepository checks,
            SanctionsRepository datasets,
            MonitoringProperties properties,
            PlatformTransactionManager transactions,
            ObjectMapper json,
            Clock clock) {
        return new MonitoringService(
                repository,
                checkService,
                audit,
                checks,
                datasets,
                properties,
                new TransactionTemplate(transactions),
                json,
                clock);
    }

    @Bean
    WebhookDispatcher webhookDispatcher(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            MonitoringProperties properties,
            Clock clock,
            MeterRegistry meters) {
        MonitoringProperties.Webhook webhook = properties.webhook();
        return new WebhookDispatcher(
                jdbc,
                new TransactionTemplate(transactions),
                webhook,
                new BackoffPolicy(webhook.retryBaseDelay(), webhook.retryMaxDelay(), () -> ThreadLocalRandom.current()
                        .nextDouble()),
                clock,
                meters);
    }

    @Bean
    MonitoringWorker monitoringWorker(
            MonitoringRepository repository,
            SanctionsRepository datasets,
            DatasetDeltas deltas,
            ReverseScreener reverse,
            CheckService checks,
            WebhookDispatcher webhooks,
            MonitoringProperties properties,
            ChecksProperties checksProperties,
            MatchingProperties matching,
            PlatformTransactionManager transactions,
            Clock clock) {
        return new MonitoringWorker(
                repository,
                datasets,
                deltas,
                reverse,
                checks,
                webhooks,
                properties,
                fingerprint(checksProperties.engineVersion(), matching),
                new TransactionTemplate(transactions),
                clock);
    }

    @Bean
    MonitoringScheduler monitoringScheduler(MonitoringWorker worker) {
        return new MonitoringScheduler(worker);
    }

    /** The engine version does not capture kyb.matching.*, and either can change a screening result. */
    private static String fingerprint(String engineVersion, MatchingProperties matching) {
        String text = engineVersion + "|" + NameNormaliser.VERSION + "|" + matching;
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
