package com.cypherz01.kyb.ownership;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxDepth how many levels above the company to fetch; 1 means only its direct parents
 * @param maxCompanies how many parent companies one check may fetch
 */
@ConfigurationProperties("kyb.ownership")
public record OwnershipProperties(int maxDepth, int maxCompanies) {}
