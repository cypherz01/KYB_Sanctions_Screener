package com.cypherz01.kyb.fca;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.web.client.RestClient;

/**
 * Raw HTTP access to the FCA Register: {@code X-AUTH-EMAIL}/{@code X-AUTH-KEY} headers, a
 * client-side rate limiter below the documented 50 requests per 10 seconds, and a circuit breaker
 * around every call, so a dead Register is not hammered and fails fast. Returns raw JSON envelopes.
 * No caching here. No retries either: the cache's stale fallback is the recovery path for a
 * Register with no SLA.
 */
public class FcaRegisterApi {

    public FcaRegisterApi(RestClient restClient, FcaProperties properties, MeterRegistry meters) {}

    /** Base URL, timeouts, JSON accept and the auth headers (never logged). */
    public static RestClient buildRestClient(RestClient.Builder builder, FcaProperties properties) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    /**
     * {@code GET /Search?q={name}&type=firm}
     *
     * @throws FcaUnavailableException no credentials, unreachable, non-2xx, rate-limited, or
     *     circuit open
     */
    public JsonNode searchFirms(String name) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    /**
     * {@code GET /Firm/{frn}}
     *
     * @throws FcaUnavailableException as for {@link #searchFirms}
     */
    public JsonNode firm(String frn) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
