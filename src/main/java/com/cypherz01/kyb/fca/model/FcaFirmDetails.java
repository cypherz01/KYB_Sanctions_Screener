package com.cypherz01.kyb.fca.model;

/**
 * {@code /Firm/{FRN}}.
 *
 * @param companiesHouseNumber canonical 8-character form when it parses as one, otherwise as
 *     published (upper-cased), or null when absent
 */
public record FcaFirmDetails(
        String frn,
        String name,
        String status,
        String statusEffectiveDate,
        String businessType,
        String companiesHouseNumber) {}
