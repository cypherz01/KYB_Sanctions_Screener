package com.cypherz01.kyb.monitoring;

import java.util.Set;

/**
 * What changed between two sanctions dataset versions, by stable OpenSanctions entity id.
 *
 * @param changed entities in both versions that differ in a field screening reads
 */
public record DatasetDelta(Set<String> added, Set<String> removed, Set<String> changed) {}
