package com.cypherz01.kyb.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param apiKey the key callers must send as {@code X-Api-Key} on {@code /api/**}; when unset,
 *     every API call is refused (fail closed)
 */
@ConfigurationProperties("kyb.security")
public record ApiSecurityProperties(String apiKey) {

    public static final String HEADER = "X-Api-Key";

    @Override
    public String toString() {
        return "ApiSecurityProperties[apiKey=" + (apiKey == null || apiKey.isBlank() ? "<unset>" : "****") + "]";
    }
}
