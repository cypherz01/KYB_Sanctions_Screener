package com.cypherz01.kyb.check;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param engineVersion version of the matching/scoring code; the normaliser version is appended
 * @param includeResignedOfficers also screen resigned officers (spec §9: config, default off)
 * @param nationalities lower-case Companies House demonym to ISO 3166-1 alpha-2 code
 */
@ConfigurationProperties("kyb.checks")
public record ChecksProperties(
        String engineVersion, boolean includeResignedOfficers, Map<String, String> nationalities) {}
