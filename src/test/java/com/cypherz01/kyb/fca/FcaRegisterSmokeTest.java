package com.cypherz01.kyb.fca;

import static org.assertj.core.api.Assertions.assertThat;

import com.cypherz01.kyb.fca.model.FcaAssessment;
import com.cypherz01.kyb.fca.model.FcaAvailability;
import com.cypherz01.kyb.fca.model.FcaMatchBasis;
import com.cypherz01.kyb.support.PostgresTestcontainer;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Calls the REAL FCA Register. Verifies the API shape this client was built from (third-party
 * docs; the official ones are JavaScript-only). Excluded from normal builds and CI. Run manually:
 *
 * <pre>FCA_AUTH_EMAIL=... FCA_AUTH_KEY=... ./mvnw -Psmoke test -Dtest=FcaRegisterSmokeTest</pre>
 */
@Tag("smoke")
@EnabledIfEnvironmentVariable(named = "FCA_AUTH_KEY", matches = ".+")
@SpringBootTest(properties = "kyb.sanctions.cron=-")
@Import(PostgresTestcontainer.class)
class FcaRegisterSmokeTest {

    @Autowired
    FcaRegisterClient client;

    @Autowired
    FcaRegisterApi api;

    @Test
    void matchesARealAuthorisedBankByItsCompaniesHouseNumber() {
        // Barclays Bank UK PLC: FCA-authorised, Companies House 09740322, registered office E14 5HP
        FcaAssessment a = client.assess("BARCLAYS BANK UK PLC", "09740322", "E14 5HP");
        System.out.println(
                "Smoke: " + a.availability() + " match=" + a.authorisedMatch() + " evidence=" + a.evidence());

        assertThat(a.availability()).as(a.unavailableReason()).isEqualTo(FcaAvailability.AVAILABLE);
        assertThat(a.authorisedMatch()).isNotNull();
        assertThat(a.authorisedMatch().basis()).isEqualTo(FcaMatchBasis.COMPANIES_HOUSE_NUMBER);
    }

    @Test
    void printsTheShapeOfRealResponses() {
        JsonNode search = api.searchFirms("Barclays");
        System.out.println("Search envelope keys: " + keys(search) + " status=" + search.path("Status") + " message="
                + search.path("Message") + " resultInfo=" + search.path("ResultInfo"));
        System.out.println("First hit: " + search.path("Data").path(0));

        // How the Register represents clone/unauthorised entries (spec FCA_CLONE_OR_UNAUTHORISED_MATCH)
        JsonNode clones = api.searchFirms("clone");
        for (JsonNode hit : clones.path("Data")) {
            System.out.println("clone search hit: status=" + hit.path("Status") + " name=" + hit.path("Name"));
        }
        assertThat(search.path("Data").isArray()).isTrue();
    }

    private static String keys(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            sb.append(it.next()).append(it.hasNext() ? "," : "");
        }
        return sb.toString();
    }
}
