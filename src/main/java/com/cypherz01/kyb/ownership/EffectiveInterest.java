package com.cypherz01.kyb.ownership;

/**
 * What an owner effectively holds in the company being checked, across every path up to it.
 *
 * @param lowerPercent the least it can be, given the published bands
 * @param upperPercent the most it can be, capped at 100
 * @param control true if some path consists entirely of control links
 */
public record EffectiveInterest(double lowerPercent, double upperPercent, boolean control) {}
