package com.cypherz01.kyb.fca.model;

/** Why a Register firm is believed to be the company being checked, strongest first. */
public enum FcaMatchBasis {
    /** The Register's firm details carry the same Companies House number: certain. */
    COMPANIES_HOUSE_NUMBER,
    /** Normalised names match and the postcodes agree. */
    NAME_AND_POSTCODE,
    /** Normalised names match; nothing else could be compared. */
    NAME
}
