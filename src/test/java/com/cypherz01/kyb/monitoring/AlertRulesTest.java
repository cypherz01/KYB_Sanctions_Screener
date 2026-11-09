package com.cypherz01.kyb.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.cypherz01.kyb.monitoring.AlertChange.MatchKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** ADR-0004: an alert is raised only when the decision or the set of matches changes. */
class AlertRulesTest {

    final ObjectMapper json = new ObjectMapper();

    JsonNode check(String decision, String... subjects) throws Exception {
        return json.readTree("{\"decision\":\"%s\",\"subjects\":[%s]}".formatted(decision, String.join(",", subjects)));
    }

    static String subject(String name, String... matches) {
        return "{\"name\":\"%s\",\"type\":\"PERSON\",\"matches\":[%s]}".formatted(name, String.join(",", matches));
    }

    static String match(String id, String band, double similarity) {
        return "{\"sanctionsEntityId\":\"%s\",\"band\":\"%s\",\"similarity\":%s}".formatted(id, band, similarity);
    }

    @Test
    void nothingChangedRaisesNothing() throws Exception {
        JsonNode same = check("PASS", subject("Sarah Jones"));

        assertThat(AlertRules.compare(same, same)).isEmpty();
    }

    @Test
    void aSmallSimilarityChangeRaisesNothing() throws Exception {
        assertThat(AlertRules.compare(
                        check("REVIEW", subject("Sarah Jones", match("NK-1", "POSSIBLE", 0.81))),
                        check("REVIEW", subject("Sarah Jones", match("NK-1", "POSSIBLE", 0.84)))))
                .isEmpty();
    }

    @Test
    void aNewMatchIsAChange() throws Exception {
        AlertChange change = AlertRules.compare(
                        check("PASS", subject("Sarah Jones")),
                        check("FAIL", subject("Sarah Jones", match("NK-1", "STRONG", 0.97))))
                .orElseThrow();

        assertThat(change.type()).isEqualTo(AlertChange.Type.MATCHES_CHANGED);
        assertThat(change.decisionBefore()).isEqualTo("PASS");
        assertThat(change.decisionAfter()).isEqualTo("FAIL");
        assertThat(change.added()).containsExactly(new MatchKey("Sarah Jones", "NK-1", "STRONG"));
        assertThat(change.removed()).isEmpty();
    }

    @Test
    void aNewMatchThatLeavesTheDecisionAloneIsStillAChange() throws Exception {
        assertThat(AlertRules.compare(
                        check("REVIEW", subject("Sarah Jones", match("NK-1", "POSSIBLE", 0.81))),
                        check(
                                "REVIEW",
                                subject(
                                        "Sarah Jones",
                                        match("NK-1", "POSSIBLE", 0.81),
                                        match("NK-2", "POSSIBLE", 0.82)))))
                .map(AlertChange::type)
                .contains(AlertChange.Type.MATCHES_CHANGED);
    }

    /** Only the best match drives a factor, but a person needs to hear about the others too. */
    @Test
    void aChangeBehindTheBestMatchStillCounts() throws Exception {
        Optional<AlertChange> change = AlertRules.compare(
                check("FAIL", subject("Sarah Jones", match("NK-1", "STRONG", 0.97))),
                check("FAIL", subject("Sarah Jones", match("NK-1", "STRONG", 0.97), match("NK-2", "POSSIBLE", 0.82))));

        assertThat(change.orElseThrow().added()).containsExactly(new MatchKey("Sarah Jones", "NK-2", "POSSIBLE"));
    }

    @Test
    void aPossibleMatchBecomingStrongIsAChange() throws Exception {
        AlertChange change = AlertRules.compare(
                        check("REVIEW", subject("Sarah Jones", match("NK-1", "POSSIBLE", 0.85))),
                        check("FAIL", subject("Sarah Jones", match("NK-1", "STRONG", 0.95))))
                .orElseThrow();

        assertThat(change.type()).isEqualTo(AlertChange.Type.MATCHES_CHANGED);
        assertThat(change.removed()).containsExactly(new MatchKey("Sarah Jones", "NK-1", "POSSIBLE"));
        assertThat(change.added()).containsExactly(new MatchKey("Sarah Jones", "NK-1", "STRONG"));
    }

    @Test
    void aMatchGoingAwayWithNothingNewIsCleared() throws Exception {
        AlertChange change = AlertRules.compare(
                        check("FAIL", subject("Sarah Jones", match("NK-1", "STRONG", 0.97))),
                        check("PASS", subject("Sarah Jones")))
                .orElseThrow();

        assertThat(change.type()).isEqualTo(AlertChange.Type.MATCH_CLEARED);
        assertThat(change.removed()).containsExactly(new MatchKey("Sarah Jones", "NK-1", "STRONG"));
    }

    @Test
    void aDirectorLeavingTakesTheirMatchWithThem() throws Exception {
        AlertChange change = AlertRules.compare(
                        check("FAIL", subject("Ivan Petrov", match("NK-1", "STRONG", 1.0)), subject("Sarah Jones")),
                        check("PASS", subject("Sarah Jones")))
                .orElseThrow();

        assertThat(change.type()).isEqualTo(AlertChange.Type.MATCH_CLEARED);
    }

    @Test
    void aDecisionChangingWithTheMatchesAloneIsAChange() throws Exception {
        AlertChange change = AlertRules.compare(
                        check("PASS", subject("Sarah Jones")), check("REVIEW", subject("Sarah Jones")))
                .orElseThrow();

        assertThat(change.type()).isEqualTo(AlertChange.Type.DECISION_CHANGED);
        assertThat(change.added()).isEmpty();
        assertThat(change.removed()).isEmpty();
    }

    /** A different person matching the same entity is a different match. */
    @Test
    void theSubjectIsPartOfWhatIdentifiesAMatch() throws Exception {
        AlertChange change = AlertRules.compare(
                        check("REVIEW", subject("Sarah Jones", match("NK-1", "POSSIBLE", 0.85))),
                        check("REVIEW", subject("Amy Jones", match("NK-1", "POSSIBLE", 0.85))))
                .orElseThrow();

        assertThat(change.type()).isEqualTo(AlertChange.Type.MATCHES_CHANGED);
    }
}
