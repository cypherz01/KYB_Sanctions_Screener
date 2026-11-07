package com.cypherz01.kyb.companieshouse.model;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * A person with significant control.
 *
 * @param registrationCountry for corporate/legal-person PSCs, where the entity is registered (feeds
 *     the CORPORATE_PSC_OFFSHORE rule); null for individuals
 */
public record Psc(
        String name,
        PscKind kind,
        String nationality,
        String countryOfResidence,
        YearMonth dateOfBirth,
        LocalDate notifiedOn,
        LocalDate ceasedOn,
        String registrationCountry,
        List<String> naturesOfControl) {

    public Psc {
        naturesOfControl = naturesOfControl == null ? List.of() : List.copyOf(naturesOfControl);
    }

    public boolean isCurrent() {
        return ceasedOn == null;
    }
}
