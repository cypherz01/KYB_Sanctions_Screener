package com.cypherz01.kyb.check;

import static org.assertj.core.api.Assertions.assertThat;

import com.cypherz01.kyb.companieshouse.model.CompanyProfile;
import com.cypherz01.kyb.companieshouse.model.Officer;
import com.cypherz01.kyb.companieshouse.model.Psc;
import com.cypherz01.kyb.companieshouse.model.PscKind;
import com.cypherz01.kyb.matching.PartialDate;
import com.cypherz01.kyb.matching.SubjectType;
import com.cypherz01.kyb.support.MatchingDefaults;
import com.cypherz01.kyb.support.ShippedConfig;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Who is screened (spec §9), with the shipped nationality mapping. */
class SubjectExtractorTest {

    static final CompanyProfile PROFILE = new CompanyProfile(
            "01234567",
            "ACME WIDGETS LTD",
            "active",
            "ltd",
            LocalDate.of(2015, 3, 1),
            null,
            List.of("46900"),
            false,
            false,
            false);

    final SubjectExtractor extractor = new SubjectExtractor(ShippedConfig.checks(), MatchingDefaults.normaliser());

    @Test
    void theCompanyItselfIsScreenedFirstAsAnOrganisation() {
        Subject company = extract(List.of(), List.of()).subjects().get(0);

        assertThat(company.roles()).containsExactly(SubjectRole.COMPANY);
        assertThat(company.publishedName()).isEqualTo("ACME WIDGETS LTD");
        assertThat(company.screenedName()).isEqualTo("ACME WIDGETS LTD");
        assertThat(company.type()).isEqualTo(SubjectType.ORGANISATION);
        assertThat(company.birthDate()).isNull();
    }

    @Test
    void anIndividualDirectorIsScreenedInNaturalOrderWithBirthMonthAndNationality() {
        Subject director = only(
                extract(
                        List.of(officer("PETROV, Ivan", "director", false, null, "Russian", YearMonth.of(1971, 3))),
                        List.of()),
                "PETROV, Ivan");

        assertThat(director.roles()).containsExactly(SubjectRole.DIRECTOR);
        assertThat(director.screenedName()).isEqualTo("Ivan PETROV");
        assertThat(director.type()).isEqualTo(SubjectType.PERSON);
        assertThat(director.birthDate()).isEqualTo(new PartialDate(1971, 3, null));
        assertThat(director.countries()).containsExactly("ru");
    }

    @ParameterizedTest
    @CsvSource({
        "director,false,DIRECTOR",
        "nominee-director,false,DIRECTOR",
        "secretary,false,SECRETARY",
        "llp-designated-member,false,OFFICER",
        "corporate-director,true,CORPORATE_OFFICER",
        "corporate-secretary,true,CORPORATE_OFFICER"
    })
    void officerRolesAreClassified(String chRole, boolean corporate, SubjectRole expected) {
        Subject s = extract(List.of(officer("SMITH, John", chRole, corporate, null, "British", null)), List.of())
                .subjects()
                .get(1);

        assertThat(s.roles()).containsExactly(expected);
    }

    @Test
    void corporateOfficersAreScreenedAsOrganisationsWithTheirNameUnchanged() {
        Subject s = only(
                extract(
                        List.of(officer("SMITH, JONES & CO LIMITED", "corporate-director", true, null, null, null)),
                        List.of()),
                "SMITH, JONES & CO LIMITED");

        assertThat(s.type()).isEqualTo(SubjectType.ORGANISATION);
        assertThat(s.screenedName()).isEqualTo("SMITH, JONES & CO LIMITED");
        assertThat(s.countries()).isEmpty();
    }

    @Test
    void resignedOfficersAreNotScreenedByDefault() {
        SubjectExtraction e = extract(
                List.of(officer("GONE, Former", "director", false, LocalDate.of(2020, 1, 1), "British", null)),
                List.of());

        assertThat(e.subjects()).extracting(Subject::publishedName).doesNotContain("GONE, Former");
    }

    @Test
    void resignedOfficersCanBeIncluded() {
        ChecksProperties shipped = ShippedConfig.checks();
        SubjectExtractor including = new SubjectExtractor(
                new ChecksProperties(shipped.engineVersion(), true, shipped.nationalities()),
                MatchingDefaults.normaliser());

        SubjectExtraction e = including.extract(
                PROFILE,
                List.of(officer("GONE, Former", "director", false, LocalDate.of(2020, 1, 1), "British", null)),
                List.of());

        assertThat(e.subjects()).extracting(Subject::publishedName).contains("GONE, Former");
    }

