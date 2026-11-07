package com.cypherz01.kyb.companieshouse.model;

/**
 * Collapses the eight Companies House PSC {@code kind} strings (person-with-significant-control and
 * beneficial-owner variants) into the categories the screening and scoring rules need.
 */
public enum PscKind {
    INDIVIDUAL,
    CORPORATE,
    LEGAL_PERSON,
    /** Details withheld by Companies House for the person's protection: cannot be name-screened. */
    SUPER_SECURE,
    UNKNOWN
}
