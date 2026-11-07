package com.cypherz01.kyb.companieshouse.model;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * A company officer as published by Companies House.
 *
 * @param name raw Companies House form, usually {@code SURNAME, Forenames}; parsing is the
 *     matcher's job (M3), so the original is preserved here
 * @param dateOfBirth Companies House only publishes month and year
 * @param corporate true for corporate officers (e.g. {@code corporate-director})
 */
public record Officer(
        String name,
        String role,
        boolean corporate,
        LocalDate appointedOn,
        LocalDate resignedOn,
        String nationality,
        String countryOfResidence,
        YearMonth dateOfBirth) {

    public boolean isCurrent() {
        return resignedOn == null;
    }
}
