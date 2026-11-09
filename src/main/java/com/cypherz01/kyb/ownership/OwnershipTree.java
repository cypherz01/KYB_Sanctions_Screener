package com.cypherz01.kyb.ownership;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The ownership picture returned with a check.
 *
 * @param performed false when replaying a check made before look-through existed
 */
public record OwnershipTree(boolean performed, List<Node> nodes, List<Link> links) {

    /**
     * @param id referred to by {@link Link}
     * @param type {@code COMPANY}, {@code PERSON} or {@code ORGANISATION}
     * @param companyNumber for companies that were followed or could have been, or null
     * @param effective what this owner holds in the checked company; null for that company itself
     * @param stop where the chain stopped at this node, or null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Node(
            String id, String name, String type, String companyNumber, EffectiveInterest effective, ChainStop stop) {}

    /** @param natures the natures of control exactly as Companies House publishes them */
    public record Link(
            String owner,
            String owned,
            List<String> natures,
            double lowerPercent,
            double upperPercent,
            Holding.Kind kind) {}
}
