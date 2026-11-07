package com.cypherz01.kyb.matching;

/**
 * An ad-hoc screening request (spec §7 {@code /screen/name}).
 *
 * @param type null means {@link SubjectType#ANY}
 * @param birthDate optional: {@code yyyy}, {@code yyyy-MM} or {@code yyyy-MM-dd}
 * @param country optional ISO 3166-1 alpha-2 code (nationality or country of registration)
 */
public record ScreeningRequest(String name, SubjectType type, String birthDate, String country) {}
