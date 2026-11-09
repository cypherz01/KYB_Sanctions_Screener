package com.cypherz01.kyb.ownership;

/**
 * Where an ownership chain stopped.
 *
 * @param owner the name of the owner the chain could not get past
 * @param detail why, in plain English
 */
public record ChainStop(StopReason reason, String owner, String detail) {}
