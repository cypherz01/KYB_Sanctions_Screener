package com.cypherz01.kyb.sanctions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cypherz01.kyb.sanctions.model.EntityName;
import com.cypherz01.kyb.sanctions.model.NameKind;
import com.cypherz01.kyb.sanctions.model.SanctionsEntityRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Extraction and filtering rules for one FtM entity line (spec §5.3). */
class FtmEntityParserTest {

    static final ObjectMapper JSON = new ObjectMapper();

    final FtmEntityParser parser = new FtmEntityParser(
            Set.of("Person", "Company", "Organization", "LegalEntity"), Set.of("sanction", "sanction.linked"));

    // ---------- names ----------

    @Test
    void collectsPrimaryNamesThenAliasesThenPreviousNames() {
        SanctionsEntityRecord r = parse(
                """
                {"id":"x","schema":"Person","caption":"Ivan Petrov","properties":{
                  "previousName":["Ivan Sidorov"],"alias":["Ivan Petroff"],"name":["Ivan Petrov","Иван Петров"],
                  "topics":["sanction"]}}
                """);

        assertThat(r.names())
                .containsExactly(
                        new EntityName("Ivan Petrov", NameKind.PRIMARY),
                        new EntityName("Иван Петров", NameKind.PRIMARY),
                        new EntityName("Ivan Petroff", NameKind.ALIAS),
                        new EntityName("Ivan Sidorov", NameKind.PREVIOUS));
    }

    @Test
    void excludesWeakAliasesBecauseFtmMarksThemUnsuitableForScreening() {
        SanctionsEntityRecord r = parse(
                """
                {"id":"x","schema":"Person","caption":"Ivan Petrov","properties":{
                  "name":["Ivan Petrov"],"weakAlias":["Vanya"],"topics":["sanction"]}}
                """);

        assertThat(r.names()).extracting(EntityName::value).containsExactly("Ivan Petrov");
    }

    @Test
    void dropsExactDuplicateNamesKeepingTheFirstKind() {
        SanctionsEntityRecord r = parse(
                """
                {"id":"x","schema":"Person","caption":"Ivan Petrov","properties":{
                  "name":["Ivan Petrov"],"alias":["Ivan Petrov","Ivan Petroff"],"previousName":["Ivan Petroff"],
                  "topics":["sanction"]}}
                """);

        assertThat(r.names())
                .containsExactly(
                        new EntityName("Ivan Petrov", NameKind.PRIMARY),
                        new EntityName("Ivan Petroff", NameKind.ALIAS));
    }

    @Test
    void trimsNamesAndDropsBlankOnes() {
        SanctionsEntityRecord r = parse(
                """
                {"id":"x","schema":"Person","caption":"Ivan Petrov","properties":{
                  "name":["  Ivan Petrov  ","   ",""],"topics":["sanction"]}}
                """);

        assertThat(r.names()).extracting(EntityName::value).containsExactly("Ivan Petrov");
    }

    @Test
    void composesAPrimaryNameFromPartsWhenNoNameIsPublished() {
        SanctionsEntityRecord r = parse(
                """
                {"id":"x","schema":"Person","caption":"Maria Gonzalez","properties":{
                  "firstName":["Maria"],"middleName":["Elena"],"lastName":["Gonzalez"],"topics":["sanction"]}}
                """);

        assertThat(r.names()).containsExactly(new EntityName("Maria Elena Gonzalez", NameKind.PRIMARY));
    }

    @Test
    void fallsBackToTheCaptionWhenThereIsNoNameAndNoParts() {
        SanctionsEntityRecord r = parse(
                """
                {"id":"x","schema":"Organization","caption":"Front Holdings","properties":{"topics":["sanction"]}}
                """);

        assertThat(r.names()).containsExactly(new EntityName("Front Holdings", NameKind.PRIMARY));
    }

    @Test
    void entityWithNoUsableNameIsSkipped() {
        Optional<SanctionsEntityRecord> r = parser.parse(
                json(
                        """
                        {"id":"x","schema":"Person","caption":" ","properties":{"weakAlias":["Vanya"],"topics":["sanction"]}}
                        """));

        assertThat(r).isEmpty();
    }

    @Test
    void onlyPrimaryNamesAreNotAliases() {
        assertThat(new EntityName("a", NameKind.PRIMARY).isAlias()).isFalse();
        assertThat(new EntityName("a", NameKind.ALIAS).isAlias()).isTrue();
        assertThat(new EntityName("a", NameKind.PREVIOUS).isAlias()).isTrue();
    }

    // ---------- filtering ----------

