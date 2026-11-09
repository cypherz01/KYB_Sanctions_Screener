package com.cypherz01.kyb.ownership;

/**
 * One owner's holding in one company.
 *
 * @param owner the owner's node id
 * @param owned the owned company's node id
 */
public record OwnershipLink(String owner, String owned, Holding holding) {}
