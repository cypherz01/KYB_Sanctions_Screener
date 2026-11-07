package com.cypherz01.kyb.scoring;

/** Spec §9: any automatic-fail factor or score >= fail is FAIL; score >= review is REVIEW. */
public enum Decision {
    PASS,
    REVIEW,
    FAIL
}
