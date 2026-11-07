package com.cypherz01.kyb.matching;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PartialDateTest {

    @Test
    void parsesYearOnly() {
        assertThat(PartialDate.parse("1971")).contains(new PartialDate(1971, null, null));
    }

    @Test
    void parsesYearAndMonth() {
        assertThat(PartialDate.parse("1971-03")).contains(new PartialDate(1971, 3, null));
    }

    @Test
    void parsesAFullDate() {
        assertThat(PartialDate.parse(" 1971-03-14 ")).contains(new PartialDate(1971, 3, 14));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"71", "abcd", "1971-13", "1971-00", "1971-02-30", "1971/03/14", "1971-3", "14-03-1971"})
    void rejectsAnythingElse(String text) {
        assertThat(PartialDate.parse(text)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1971", "1971-03", "1971-03-14"})
    void printsWhatWasParsed(String text) {
        assertThat(PartialDate.parse(text).orElseThrow()).hasToString(text);
    }
}