    @ParameterizedTest
    @ValueSource(strings = {"Person", "Company", "Organization", "LegalEntity"})
    void importsScreenableSchemas(String schema) {
        assertThat(parser.parse(json(entity(schema, "[\"sanction\"]")))).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Vessel", "Sanction", "Address", "Ownership", "Airplane", "CryptoWallet"})
    void skipsSchemasThatAreNotScreened(String schema) {
        assertThat(parser.parse(json(entity(schema, "[\"sanction\"]")))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[\"sanction\"]", "[\"sanction.linked\"]", "[\"export.control\",\"sanction\"]"})
    void importsEntitiesCarryingAConfiguredTopic(String topics) {
        assertThat(parser.parse(json(entity("Person", topics)))).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[\"sanction.counter\"]", "[\"debarment\"]", "[\"role.pep\"]"})
    void skipsEntitiesWithoutAConfiguredTopic(String topics) {
        assertThat(parser.parse(json(entity("Person", topics)))).isEmpty();
    }

    @Test
    void skipsEntitiesWithNoTopicsAtAll() {
        // e.g. a target's relative: present in the file, but not sanctioned
        assertThat(
                        parser.parse(
                                json(
                                        """
                        {"id":"x","schema":"Person","caption":"Olga Petrova","properties":{"name":["Olga Petrova"]},"target":false}
                        """)))
                .isEmpty();
    }

    @Test
    void missingIdIsMalformed() {
        assertThatThrownBy(() -> parser.parse(json("{\"schema\":\"Person\",\"properties\":{}}")))
                .isInstanceOf(MalformedEntityException.class);
    }

    @Test
    void missingSchemaIsMalformed() {
        assertThatThrownBy(() -> parser.parse(json("{\"id\":\"x\",\"properties\":{}}")))
                .isInstanceOf(MalformedEntityException.class);
    }

    // ---------- secondary identifiers ----------

    @Test
    void keepsPartialBirthDatesExactlyAsPublished() {
        SanctionsEntityRecord r = parse(
                """
                {"id":"x","schema":"Person","caption":"A","properties":{
                  "name":["A"],"birthDate":["1971-03-14","1971","1980-05","1971"],"topics":["sanction"]}}
                """);

        assertThat(r.birthDates()).containsExactly("1971-03-14", "1971", "1980-05");
    }

    @Test
    void mergesCountryPropertiesLowerCasedAndDeduplicated() {
        SanctionsEntityRecord r = parse(
                """
                {"id":"x","schema":"Company","caption":"A","properties":{
                  "name":["A"],"country":["AE"],"jurisdiction":["ru"],"mainCountry":["ru"],"topics":["sanction"]}}
                """);

        assertThat(r.countries()).containsExactly("ae", "ru");
    }

    @Test
    void mergesNationalityAndCitizenshipLowerCasedAndDeduplicated() {
        SanctionsEntityRecord r = parse(
                """
                {"id":"x","schema":"Person","caption":"A","properties":{
                  "name":["A"],"nationality":["RU"],"citizenship":["ru","cy"],"topics":["sanction"]}}
                """);

        assertThat(r.nationalities()).containsExactly("ru", "cy");
    }

    @Test
    void capturesTopicsSourceListsAndProgrammes() {
        SanctionsEntityRecord r = parse(
                """
                {"id":"x","schema":"Person","caption":"A","datasets":["gb_fcdo_sanctions","eu_fsf"],"properties":{
                  "name":["A"],"topics":["export.control","sanction"],"programId":["GB-RUS","EU-UKR"]}}
                """);

        assertThat(r.topics()).containsExactly("export.control", "sanction");
        assertThat(r.sourceLists()).containsExactly("gb_fcdo_sanctions", "eu_fsf");
        assertThat(r.programIds()).containsExactly("GB-RUS", "EU-UKR");
    }

    @Test
    void absentPropertiesBecomeEmptyListsNotNulls() {
        SanctionsEntityRecord r = parse(
                """
                {"id":"x","schema":"Organization","caption":"A","properties":{"name":["A"],"topics":["sanction"]}}
                """);

        assertThat(r.birthDates()).isEmpty();
        assertThat(r.countries()).isEmpty();
        assertThat(r.nationalities()).isEmpty();
        assertThat(r.sourceLists()).isEmpty();
        assertThat(r.programIds()).isEmpty();
    }

    @Test
    void keepsIdentityAndTheRawEntity() {
        JsonNode node = json(
                """
                {"id":"NK-1","schema":"Person","caption":"Ivan Petrov","properties":{"name":["Ivan Petrov"],"topics":["sanction"]},"target":true}
                """);

        SanctionsEntityRecord r = parser.parse(node).orElseThrow();

        assertThat(r.externalId()).isEqualTo("NK-1");
        assertThat(r.schema()).isEqualTo("Person");
        assertThat(r.caption()).isEqualTo("Ivan Petrov");
        assertThat(r.raw()).isEqualTo(node);
    }

    @Test
    void captionDefaultsToTheFirstNameWhenAbsent() {
        SanctionsEntityRecord r = parse(
                """
                {"id":"x","schema":"Person","properties":{"name":["Ivan Petrov"],"topics":["sanction"]}}
                """);

        assertThat(r.caption()).isEqualTo("Ivan Petrov");
    }

    // ---------- helpers ----------

    private SanctionsEntityRecord parse(String entityJson) {
        return parser.parse(json(entityJson)).orElseThrow(() -> new AssertionError("filtered out: " + entityJson));
    }

    private static String entity(String schema, String topicsJson) {
        return "{\"id\":\"x\",\"schema\":\"" + schema + "\",\"caption\":\"A\",\"properties\":{\"name\":[\"A\"],"
                + "\"topics\":" + topicsJson + "}}";
    }

    private static JsonNode json(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
