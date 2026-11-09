package com.cypherz01.kyb.monitoring;

/** Delivers the outbox at least once, signed, with backoff (ADR-0006). */
public class WebhookDispatcher {

    /** Sends every webhook that is due. */
    public void deliverDue() {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
