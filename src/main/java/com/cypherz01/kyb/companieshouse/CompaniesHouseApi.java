package com.cypherz01.kyb.companieshouse;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Optional;
import java.util.function.DoubleSupplier;
import org.springframework.web.client.RestClient;

/**
 * Raw HTTP access to Companies House: auth, client-side rate limiting, retry with backoff,
 * pagination, metrics and call logging. Returns raw JSON; no caching or mapping here.
 */
public class CompaniesHouseApi {

    public CompaniesHouseApi(
            RestClient restClient,
            CompaniesHouseProperties properties,
            Clock clock,
            MeterRegistry meters,
            DoubleSupplier jitter) {}

    /** Builds a RestClient with base URL, timeouts and Basic auth (API key as username). */
    public static RestClient buildRestClient(RestClient.Builder builder, CompaniesHouseProperties properties) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    /** @return the profile JSON, or empty if Companies House returns 404 */
    public Optional<JsonNode> fetchProfile(CompanyNumber number) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    /** @return all pages merged into one list object; {@code items} is empty on 404 */
    public JsonNode fetchOfficers(CompanyNumber number) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    /** @return all pages merged into one list object; {@code items} is empty on 404 */
    public JsonNode fetchPscs(CompanyNumber number) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
