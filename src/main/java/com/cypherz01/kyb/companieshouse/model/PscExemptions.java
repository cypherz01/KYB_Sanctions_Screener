package com.cypherz01.kyb.companieshouse.model;

import java.util.List;

/**
 * Exemptions from keeping a PSC register ({@code GET /company/{n}/exemptions}). Listed companies
 * are exempt, so "no PSCs" is not suspicious for them (spec §9 NO_PSC_DECLARED; found in the M1
 * smoke run on Tesco PLC). Verified live 2026-11-07: an item without {@code exempt_to} is current,
 * and a company with no exemptions gets 404.
 *
 * @param current exemption types in force, e.g. {@code psc-exempt-as-trading-on-uk-regulated-market}
 */
public record PscExemptions(List<String> current) {

    public static final PscExemptions NONE = new PscExemptions(List.of());

    public PscExemptions {
        current = current == null ? List.of() : List.copyOf(current);
    }

    /** At least one exemption is in force. */
    public boolean exempt() {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
