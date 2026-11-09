package com.cypherz01.kyb.ownership;

import java.util.List;
import java.util.Map;

/**
 * Works out what each owner effectively holds in a company (ADR-0003). Bands multiply along a path,
 * paths add up, and the total is capped at 100%. Pure, so the arithmetic behind a sanctions decision
 * can be tested on its own.
 */
public final class EffectiveOwnership {

    private EffectiveOwnership() {}

    /**
     * @param company the node id of the company being checked
     * @param links every known holding, which may contain cycles
     * @return each owner with a path up from {@code company}, by node id; never {@code company} itself
     */
    public static Map<String, EffectiveInterest> calculate(String company, List<OwnershipLink> links) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