    @Test
    void currentIndividualPscsArePeopleAndCorporateOnesOrganisations() {
        SubjectExtraction e = extract(
                List.of(),
                List.of(
                        psc("Mrs Sarah Jones", PscKind.INDIVIDUAL, "British", YearMonth.of(1980, 5), null),
                        psc("OFFSHORE HOLDINGS S.A.", PscKind.CORPORATE, null, null, null),
                        psc("THE CROWN ESTATE", PscKind.LEGAL_PERSON, null, null, null)));

        Subject jones = only(e, "Mrs Sarah Jones");
        assertThat(jones.roles()).containsExactly(SubjectRole.PSC);
        assertThat(jones.type()).isEqualTo(SubjectType.PERSON);
        assertThat(jones.screenedName()).isEqualTo("Mrs Sarah Jones");
        assertThat(jones.birthDate()).isEqualTo(new PartialDate(1980, 5, null));
        assertThat(jones.countries()).containsExactly("gb");
        assertThat(only(e, "OFFSHORE HOLDINGS S.A.").roles()).containsExactly(SubjectRole.CORPORATE_PSC);
        assertThat(only(e, "OFFSHORE HOLDINGS S.A.").type()).isEqualTo(SubjectType.ORGANISATION);
        assertThat(only(e, "THE CROWN ESTATE").type()).isEqualTo(SubjectType.ORGANISATION);
    }

    @Test
    void ceasedPscsAreNotScreened() {
        SubjectExtraction e = extract(
                List.of(), List.of(psc("Mr Old Owner", PscKind.INDIVIDUAL, "British", null, LocalDate.of(2021, 1, 1))));

        assertThat(e.subjects()).hasSize(1); // the company only
    }

    @Test
    void superSecurePscsCannotBeScreenedAndAreNoted() {
        SubjectExtraction e = extract(List.of(), List.of(psc(null, PscKind.SUPER_SECURE, null, null, null)));

        assertThat(e.subjects()).hasSize(1);
        assertThat(e.notes()).anyMatch(n -> n.toLowerCase().contains("super-secure"));
    }

    @Test
    void aDirectorWhoIsAlsoAPscIsScreenedOnceWithBothRoles() {
        SubjectExtraction e = extract(
                List.of(officer("PETROV, Ivan", "director", false, null, "Russian", YearMonth.of(1971, 3))),
                List.of(psc("Mr Ivan Petrov", PscKind.INDIVIDUAL, "Russian", YearMonth.of(1971, 3), null)));

        assertThat(e.subjects()).hasSize(2);
        Subject petrov = e.subjects().get(1);
        assertThat(petrov.roles()).containsExactly(SubjectRole.DIRECTOR, SubjectRole.PSC);
        assertThat(petrov.publishedName()).isEqualTo("PETROV, Ivan");
    }

    @Test
    void samePeopleNamesWithDifferentBirthMonthsAreDifferentSubjects() {
        SubjectExtraction e = extract(
                List.of(officer("SMITH, John", "director", false, null, "British", YearMonth.of(1960, 1))),
                List.of(psc("Mr John Smith", PscKind.INDIVIDUAL, "British", YearMonth.of(1990, 7), null)));

        assertThat(e.subjects()).hasSize(3);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            nullValues = "NULL",
            value = {"British|gb", "BRITISH|gb", "Northern Irish|gb", "British,Irish|gb;ie", "Martian|", "NULL|"})
    void nationalitiesBecomeIsoCodesAndUnknownOnesAreLeftOut(String nationality, String expected) {
        Subject s = extract(List.of(officer("SMITH, John", "director", false, null, nationality, null)), List.of())
                .subjects()
                .get(1);

        assertThat(s.countries())
                .isEqualTo(expected == null || expected.isEmpty() ? Set.of() : Set.of(expected.split(";")));
    }

    @Test
    void anOfficerWithoutABirthDateHasNone() {
        assertThat(extract(List.of(officer("SMITH, John", "director", false, null, "British", null)), List.of())
                        .subjects()
                        .get(1)
                        .birthDate())
                .isNull();
    }

    // ---------- helpers ----------

    private SubjectExtraction extract(List<Officer> officers, List<Psc> pscs) {
        return extractor.extract(PROFILE, officers, pscs);
    }

    private static Subject only(SubjectExtraction e, String publishedName) {
        List<Subject> s = e.subjects().stream()
                .filter(x -> publishedName.equals(x.publishedName()))
                .toList();
        assertThat(s).as(publishedName).hasSize(1);
        return s.get(0);
    }

    static Officer officer(
            String name, String role, boolean corporate, LocalDate resigned, String nationality, YearMonth dob) {
        return new Officer(
                name, role, corporate, LocalDate.of(2015, 3, 1), resigned, nationality, "United Kingdom", dob);
    }

    static Psc psc(String name, PscKind kind, String nationality, YearMonth dob, LocalDate ceased) {
        return new Psc(
                name,
                kind,
                nationality,
                null,
                dob,
                LocalDate.of(2016, 6, 30),
                ceased,
                kind == PscKind.CORPORATE ? "Panama" : null,
                List.of());
    }
}
