package com.cypherz01.kyb.matching;

import java.util.Set;

/**
 * Who is being screened.
 *
 * @param birthDate null if unknown
 * @param countries ISO 3166-1 alpha-2 codes, lower case (nationality / country of registration);
 *     empty if unknown
 */
public record MatchSubject(NormalisedName name, SubjectType type, PartialDate birthDate, Set<String> countries) {}
