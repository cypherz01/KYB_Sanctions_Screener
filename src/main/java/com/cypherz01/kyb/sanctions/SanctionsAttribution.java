package com.cypherz01.kyb.sanctions;

/**
 * Attribution required by the OpenSanctions licence (CC BY-NC 4.0, non-commercial use only). Shown
 * in API responses that rely on the data (spec §5.3).
 */
public record SanctionsAttribution(String name, String url, String licence, String licenceUrl) {

    public static final SanctionsAttribution OPENSANCTIONS = new SanctionsAttribution(
            "OpenSanctions",
            "https://www.opensanctions.org",
            "CC BY-NC 4.0",
            "https://creativecommons.org/licenses/by-nc/4.0/");
}
