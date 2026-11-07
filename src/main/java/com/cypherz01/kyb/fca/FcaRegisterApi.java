package com.cypherz01.kyb.fca;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRateLimiterMetrics;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/**
 * Raw HTTP access to the FCA Register: {@code X-AUTH-EMAIL}/{@code X-AUTH-KEY} headers, a
 * client-side rate limiter below the documented 50 requests per 10 seconds, and a circuit breaker
 * around every call, so a dead Register is not hammered and fails fast. Returns raw JSON envelopes.
 * No caching here. No retries either: the cache's stale fallback is the recovery path for a
 * Register with no SLA.
 *
 * <p>Order is circuitBreaker(rateLimiter(call)): a call rejected by the local limiter is recorded
 * as a breaker failure, like an upstream 429 would be.
 */
public class FcaRegisterApi {

    private static final Logger log = LoggerFactory.getLogger(FcaRegisterApi.class);
    private static final String SOURCE = FcaRegisterClient.SOURCE;

    private final RestClient rest;
    private final FcaProperties properties;
    private final MeterRegistry meters;
    private final RateLimiter rateLimiter;
    private final CircuitBreaker breaker;

    public FcaRegisterApi(RestClient restClient, FcaProperties properties, MeterRegistry meters) {
        this.rest = restClient;
        this.properties = properties;
        this.meters = meters;

        FcaProperties.RateLimit rl = properties.rateLimit();
        RateLimiterRegistry limiters = RateLimiterRegistry.of(RateLimiterConfig.custom()
                .limitForPeriod(rl.limit())
                .limitRefreshPeriod(rl.period())
                .timeoutDuration(rl.maxWait())
                .build());
        this.rateLimiter = limiters.rateLimiter(SOURCE);

        FcaProperties.CircuitBreaker cb = properties.circuitBreaker();
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .failureRateThreshold(cb.failureRateThreshold())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cb.slidingWindowSize())
                .minimumNumberOfCalls(cb.minimumNumberOfCalls())
                .waitDurationInOpenState(cb.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(cb.permittedCallsInHalfOpenState())
                .recordExceptions(FcaUnavailableException.class, RequestNotPermitted.class)
                .build());
        this.breaker = breakers.circuitBreaker(SOURCE);

        TaggedRateLimiterMetrics.ofRateLimiterRegistry(limiters).bindTo(meters);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(meters);
    }

    /** Base URL, timeouts, JSON accept and the auth headers (never logged). */
    public static RestClient buildRestClient(RestClient.Builder builder, FcaProperties properties) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout()); // whole exchange: fine for small responses
        return builder.baseUrl(properties.baseUrl().toString())
                .requestFactory(factory)
                .defaultHeaders(h -> {
                    h.setAccept(List.of(MediaType.APPLICATION_JSON));
                    if (properties.hasCredentials()) {
                        h.set("X-AUTH-EMAIL", properties.authEmail());
                        h.set("X-AUTH-KEY", properties.authKey());
                    }
                })
                .build();
    }

    /**
     * {@code GET /Search?q={name}&type=firm}
     *
     * @throws FcaUnavailableException no credentials, unreachable, non-2xx, rate-limited, or
     *     circuit open
     */
    public JsonNode searchFirms(String name) {
        return execute("search", b -> b.path("/Search")
                .queryParam("q", name)
                .queryParam("type", "firm")
                .build());
    }

    /**
     * {@code GET /Firm/{frn}}
     *
     * @throws FcaUnavailableException as for {@link #searchFirms}
     */
    public JsonNode firm(String frn) {
        return execute("firm", b -> b.path("/Firm/{frn}").build(frn));
    }

    private JsonNode execute(String operation, Function<UriBuilder, URI> uri) {
        if (!properties.hasCredentials()) {
            throw new FcaUnavailableException("FCA Register credentials are not configured (FCA_AUTH_EMAIL/KEY)");
        }
        Supplier<JsonNode> call = () -> callOnce(operation, uri);
        try {
            return CircuitBreaker.decorateSupplier(breaker, RateLimiter.decorateSupplier(rateLimiter, call))
                    .get();
        } catch (CallNotPermittedException e) {
            throw new FcaUnavailableException("FCA Register circuit breaker is open; not calling", e);
        } catch (RequestNotPermitted e) {
            throw new FcaUnavailableException("Client-side rate limit for the FCA Register reached", e);
        }
    }

    private JsonNode callOnce(String operation, Function<UriBuilder, URI> uri) {
        long start = System.nanoTime();
        try {
            return rest.get().uri(uri).exchange((req, res) -> {
                int status = res.getStatusCode().value();
                record(operation, Integer.toString(status), start);
                if (!res.getStatusCode().is2xxSuccessful()) {
                    // The gateway answers 404 to bad credentials, so no 4xx can be read as "absent".
                    throw new FcaUnavailableException("FCA Register " + operation + " returned HTTP " + status);
                }
                JsonNode body = res.bodyTo(JsonNode.class);
                if (body == null || !body.isObject()) {
                    throw new FcaUnavailableException("FCA Register " + operation + " returned no JSON object");
                }
                return body;
            });
        } catch (ResourceAccessException e) {
            record(operation, "io_error", start);
            throw new FcaUnavailableException(
                    "FCA Register unreachable: "
                            + e.getMostSpecificCause().getClass().getSimpleName(),
                    e);
        } catch (org.springframework.web.client.RestClientException e) {
            // e.g. a non-JSON body (an HTML maintenance page) that cannot be converted
            throw new FcaUnavailableException("FCA Register " + operation + " response unreadable", e);
        }
    }

    private void record(String operation, String outcome, long startNanos) {
        long nanos = System.nanoTime() - startNanos;
        Timer.builder("kyb.external.calls")
                .description("Calls to external data sources")
                .tag("source", SOURCE)
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(meters)
                .record(nanos, TimeUnit.NANOSECONDS);
        log.info(
                "external call source={} operation={} status={} latencyMs={}",
                SOURCE,
                operation,
                outcome,
                TimeUnit.NANOSECONDS.toMillis(nanos));
    }
}
