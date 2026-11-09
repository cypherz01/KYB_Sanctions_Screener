package com.cypherz01.kyb.monitoring;

import com.cypherz01.kyb.matching.MatchBand;
import java.util.UUID;

/**
 * A monitored subject that matches a changed sanctions entity.
 *
 * @param subjectName the name as published by Companies House
 * @param externalId the OpenSanctions entity id
 */
public record ReverseHit(UUID subscriptionId, String subjectName, String externalId, MatchBand band) {}
