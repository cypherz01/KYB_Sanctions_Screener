package com.cypherz01.kyb.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.cypherz01.kyb.sanctions.ImportOutcome;
import com.cypherz01.kyb.sanctions.SanctionsImportService;
import com.cypherz01.kyb.support.PostgresTestcontainer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * M3 acceptance on REAL data: a typical single-name screen takes under 100 ms on the full
 * OpenSanctions dataset, and well-known sanctioned names are found. Downloads about 380 MB, so it
 * runs only on request:
 *
 * <pre>OPENSANCTIONS_SMOKE=true ./mvnw -Psmoke test -Dtest=ScreeningPerformanceSmokeTest</pre>
 */
@Tag("smoke")
@EnabledIfEnvironmentVariable(named = "OPENSANCTIONS_SMOKE", matches = "true")
@SpringBootTest(properties = "kyb.sanctions.cron=-")
@Import(PostgresTestcontainer.class)
class ScreeningPerformanceSmokeTest {

    /** A mix of listed names (exact and variant spellings) and ordinary names. */
    static final List<String> NAMES = List.of(
            "Vladimir Putin",
            "Vladimir Vladimirovich Putin",
            "Владимир Путин",
            "Sergei Lavrov",
            "Roman Abramovich",
            "Alisher Usmanov",
            "Oleg Deripaska",
            "Gennady Timchenko",
            "Viktor Bout",
            "Kim Jong Un",
            "Bashar al-Assad",
            "Ramzan Kadyrov",
            "Yevgeny Prigozhin",
            "Arkady Rotenberg",
            "Mohammed Ali",
            "John Smith",
            "Jane Doe",
            "Oliver Thompson",
            "Priya Patel",
            "Wei Zhang",
            "Maria Garcia",
            "James O'Brien",
            "Rosneft",
            "Sberbank",
            "Bank Rossiya",
            "Wagner Group",
            "Tesco PLC",
            "Acme Trading Ltd",
            "Global Shipping Limited",
            "Sovcomflot");

    @Autowired
    SanctionsImportService imports;

    @Autowired
    ScreeningService screening;

    @Test
    void screensASingleNameInUnder100msOnTheFullDataset() {
        assertThat(imports.importLatest().outcome()).isIn(ImportOutcome.COMPLETED, ImportOutcome.UNCHANGED);

        for (String name : NAMES) { // warm-up: JIT, connection pool, Postgres buffer cache
            screening.screen(new ScreeningRequest(name, SubjectType.ANY, null, null));
        }

        List<Long> micros = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            for (String name : NAMES) {
                long start = System.nanoTime();
                ScreeningResult r = screening.screen(new ScreeningRequest(name, SubjectType.ANY, null, null));
                micros.add((System.nanoTime() - start) / 1_000);
                if (round == 0) {
                    System.out.printf(
                            "%-30s candidates=%-3d matches=%-2d top=%s%n",
                            name,
                            r.candidatesConsidered(),
                            r.matches().size(),
                            r.matches().isEmpty()
                                    ? "-"
                                    : r.matches().get(0).matchedName() + " "
                                            + r.matches().get(0).band() + " "
                                            + r.matches().get(0).similarity());
                }
            }
        }

        long[] sorted = micros.stream().mapToLong(Long::longValue).sorted().toArray();
        long p50 = sorted[sorted.length / 2];
        long p95 = sorted[(int) (sorted.length * 0.95)];
        System.out.printf(
                "Screening latency over %d calls: p50=%.1fms p95=%.1fms max=%.1fms%n",
                sorted.length, p50 / 1000.0, p95 / 1000.0, sorted[sorted.length - 1] / 1000.0);

        assertThat(p50).as("median single-name screen, microseconds").isLessThan(100_000);
        assertThat(screening
                        .screen(new ScreeningRequest("Vladimir Putin", SubjectType.PERSON, null, null))
                        .matches())
                .anyMatch(m -> m.band() == MatchBand.STRONG);
        assertThat(Arrays.stream(sorted).count()).isEqualTo(NAMES.size() * 3L);
    }
}
