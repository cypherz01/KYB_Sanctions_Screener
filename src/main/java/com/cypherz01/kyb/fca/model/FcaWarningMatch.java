package com.cypherz01.kyb.fca.model;

import java.util.List;

/**
 * A Register entry for an unauthorised or clone firm whose name matches the company: the M5
 * FCA_CLONE_OR_UNAUTHORISED_MATCH factor.
 *
 * @param similarity name similarity from the sanctions matcher (0-1)
 * @param evidence why it is a warning and why it matched
 */
public record FcaWarningMatch(String frn, String name, String status, double similarity, List<String> evidence) {}
