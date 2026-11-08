package com.cypherz01.kyb.check;

/**
 * The check exists but what it ran against does not: its dataset version has been pruned, or it
 * predates source snapshots. Refused rather than attempted, because screening against missing rows
 * would look like a clean result.
 */
public class CheckNotReplayableException extends RuntimeException {

    public CheckNotReplayableException(String message) {
        super(message);
    }
}
