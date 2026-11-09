package com.cypherz01.kyb.ownership;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out what each owner effectively holds in a company (ADR-0003). Bands multiply along a path,
 * paths add up, and the total is capped at 100%. Pure, so the arithmetic behind a sanctions decision
 * can be tested on its own.
 *
 * <p>Every published band is a multiple of a quarter, so products and sums of them are exact in
 * binary floating point and a replay compares equal without any rounding.
 */
public final class EffectiveOwnership {

    private EffectiveOwnership() {}

    /**
     * @param company the node id of the company being checked
     * @param links every known holding, which may contain cycles
     * @return each owner with a path up from {@code company}, by node id; never {@code company} itself
     */
    public static Map<String, EffectiveInterest> calculate(String company, List<OwnershipLink> links) {
        Map<String, List<OwnershipLink>> ownersOf = new LinkedHashMap<>();
        for (OwnershipLink link : links) {
            ownersOf.computeIfAbsent(link.owned(), k -> new ArrayList<>()).add(link);
        }
        Map<String, Running> totals = new LinkedHashMap<>();
        Set<String> onPath = new HashSet<>();
        onPath.add(company);
        walk(company, 100, 100, true, ownersOf, onPath, totals);

        Map<String, EffectiveInterest> result = new LinkedHashMap<>();
        totals.forEach((owner, t) ->
                result.put(owner, new EffectiveInterest(Math.min(100, t.lower), Math.min(100, t.upper), t.control)));
        return result;
    }

    /** Depth-first over simple paths: a company already on the path ends it, which breaks a cycle. */
    private static void walk(
            String owned,
            double lower,
            double upper,
            boolean allControl,
            Map<String, List<OwnershipLink>> ownersOf,
            Set<String> onPath,
            Map<String, Running> totals) {
        for (OwnershipLink link : ownersOf.getOrDefault(owned, List.of())) {
            String owner = link.owner();
            if (!onPath.add(owner)) {
                continue;
            }
            Holding holding = link.holding();
            double pathLower = lower * holding.lowerPercent() / 100;
            double pathUpper = upper * holding.upperPercent() / 100;
            boolean pathControl = allControl && holding.kind() == Holding.Kind.CONTROL;

            Running total = totals.computeIfAbsent(owner, k -> new Running());
            total.lower += pathLower;
            total.upper += pathUpper;
            total.control |= pathControl;

            walk(owner, pathLower, pathUpper, pathControl, ownersOf, onPath, totals);
            onPath.remove(owner);
        }
    }

    private static final class Running {
        double lower;
        double upper;
        boolean control;
    }
}
