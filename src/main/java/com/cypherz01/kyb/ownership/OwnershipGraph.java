package com.cypherz01.kyb.ownership;

import java.time.YearMonth;
import java.util.List;

/**
 * The ownership picture a walk produced.
 *
 * @param parties everyone found above the first level who can be screened
 * @param stops where chains stopped short of reaching a person
 * @param notes holdings that could not be understood
 */
public record OwnershipGraph(OwnershipTree tree, List<Party> parties, List<ChainStop> stops, List<String> notes) {

    /**
     * @param followedCompany a company whose own PSCs were looked up
     * @param person an individual rather than an organisation
     * @param birthDate month and year, for individuals
     * @param nationality every nationality published for them, comma-separated, for individuals
     * @param interest what they effectively hold; null if they count as a first-level party
     */
    public record Party(
            String name,
            boolean followedCompany,
            boolean person,
            YearMonth birthDate,
            String nationality,
            EffectiveInterest interest) {}
}
