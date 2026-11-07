package com.cypherz01.kyb.companieshouse;

import com.cypherz01.kyb.config.BackoffPolicy;
import com.cypherz01.kyb.config.RetryAfterHeaders;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.micrometer.tagged.TaggedRateLimiterMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/**
 * Raw HTTP access to Companies House: auth, client-side rate limiting, retry with backoff,
 * pagination, metrics and call logging. Returns raw JSON; no caching or mapping here.
 *
 * <p>Decorator order is retry(rateLimiter(call)): every attempt, retries included, takes a permit,
 * so retries can never push us over the upstream limit.
 */
public class CompaniesHouseApi {

    private static final Logger log = LoggerFactory.getLogger(CompaniesHouseApi.class);
    private static final String SOURCE = CompaniesHouseClient.SOURCE;
    private static final String PROFILE = "/company/{number}";
    private static final String OFFICERS = "/company/{number}/officers";
    private static final String PSCS = "/company/{number}/persons-with-significant-control";
    private static final String EXEMPTIONS = "/company/{number}/exemptions";

    private final RestClient rest;
    private final CompaniesHouseProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;
    private final RateLimiter rateLimiter;
    private final Retry retry;

    public CompaniesHouseApi(
            RestClient restClient,
            CompaniesHouseProperties properties,
            Clock clock,
            MeterRegistry meters,
            DoubleSupplier jitter) {
        this.rest = restClient;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;

        CompaniesHouseProperties.RateLimit rl = properties.rateLimit();
        RateLimiterRegistry rateLimiters = RateLimiterRegistry.of(RateLimiterConfig.custom()
                .limitForPeriod(rl.limit())
                .limitRefreshPeriod(rl.period())
                .timeoutDuration(rl.maxWait())
                .build());
        this.rateLimiter = rateLimiters.rateLimiter(SOURCE);

        BackoffPolicy backoff = new BackoffPolicy(
                properties.retry().baseDelay(), properties.retry().maxDelay(), jitter);
        RetryRegistry retries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(properties.retry().maxRetries() + 1)
                .retryOnException(RetryableUpstreamException.class::isInstance)
                .intervalBiFunction((Integer attempt, Either<Throwable, Object> outcome) ->
                        backoff.delayFor(attempt, hintFrom(outcome)).toMillis())
                .build());
        this.retry = retries.retry(SOURCE);

