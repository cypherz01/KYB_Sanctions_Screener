package com.cypherz01.kyb.check;

import com.cypherz01.kyb.matching.PartialDate;
import com.cypherz01.kyb.matching.SubjectType;
import java.util.List;
import java.util.Set;

/**
 * A party to screen, prepared from Companies House data.
 *
 * @param roles every role this party holds (a director who is also a PSC is screened once)
 * @param publishedName the name exactly as Companies House publishes it
 * @param screenedName the name as screened (officer names turned from SURNAME, Forenames)
 * @param birthDate month and year for individuals, or null
 * @param countries ISO alpha-2 codes from the published nationality, or empty
 */
public record Subject(
        List<SubjectRole> roles,
        String publishedName,
        String screenedName,
        SubjectType type,
        PartialDate birthDate,
        Set<String> countries) {}
