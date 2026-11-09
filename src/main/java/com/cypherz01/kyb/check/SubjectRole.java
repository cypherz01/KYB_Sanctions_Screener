package com.cypherz01.kyb.check;

/** Why a party is screened in a check (spec §9 "subjects screened"). */
public enum SubjectRole {
    COMPANY,
    DIRECTOR,
    SECRETARY,
    /** Any other officer role (e.g. LLP member). */
    OFFICER,
    CORPORATE_OFFICER,
    PSC,
    CORPORATE_PSC,
    /** A company above the first level that was followed: it is screened, its officers are not. */
    INTERMEDIATE_COMPANY,
    /** A PSC above the first level that was not itself followed. */
    UPSTREAM_PSC
}