        TaggedRateLimiterMetrics.ofRateLimiterRegistry(rateLimiters).bindTo(meters);
        TaggedRetryMetrics.ofRetryRegistry(retries).bindTo(meters);
    }

    /** Builds a RestClient with base URL, timeouts and Basic auth (API key as username). */
    public static RestClient buildRestClient(RestClient.Builder builder, CompaniesHouseProperties properties) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());

        String apiKey = properties.apiKey();
        return builder.baseUrl(properties.baseUrl().toString())
                .requestFactory(factory)
                .defaultHeaders(h -> {
                    h.setAccept(List.of(MediaType.APPLICATION_JSON));
                    if (apiKey != null && !apiKey.isBlank()) {
                        // Companies House: key is the username, password is ignored (left blank).
                        h.setBasicAuth(apiKey, "");
                    }
                })
                .build();
    }

    /** @return the profile JSON, or empty if Companies House returns 404 */
    public Optional<JsonNode> fetchProfile(CompanyNumber number) {
        return execute("profile", b -> b.path(PROFILE).build(number.value()));
    }

    /** @return all pages merged into one list object; {@code items} is empty on 404 */
    public JsonNode fetchOfficers(CompanyNumber number) {
        return fetchAllPages("officers", OFFICERS, number, false);
    }

    /** @return all pages merged into one list object; {@code items} is empty on 404 */
    public JsonNode fetchPscs(CompanyNumber number) {
        // register_view is documented as required on this endpoint (and optional on officers).
        return fetchAllPages("pscs", PSCS, number, true);
    }

    /** @return the exemptions JSON, or empty on 404 (Companies House: the company has none) */
    public Optional<JsonNode> fetchExemptions(CompanyNumber number) {
        return execute("exemptions", b -> b.path(EXEMPTIONS).build(number.value()));
    }

    private JsonNode fetchAllPages(String operation, String path, CompanyNumber number, boolean sendRegisterView) {
        ObjectNode merged = null;
        ArrayNode items = JsonNodeFactory.instance.arrayNode();
        int startIndex = 0;
        int pages = 0;
        boolean complete = false;

        while (pages < properties.maxPages()) {
            final int start = startIndex;
            Optional<JsonNode> page = execute(operation, b -> {
                b.path(path)
                        .queryParam("items_per_page", properties.itemsPerPage())
                        .queryParam("start_index", start);
                if (sendRegisterView) {
                    b.queryParam("register_view", false);
                }
                return b.build(number.value());
            });
            pages++;

            if (page.isEmpty() || !page.get().isObject()) {
                complete = true; // 404: no list for this company
                break;
            }
            if (merged == null) {
                merged = ((ObjectNode) page.get()).deepCopy(); // keep counts/links from page 1
            }
            JsonNode pageItems = page.get().path("items");
            if (!pageItems.isArray() || pageItems.isEmpty()) {
                complete = true; // guard: upstream claims more results but sends none
                break;
            }
            items.addAll((ArrayNode) pageItems);
            startIndex += pageItems.size();
            if (startIndex >= page.get().path("total_results").asInt(0)) {
                complete = true;
                break;
            }
        }
        if (!complete) {
            log.warn(
                    "source={} operation={} truncated after {} pages ({} items)",
                    SOURCE,
                    operation,
                    pages,
                    items.size());
        }

        ObjectNode result = merged != null ? merged : JsonNodeFactory.instance.objectNode();
        result.set("items", items);
        return result;
    }

    /** One logical request: rate-limited, retried, translated to our exception types. */
    private Optional<JsonNode> execute(String operation, Function<UriBuilder, URI> uri) {
        Supplier<Optional<JsonNode>> call = () -> callOnce(operation, uri);
        Supplier<Optional<JsonNode>> decorated =
                Retry.decorateSupplier(retry, RateLimiter.decorateSupplier(rateLimiter, call));
        try {
            return decorated.get();
        } catch (RequestNotPermitted e) {
            throw new CompaniesHouseUnavailableException(
                    "Client-side rate limit for Companies House reached; request not sent", e);
        } catch (RetryableUpstreamException e) {
            throw new CompaniesHouseUnavailableException(
                    "Companies House unavailable after " + (properties.retry().maxRetries() + 1) + " attempts ("
                            + e.getMessage() + ")",
                    e);
        }
    }

    /** A single HTTP attempt. Logs and times it, then classifies the outcome. */
    private Optional<JsonNode> callOnce(String operation, Function<UriBuilder, URI> uri) {
        long start = System.nanoTime();
        Response response;
        try {
            response = rest.get().uri(uri).exchange((req, res) -> {
                HttpStatusCode status = res.getStatusCode();
                if (status.is2xxSuccessful()) {
                    return new Response(status, res.bodyTo(JsonNode.class), null);
                }
                return new Response(
                        status,
                        null,
                        RetryAfterHeaders.parse(res.getHeaders(), clock).orElse(null));
            });
        } catch (ResourceAccessException e) {
            record(operation, "io_error", start);
            throw new RetryableUpstreamException("I/O error: " + e.getClass().getSimpleName(), null);
        }
        record(operation, Integer.toString(response.status().value()), start);

        HttpStatusCode status = response.status();
        if (status.is2xxSuccessful()) {
            return Optional.ofNullable(response.body());
        }
        if (status.value() == 404) {
            return Optional.empty();
        }
        if (status.value() == 429 || status.is5xxServerError()) {
            throw new RetryableUpstreamException("HTTP " + status.value(), response.retryAfter());
        }
        // 401/403/other 4xx: retrying cannot help (e.g. bad API key).
        throw new CompaniesHouseUnavailableException("Companies House rejected the request: HTTP " + status.value());
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

    private static Optional<Duration> hintFrom(Either<Throwable, Object> outcome) {
        if (outcome.isLeft() && outcome.getLeft() instanceof RetryableUpstreamException e) {
            return e.retryAfter();
        }
        return Optional.empty();
    }

    private record Response(HttpStatusCode status, JsonNode body, Duration retryAfter) {}
}
