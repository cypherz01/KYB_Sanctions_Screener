package com.cypherz01.kyb.fca;

import com.cypherz01.kyb.cache.SourceCache;
import com.cypherz01.kyb.fca.model.FcaAssessment;
import com.cypherz01.kyb.matching.NameMatcher;
import com.cypherz01.kyb.matching.NameNormaliser;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;

/**
 * Establishes a company's regulatory status from the FCA Register (spec §5.2).
 *
 * <ol>
 *   <li>Search the Register by the company name. The response is cached for {@code cache-ttl}.
 *   <li>Score every hit's name with the sanctions matcher (same normaliser, organisation rules).
 *   <li>Hits with a warning status or a clone/unauthorised name marker that match the name become
 *       warnings.
 *   <li>For the strongest authorised hits (up to {@code max-details-lookups}), fetch firm details:
 *       the same Companies House number is a certain match, a different one rules the firm out.
 *       Otherwise the match rests on name and postcode, with that confidence.
 * </ol>
 *
 * Companies House does not carry the FRN, so this matching is unavoidable. If the Register is
 * unavailable, expired cached responses are used (STALE). Without them the result is UNAVAILABLE,
 * never an exception, so a check still completes.
 */
public class FcaRegisterClient {

    public static final String SOURCE = "fca-register";

    public FcaRegisterClient(
            FcaRegisterApi api,
            SourceCache cache,
            FcaMapper mapper,
            NameNormaliser normaliser,
            NameMatcher matcher,
            FcaProperties properties,
            Clock clock,
            MeterRegistry meters) {}

    /**
     * @param companyName the registered company name to search for
     * @param companyNumber Companies House number, compared with the Register's; may be null
     * @param postcode registered office postcode, compared when the number cannot be; may be null
     */
    public FcaAssessment assess(String companyName, String companyNumber, String postcode) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
