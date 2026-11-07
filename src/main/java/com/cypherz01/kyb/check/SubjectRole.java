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
    CORPORATE_PSC
}
