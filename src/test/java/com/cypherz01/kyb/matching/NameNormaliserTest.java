package com.cypherz01.kyb.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.cypherz01.kyb.support.MatchingDefaults;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Spec §8.1 pipeline, with the shipped honorific and legal-form lists. */
class NameNormaliserTest {

    final NameNormaliser normaliser = MatchingDefaults.normaliser();

    private List<String> tokens(String name, SubjectType type) {
        return normaliser.normalise(name, type).tokens();
    }

    // ---------- characters and scripts ----------

    @Test
    void stripsDiacriticsAndLowerCases() {
        assertThat(tokens("José MÜLLER", SubjectType.PERSON)).containsExactly("jose", "muller");
    }

    @Test
    void transliteratesCyrillicAndKeepsTheOriginal() {
        NormalisedName n = normaliser.normalise("Иван Петров", SubjectType.PERSON);

        assertThat(n.tokens()).containsExactly("ivan", "petrov");
        assertThat(n.original()).isEqualTo("Иван Петров");
        assertThat(n.normalised()).isEqualTo("ivan petrov");
    }

    @Test
    void transliteratesRussianNamesTheWayListsRomaniseThem() {
        assertThat(tokens("Александр Иванов", SubjectType.PERSON)).containsExactly("aleksandr", "ivanov");
    }

    @ParameterizedTest
    @ValueSource(strings = {"محمد الرشيد", "习近平", "Αλέξης Τσίπρας", "김정은", "Đặng Thị Ngọc Thịnh"})
    void anyScriptEndsUpAsLowerCaseAsciiTokens(String name) {
        assertThat(tokens(name, SubjectType.PERSON)).isNotEmpty().allMatch(t -> t.matches("[a-z0-9]+"));
    }

    @Test
    void dropsApostrophesAndDotsButSplitsOnOtherPunctuation() {
        assertThat(tokens("O'Brien", SubjectType.PERSON)).containsExactly("obrien");
        assertThat(tokens("Jean-Pierre  Dupont", SubjectType.PERSON)).containsExactly("jean", "pierre", "dupont");
        assertThat(tokens("PETROV, Ivan", SubjectType.PERSON)).containsExactly("petrov", "ivan");
        assertThat(tokens("Repsol S.A.", SubjectType.ORGANISATION)).containsExactly("repsol");
    }

    @Test
    void keepsDigits() {
        assertThat(tokens("Shell Entity 77", SubjectType.ORGANISATION)).containsExactly("shell", "entity", "77");
    }

    @Test
    void ampersandBecomesAnd() {
        assertThat(tokens("Smith & Sons", SubjectType.ORGANISATION)).containsExactly("smith", "and", "sons");
        assertThat(tokens("Smith&Sons", SubjectType.ORGANISATION)).containsExactly("smith", "and", "sons");
    }

    @Test
    void blankInputHasNoTokens() {
        NormalisedName n = normaliser.normalise("  ,. ", SubjectType.PERSON);

        assertThat(n.tokens()).isEmpty();
        assertThat(n.normalised()).isEmpty();
    }

    // ---------- honorifics and legal forms ----------

    @Test
    void removesHonorificsFromPeople() {
        assertThat(tokens("Dr. Ivan Petrov", SubjectType.PERSON)).containsExactly("ivan", "petrov");
        assertThat(tokens("Sheikh Mohammed Al-Rashid", SubjectType.PERSON)).containsExactly("mohammed", "al", "rashid");
        assertThat(tokens("Mr John Smith", SubjectType.PERSON)).containsExactly("john", "smith");
    }

    @Test
    void doesNotRemoveHonorificWordsFromOrganisations() {
        assertThat(tokens("Lord Trading Ltd", SubjectType.ORGANISATION)).containsExactly("lord", "trading");
    }

    @Test
    void removesTrailingLegalFormsRepeatedly() {
        assertThat(tokens("Acme Trading Ltd", SubjectType.ORGANISATION)).containsExactly("acme", "trading");
        assertThat(tokens("ACME TRADING LIMITED", SubjectType.ORGANISATION)).containsExactly("acme", "trading");
        assertThat(tokens("Big Co Lp Ltd", SubjectType.ORGANISATION)).containsExactly("big");
        assertThat(tokens("Acme Limited Liability Company", SubjectType.ORGANISATION))
                .containsExactly("acme");
    }

    @Test
    void removesOnlyLegalFormsThatAreWrittenFirstFromTheStart() {
        assertThat(tokens("OOO Romashka", SubjectType.ORGANISATION)).containsExactly("romashka");
        assertThat(tokens("The Acme Group plc", SubjectType.ORGANISATION)).containsExactly("acme", "group");
        // "co" is only a trailing form: the Co-operative Bank keeps its name
        assertThat(tokens("Co-operative Bank Ltd", SubjectType.ORGANISATION))
                .containsExactly("co", "operative", "bank");
    }

    @Test
    void doesNotRemoveLegalFormWordsFromPeople() {
        assertThat(tokens("Ann Co", SubjectType.PERSON)).containsExactly("ann", "co");
    }

    @Test
    void anyTypeAppliesBothRuleSets() {
        assertThat(tokens("Dr Acme Ltd", SubjectType.ANY)).containsExactly("acme");
    }

    @Test
    void neverRemovesEveryToken() {
        assertThat(tokens("Sir", SubjectType.PERSON)).containsExactly("sir");
        assertThat(tokens("Limited", SubjectType.ORGANISATION)).containsExactly("limited");
    }

    // ---------- keys ----------

    @Test
    void sortedKeyIsOrderInsensitive() {
        assertThat(normaliser.normalise("PETROV, Ivan", SubjectType.PERSON).sortedKey())
                .isEqualTo(
                        normaliser.normalise("Ivan Petrov", SubjectType.PERSON).sortedKey())
                .isEqualTo("ivan petrov");
    }

    @Test
    void romanisationVariantsShareAPhoneticKey() {
        String mohammed = normaliser.normalise("Mohammed", SubjectType.PERSON).phoneticKey();

        assertThat(normaliser.normalise("Muhammad", SubjectType.PERSON).phoneticKey())
                .isEqualTo(mohammed);
        assertThat(normaliser.normalise("Mohamed", SubjectType.PERSON).phoneticKey())
                .isEqualTo(mohammed);
    }

    @Test
    void phoneticTokensAlignWithTokensAndTheKeyIsSorted() {
        NormalisedName n = normaliser.normalise("Petrov Ivan", SubjectType.PERSON);

        assertThat(n.phoneticTokens()).hasSameSizeAs(n.tokens());
        assertThat(n.phoneticKey())
                .isEqualTo(String.join(" ", n.phoneticTokens().stream().sorted().toList()));
    }

    @Test
    void rebuildingFromStoredTokensGivesTheSameName() {
        NormalisedName original = normaliser.normalise("Dr. Иван Петров", SubjectType.PERSON);

        assertThat(normaliser.fromTokens(original.original(), original.tokens()))
                .isEqualTo(original);
    }

    // ---------- Companies House officer names ----------

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "PETROV, Ivan Sergeyevich|Ivan Sergeyevich PETROV",
                "  SMITH ,  John  |John SMITH",
                "PETROV,|PETROV",
                "Ivan Petrov|Ivan Petrov"
            })
    void turnsSurnameFirstOfficerNamesAround(String input, String expected) {
        assertThat(NameNormaliser.surnameFirstToNatural(input)).isEqualTo(expected);
    }
}
