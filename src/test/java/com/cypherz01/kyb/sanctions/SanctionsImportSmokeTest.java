package com.cypherz01.kyb.sanctions;

import static org.assertj.core.api.Assertions.assertThat;

import com.cypherz01.kyb.support.PostgresTestcontainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Downloads and imports the REAL OpenSanctions sanctions collection (about 380 MB). M2 acceptance:
 * the full import completes and repeating it is a no-op. Excluded from normal builds and CI (spec
 * §14), and gated separately from the Companies House smoke test because of the download size:
 *
 * <pre>OPENSANCTIONS_SMOKE=true ./mvnw -Psmoke test</pre>
 *
 * <p>Upstream re-exports several times a day. If a new export lands between the two runs, the
 * second import is legitimately COMPLETED, not UNCHANGED. Run it again.
 */
@Tag("smoke")
@EnabledIfEnvironmentVariable(named = "OPENSANCTIONS_SMOKE", matches = "true")
@SpringBootTest(properties = "kyb.sanctions.cron=-")
@Import(PostgresTestcontainer.class)
class SanctionsImportSmokeTest {

    @Autowired
    SanctionsImportService service;

    @Test
    void importsTheFullDatasetIdempotently() {
        long start = System.nanoTime();
        ImportResult first = service.importLatest();
        long seconds = (System.nanoTime() - start) / 1_000_000_000L;

        assertThat(first.outcome()).as(first.message()).isEqualTo(ImportOutcome.COMPLETED);
        assertThat(first.entityCount()).isGreaterThan(10_000);
        assertThat(first.nameCount()).isGreaterThan(first.entityCount());

        ImportResult second = service.importLatest();
        assertThat(second.outcome()).isEqualTo(ImportOutcome.UNCHANGED);

        SanctionsStatus status = service.status();
        System.out.printf(
                "Smoke OK: version %s, %d entities, %d names, %d malformed lines, %ds, by schema %s%n",
                first.versionLabel(),
                first.entityCount(),
                first.nameCount(),
                first.malformedLines(),
                seconds,
                status.activeEntitiesBySchema());
    }
}
