package com.cypherz01.kyb;

import static org.assertj.core.api.Assertions.assertThat;

import com.cypherz01.kyb.support.PostgresTestcontainer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M0 acceptance: the app boots against a real Postgres, reports healthy, exposes metrics, and the
 * Flyway baseline has installed the extensions the matcher relies on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainer.class)
class SkeletonIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void healthEndpointReportsUp() {
        ResponseEntity<String> response = http.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void healthIncludesDatabase() {
        ResponseEntity<String> response = http.getForEntity("/actuator/health", String.class);

        // The DB indicator must be part of health, so a dead Postgres makes the app unhealthy.
        assertThat(response.getBody()).contains("\"db\"");
    }

    @Test
    void prometheusEndpointIsExposed() {
        ResponseEntity<String> response = http.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("jvm_memory_used_bytes");
    }

    @Test
    void flywayBaselineInstallsMatchingExtensions() {
        List<String> extensions = jdbc.queryForList("SELECT extname FROM pg_extension", String.class);

        assertThat(extensions).contains("pg_trgm", "unaccent");
    }

    @Test
    void flywayHistoryRecordsBaselineMigration() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success AND version = '1'", Integer.class);

        assertThat(applied).isEqualTo(1);
    }
}
