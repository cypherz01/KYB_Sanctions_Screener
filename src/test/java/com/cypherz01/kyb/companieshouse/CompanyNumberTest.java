package com.cypherz01.kyb.companieshouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CompanyNumberTest {

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
        "09446231, 09446231",
        "'  09446231  ', 09446231",
        // users often drop leading zeros of England & Wales numbers
        "9446231, 09446231",
        "6, 00000006",
        // prefixed numbers: Scotland, Northern Ireland, LLPs; case-insensitive input
        "SC123456, SC123456",
        "sc123456, SC123456",
        "NI012345, NI012345",
        "OC301234, OC301234",
        "R0123456, R0123456",
    })
    void acceptsAndCanonicalisesValidNumbers(String raw, String expected) {
        assertThat(CompanyNumber.parse(raw).value()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "rejects \"{0}\"")
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "   ",
                "123456789", // too long
                "SC12345", // prefixed numbers are never padded: 7 chars is invalid
                "ABC12345", // three-letter prefix
                "12-34567", // punctuation
                "SC12345X", // non-digit suffix
                "../company", // path injection attempt
            })
    void rejectsInvalidNumbers(String raw) {
        assertThatThrownBy(() -> CompanyNumber.parse(raw)).isInstanceOf(InvalidCompanyNumberException.class);
    }
}
