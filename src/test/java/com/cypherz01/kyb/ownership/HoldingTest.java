package com.cypherz01.kyb.ownership;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Nature strings as published in the Companies House api-enumerations, checked 2026-11-09. */
class HoldingTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "ownership-of-shares-25-to-50-percent, 25, 50",
        "ownership-of-shares-50-to-75-percent, 50, 75",
        "ownership-of-shares-75-to-100-percent, 75, 100",
        "ownership-of-shares-50-to-75-percent-as-trust, 50, 75",
        "ownership-of-shares-75-to-100-percent-as-firm, 75, 100",
        "voting-rights-25-to-50-percent, 25, 50",
        "voting-rights-75-to-100-percent-limited-liability-partnership, 75, 100",
        "voting-rights-50-to-75-percent-as-trust-limited-liability-partnership, 50, 75",
        "right-to-share-surplus-assets-50-to-75-percent-limited-liability-partnership, 50, 75",
        "part-right-to-share-surplus-assets-25-to-50-percent, 25, 50",
        "ownership-of-shares-more-than-25-percent-registered-overseas-entity, 25, 100",
        "voting-rights-more-than-25-percent-as-firm-registered-overseas-entity, 25, 100",
    })
    void readsAnOwnershipBand(String nature, double lower, double upper) {
        assertThat(Holding.fromNatures(List.of(nature)))
                .isEqualTo(new Holding(lower, upper, Holding.Kind.OWNERSHIP, true));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "right-to-appoint-and-remove-directors",
                "right-to-appoint-and-remove-directors-as-trust",
                "right-to-appoint-and-remove-directors-registered-overseas-entity",
                "right-to-appoint-and-remove-members-limited-liability-partnership",
                "right-to-appoint-and-remove-person"
            })
    void readsTheRightToAppointAsControl(String nature) {
        assertThat(Holding.fromNatures(List.of(nature))).isEqualTo(new Holding(100, 100, Holding.Kind.CONTROL, true));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "significant-influence-or-control",
                "significant-influence-or-control-as-firm",
                "significant-influence-or-control-limited-liability-partnership",
                "significant-influence-or-control-registered-overseas-entity"
            })
    void readsSignificantInfluenceAsInfluence(String nature) {
        assertThat(Holding.fromNatures(List.of(nature))).isEqualTo(new Holding(0, 100, Holding.Kind.INFLUENCE, true));
    }

    @Test
    void takesTheHigherOfSharesAndVotingRights() {
        assertThat(Holding.fromNatures(
                        List.of("ownership-of-shares-25-to-50-percent", "voting-rights-75-to-100-percent")))
                .isEqualTo(new Holding(75, 100, Holding.Kind.OWNERSHIP, true));
    }

    @Test
    void controlOutranksAnOwnershipBand() {
        assertThat(Holding.fromNatures(
                        List.of("ownership-of-shares-25-to-50-percent", "right-to-appoint-and-remove-directors")))
                .isEqualTo(new Holding(100, 100, Holding.Kind.CONTROL, true));
    }

    @Test
    void anOwnershipBandOutranksInfluence() {
        assertThat(Holding.fromNatures(
                        List.of("significant-influence-or-control", "ownership-of-shares-25-to-50-percent")))
                .isEqualTo(new Holding(25, 50, Holding.Kind.OWNERSHIP, true));
    }

    @Test
    void aKnownNatureIsNotSpoiledByAnUnknownOne() {
        assertThat(Holding.fromNatures(List.of("something-new", "voting-rights-50-to-75-percent")))
                .isEqualTo(new Holding(50, 75, Holding.Kind.OWNERSHIP, true));
    }

    /** Not knowing what a holding means must not read as the holding being nothing. */
    @Test
    void anUnknownNatureIsTreatedAsInfluenceAndFlagged() {
        assertThat(Holding.fromNatures(
                        List.of("registered-owner-as-nominee-person-england-wales-registered-overseas-entity")))
                .isEqualTo(new Holding(0, 100, Holding.Kind.INFLUENCE, false));
    }

    @Test
    void noNaturesAtAllIsTreatedTheSameWay() {
        assertThat(Holding.fromNatures(List.of())).isEqualTo(new Holding(0, 100, Holding.Kind.INFLUENCE, false));
    }

    @Test
    void listsOnlyTheNaturesItDoesNotUnderstand() {
        assertThat(Holding.unknownNatures(List.of(
                        "voting-rights-50-to-75-percent",
                        "something-new",
                        "right-to-appoint-and-remove-directors",
                        "significant-influence-or-control",
                        "something-else")))
                .containsExactly("something-new", "something-else");
    }
}
