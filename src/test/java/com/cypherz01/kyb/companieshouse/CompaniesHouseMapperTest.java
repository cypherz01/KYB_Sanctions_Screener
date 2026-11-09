package com.cypherz01.kyb.companieshouse;

import static org.assertj.core.api.Assertions.assertThat;

import com.cypherz01.kyb.companieshouse.model.CompanyProfile;
import com.cypherz01.kyb.companieshouse.model.Officer;
import com.cypherz01.kyb.companieshouse.model.Psc;
import com.cypherz01.kyb.companieshouse.model.PscKind;
import com.cypherz01.kyb.support.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CompaniesHouseMapperTest {

    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final CompaniesHouseMapper mapper = new CompaniesHouseMapper(json);

    @Test
    void mapsProfile() throws Exception {
        CompanyProfile p = mapper.toProfile(json.readTree(Fixtures.read("companieshouse/profile.json")));

        assertThat(p.companyNumber()).isEqualTo("12345678");
        assertThat(p.name()).isEqualTo("EXAMPLE TRADING LTD");
        assertThat(p.status()).isEqualTo("active");
        assertThat(p.type()).isEqualTo("ltd");
        assertThat(p.incorporatedOn()).isEqualTo(LocalDate.of(2015, 2, 17));
        assertThat(p.registeredOffice().postalCode()).isEqualTo("EC1A 1AA");
        assertThat(p.sicCodes()).containsExactly("64921", "62020");
        assertThat(p.accountsOverdue()).isFalse();
        assertThat(p.confirmationStatementOverdue()).isTrue();
        assertThat(p.hasInsolvencyHistory()).isFalse();
    }

    @Test
    void missingOptionalProfileBlocksDefaultSafely() throws Exception {
        // A freshly incorporated company may have no accounts/confirmation-statement blocks yet.
        JsonNode minimal = json.readTree(
                """
                { "company_number": "12345678", "company_name": "NEW CO LTD", "company_status": "active" }
                """);

        CompanyProfile p = mapper.toProfile(minimal);

        assertThat(p.accountsOverdue()).isFalse();
        assertThat(p.confirmationStatementOverdue()).isFalse();
        assertThat(p.hasInsolvencyHistory()).isFalse();
        assertThat(p.sicCodes()).isEmpty();
        assertThat(p.registeredOffice()).isNull();
    }

    @Test
    void mapsOfficersIncludingMonthYearBirthDatesAndCorporateOfficers() throws Exception {
        List<Officer> officers = mapper.toOfficers(merged("officers-page1.json", "officers-page2.json"));

        assertThat(officers).hasSize(3);

        Officer petrov = officers.get(0);
        assertThat(petrov.name()).isEqualTo("PETROV, Ivan");
        assertThat(petrov.role()).isEqualTo("director");
        assertThat(petrov.corporate()).isFalse();
        assertThat(petrov.dateOfBirth()).isEqualTo(YearMonth.of(1971, 3));
        assertThat(petrov.nationality()).isEqualTo("Russian");
        assertThat(petrov.isCurrent()).isTrue();

        Officer smith = officers.get(1);
        assertThat(smith.resignedOn()).isEqualTo(LocalDate.of(2023, 5, 1));
        assertThat(smith.isCurrent()).isFalse();
        assertThat(smith.dateOfBirth()).isNull();

        Officer nominee = officers.get(2);
        assertThat(nominee.corporate()).isTrue();
    }

    @Test
    void mapsPscsIncludingCorporateRegistrationCountryAndSuperSecure() throws Exception {
        List<Psc> pscs = mapper.toPscs(merged("pscs-page1.json", "pscs-page2.json"));

        assertThat(pscs)
                .extracting(Psc::kind)
                .containsExactly(PscKind.INDIVIDUAL, PscKind.CORPORATE, PscKind.SUPER_SECURE);

        Psc individual = pscs.get(0);
        assertThat(individual.name()).isEqualTo("Mr Ivan Petrov");
        assertThat(individual.dateOfBirth()).isEqualTo(YearMonth.of(1971, 3));
        assertThat(individual.registrationCountry()).isNull();

        Psc corporate = pscs.get(1);
        assertThat(corporate.registrationCountry()).isEqualTo("Panama");
        assertThat(corporate.naturesOfControl()).contains("voting-rights-50-to-75-percent");
        assertThat(corporate.isCurrent()).isTrue();

        Psc superSecure = pscs.get(2);
        assertThat(superSecure.name()).isNull();
    }

    /** Look-through starts here: v1 parsed the number and then dropped it. */
    @Test
    void keepsACorporatePscsRegistrationNumberAsPublished() throws Exception {
        List<Psc> pscs = mapper.toPscs(merged("pscs-page1.json", "pscs-page2.json"));

        assertThat(pscs.get(1).registrationNumber()).isEqualTo("155555555");
        assertThat(pscs.get(0).registrationNumber()).isNull();
    }

    @Test
    void emptyListsMapToEmpty() throws Exception {
        JsonNode empty = json.readTree("{\"items\": []}");

        assertThat(mapper.toOfficers(empty)).isEmpty();
        assertThat(mapper.toPscs(empty)).isEmpty();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "individual-person-with-significant-control, INDIVIDUAL",
        "individual-beneficial-owner, INDIVIDUAL",
        "corporate-entity-person-with-significant-control, CORPORATE",
        "corporate-entity-beneficial-owner, CORPORATE",
        "legal-person-with-significant-control, LEGAL_PERSON",
        "legal-person-beneficial-owner, LEGAL_PERSON",
        "super-secure-person-with-significant-control, SUPER_SECURE",
        "super-secure-beneficial-owner, SUPER_SECURE",
        "something-new-from-companies-house, UNKNOWN",
    })
    void mapsAllDocumentedPscKinds(String kind, PscKind expected) {
        assertThat(CompaniesHouseMapper.kindOf(kind)).isEqualTo(expected);
    }

    @Test
    void nullPscKindIsUnknown() {
        assertThat(CompaniesHouseMapper.kindOf(null)).isEqualTo(PscKind.UNKNOWN);
    }

    /** Builds the merged multi-page shape that CompaniesHouseApi produces. */
    private JsonNode merged(String... pages) throws Exception {
        ObjectNode result = (ObjectNode) json.readTree(Fixtures.read("companieshouse/" + pages[0]));
        ArrayNode items = (ArrayNode) result.get("items");
        for (int i = 1; i < pages.length; i++) {
            items.addAll((ArrayNode)
                    json.readTree(Fixtures.read("companieshouse/" + pages[i])).get("items"));
        }
        return result;
    }
}
