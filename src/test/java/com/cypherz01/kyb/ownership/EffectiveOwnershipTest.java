package com.cypherz01.kyb.ownership;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ADR-0003. Expected values are worked out by hand from the published bands, never by the code. */
class EffectiveOwnershipTest {

    static final Holding Q25_50 = new Holding(25, 50, Holding.Kind.OWNERSHIP, true);
    static final Holding Q50_75 = new Holding(50, 75, Holding.Kind.OWNERSHIP, true);
    static final Holding Q75_100 = new Holding(75, 100, Holding.Kind.OWNERSHIP, true);
    static final Holding CONTROL = new Holding(100, 100, Holding.Kind.CONTROL, true);
    static final Holding INFLUENCE = new Holding(0, 100, Holding.Kind.INFLUENCE, true);

    static OwnershipLink link(String owner, String owned, Holding holding) {
        return new OwnershipLink(owner, owned, holding);
    }

    static Map<String, EffectiveInterest> calculate(OwnershipLink... links) {
        return EffectiveOwnership.calculate("company", List.of(links));
    }

    @Test
    void aDirectOwnerHoldsItsBand() {
        assertThat(calculate(link("s", "company", Q50_75))).containsEntry("s", new EffectiveInterest(50, 75, false));
    }

    @Test
    void bandsMultiplyAlongAPath() {
        Map<String, EffectiveInterest> result = calculate(link("h", "company", Q75_100), link("s", "h", Q75_100));

        assertThat(result).containsEntry("h", new EffectiveInterest(75, 100, false));
        assertThat(result).containsEntry("s", new EffectiveInterest(56.25, 100, false));
    }

    @Test
    void aMinorityLinkBringsTheLowerBoundBelowHalf() {
        assertThat(calculate(link("h", "company", Q50_75), link("s", "h", Q75_100)))
                .containsEntry("s", new EffectiveInterest(37.5, 75, false));
    }

    @Test
    void separatePathsAddUp() {
        assertThat(calculate(
                        link("h1", "company", Q25_50),
                        link("s", "h1", CONTROL),
                        link("h2", "company", Q25_50),
                        link("s", "h2", CONTROL)))
                .containsEntry("s", new EffectiveInterest(50, 100, false));
    }

    @Test
    void aDirectAndAnIndirectHoldingAddUp() {
        assertThat(calculate(link("s", "company", Q25_50), link("h", "company", Q50_75), link("s", "h", Q50_75)))
                .containsEntry("s", new EffectiveInterest(50, 100, false));
    }

    @Test
    void theTotalIsCappedAtOneHundred() {
        assertThat(calculate(
                        link("h1", "company", Q75_100),
                        link("s", "h1", CONTROL),
                        link("h2", "company", Q75_100),
                        link("s", "h2", CONTROL)))
                .containsEntry("s", new EffectiveInterest(100, 100, false));
    }

    @Test
    void anUnbrokenChainOfControlLinksIsControl() {
        assertThat(calculate(link("h", "company", CONTROL), link("s", "h", CONTROL)))
                .containsEntry("s", new EffectiveInterest(100, 100, true));
    }

    @Test
    void controlOverAMinorityHolderIsNotControlOfTheCompany() {
        assertThat(calculate(link("h", "company", Q25_50), link("s", "h", CONTROL)))
                .containsEntry("s", new EffectiveInterest(25, 50, false));
    }

    @Test
    void aMinorityStakeInAControllerIsNotControlOfTheCompany() {
        assertThat(calculate(link("h", "company", CONTROL), link("s", "h", Q25_50)))
                .containsEntry("s", new EffectiveInterest(25, 50, false));
    }

    @Test
    void oneControllingPathIsEnoughForControl() {
        EffectiveInterest s = calculate(
                        link("h1", "company", CONTROL),
                        link("s", "h1", CONTROL),
                        link("h2", "company", Q25_50),
                        link("s", "h2", Q25_50))
                .get("s");

        assertThat(s.control()).isTrue();
    }

    @Test
    void influenceAloneHasNoLowerBound() {
        assertThat(calculate(link("s", "company", INFLUENCE))).containsEntry("s", new EffectiveInterest(0, 100, false));
    }

    @Test
    void influenceAnywhereOnAPathRemovesItsLowerBound() {
        assertThat(calculate(link("h", "company", INFLUENCE), link("s", "h", Q75_100)))
                .containsEntry("s", new EffectiveInterest(0, 100, false));
    }

    @Test
    void anotherPathCanStillGiveInfluenceALowerBound() {
        assertThat(calculate(link("s", "company", INFLUENCE), link("h", "company", Q50_75), link("s", "h", CONTROL)))
                .containsEntry("s", new EffectiveInterest(50, 100, false));
    }

    @Test
    void aCycleEndsInsteadOfCountingTwice() {
        Map<String, EffectiveInterest> result =
                calculate(link("h", "company", Q75_100), link("k", "h", Q75_100), link("h", "k", Q75_100));

        assertThat(result).containsEntry("h", new EffectiveInterest(75, 100, false));
        assertThat(result).containsEntry("k", new EffectiveInterest(56.25, 100, false));
    }

    @Test
    void theCompanyIsNeverItsOwnOwner() {
        assertThat(calculate(link("h", "company", Q75_100), link("company", "h", Q25_50)))
                .containsOnlyKeys("h");
    }

    @Test
    void anOwnerWithNoPathToTheCompanyIsLeftOut() {
        assertThat(calculate(link("h", "company", Q75_100), link("x", "elsewhere", Q75_100)))
                .containsOnlyKeys("h");
    }

    @Test
    void noLinksMeansNoOwners() {
        assertThat(calculate()).isEmpty();
    }
}
