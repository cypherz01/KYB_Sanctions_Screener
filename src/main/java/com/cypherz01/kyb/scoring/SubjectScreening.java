package com.cypherz01.kyb.scoring;

import com.cypherz01.kyb.matching.ScreeningMatch;
import com.cypherz01.kyb.ownership.EffectiveInterest;
import java.util.List;

/**
 * One screened subject and its best sanctions match.
 *
 * @param roles e.g. {@code COMPANY}, {@code DIRECTOR}, {@code PSC}
 * @param name the name as published by Companies House
 * @param birthMonth {@code yyyy-MM}, or null
 * @param bestMatch the strongest retained match, or null if none reached POSSIBLE
 * @param upstream what this subject effectively holds if it is known only from above the first
 *     level; null for the company, its officers and its direct PSCs, whom ownership never excuses
 */
public record SubjectScreening(
        List<String> roles, String name, String birthMonth, ScreeningMatch bestMatch, EffectiveInterest upstream) {}
