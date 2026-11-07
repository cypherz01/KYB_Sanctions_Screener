package com.cypherz01.kyb.fca.model;

/**
 * The authorised Register firm matched to the company.
 *
 * @param confidence 1.0 on a Companies House number match, otherwise the name similarity (plus the
 *     postcode boost), capped at 1
 */
public record FcaFirmMatch(
        String frn,
        String name,
        String status,
        FcaMatchBasis basis,
        double confidence,
        String postcode,
        String companiesHouseNumber) {}
