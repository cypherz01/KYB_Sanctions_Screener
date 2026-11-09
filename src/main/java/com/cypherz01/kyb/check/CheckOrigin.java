package com.cypherz01.kyb.check;

import java.util.UUID;

/**
 * Why a check ran. Stored in its request, so its audit trail shows what asked for it.
 *
 * @param trigger e.g. {@code MANUAL}, {@code SANCTIONS_UPDATE}
 * @param subscriptionId the subscription it ran for, or null
 * @param previousCheckId the check it is compared with, or null
 */
public record CheckOrigin(String trigger, UUID subscriptionId, UUID previousCheckId) {

    public static final CheckOrigin MANUAL = new CheckOrigin("MANUAL", null, null);
}
