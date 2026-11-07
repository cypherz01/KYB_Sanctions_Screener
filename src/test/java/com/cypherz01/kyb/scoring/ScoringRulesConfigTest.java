package com.cypherz01.kyb.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.cypherz01.kyb.support.ShippedConfig;
import org.junit.jupiter.api.Test;

/** scoring-rules.yml is complete: every rule has points, and the version is recorded. */
class ScoringRulesConfigTest {

    final ScoringProperties props = ShippedConfig.scoring();

    @Test
    void hasAVersion() {
        assertThat(props.version()).isNotBlank();
    }

    @Test
    void everyRuleHasPoints() {
        assertThat(props.points()).containsOnlyKeys(FactorCode.values());
    }

    @Test
    void usesTheSpecDefaults() {
        assertThat(props.thresholds().review()).isEqualTo(40);
        assertThat(props.thresholds().fail()).isEqualTo(80);
        assertThat(props.points().get(FactorCode.FCA_CLONE_OR_UNAUTHORISED_MATCH))
                .isEqualTo(60);
        assertThat(props.regulatedSicCodes()).containsKey("64921");
        assertThat(props.ukCountries()).contains("united kingdom");
    }
}
