package com.cypherz01.kyb.scoring;

/** The risk rules of spec §9. Points for each come from {@code scoring-rules.yml}. */
public enum FactorCode {
    COMPANY_NOT_ACTIVE,
    COMPANY_RECENTLY_INCORPORATED,
    ACCOUNTS_OVERDUE,
    NO_PSC_DECLARED,
    CORPORATE_PSC_OFFSHORE,
    HIGH_OFFICER_CHURN,
    FCA_CLONE_OR_UNAUTHORISED_MATCH,
    FCA_NOT_AUTHORISED_FOR_ACTIVITY,
    FCA_UNAVAILABLE,
    SANCTIONS_POSSIBLE_MATCH,
    /** Automatic FAIL. */
    SANCTIONS_STRONG_MATCH
}
