package com.cypherz01.kyb.companieshouse;

import static org.assertj.core.api.Assertions.assertThat;

import com.cypherz01.kyb.support.PostgresTestcontainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Calls the REAL Companies House API. Excluded from normal builds and CI (spec §14). Run manually:
 *
 * <pre>COMPANIES_HOUSE_API_KEY=... ./mvnw -Psmoke test</pre>
 */
@Tag("smoke")
@EnabledIfEnvironmentVariable(named = "COMPANIES_HOUSE_API_KEY", matches = ".+")
@SpringBootTest
@Import(PostgresTestcontainer.class)
class CompaniesHouseSmokeTest {

    /** Tesco PLC: long-lived, active, has officers and PSC data. */
    static final String REAL_COMPANY = "00445790";

    @Autowired
    CompaniesHouseClient client;

    @Test
    void fetchesARealCompany() {
        var profile = client.getProfile(REAL_COMPANY);
        var officers = client.getOfficers(REAL_COMPANY);
        var pscs = client.getPscs(REAL_COMPANY);

        assertThat(profile.value().name()).containsIgnoringCase("TESCO");
        assertThat(officers.value()).anyMatch(o -> o.isCurrent());
        System.out.printf(
                "Smoke OK: %s (%s), %d officers, %d PSCs%n",
                profile.value().name(),
                profile.value().status(),
                officers.value().size(),
                pscs.value().size());
    }
}
