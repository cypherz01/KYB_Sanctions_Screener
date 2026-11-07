package com.cypherz01.kyb.companieshouse;

import com.cypherz01.kyb.cache.CacheEntry;
import com.cypherz01.kyb.cache.SourceCache;
import com.cypherz01.kyb.cache.Sourced;
import com.cypherz01.kyb.companieshouse.model.CompanyProfile;
import com.cypherz01.kyb.companieshouse.model.Officer;
import com.cypherz01.kyb.companieshouse.model.Psc;
import com.cypherz01.kyb.companieshouse.model.PscExemptions;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Companies House facade used by the rest of the service. Cache first (TTL from config). On a
 * miss, a live call through {@link CompaniesHouseApi}. If Companies House is unavailable and an
 * expired entry exists, that entry is served with {@code stale=true} rather than failing the check.
 */
public class CompaniesHouseClient {

    public static final String SOURCE = "companies-house";
    private static final Logger log = LoggerFactory.getLogger(CompaniesHouseClient.class);

    private final CompaniesHouseApi api;
    private final SourceCache cache;
    private final CompaniesHouseMapper mapper;
    private final CompaniesHouseProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;

    public CompaniesHouseClient(
            CompaniesHouseApi api,
            SourceCache cache,
            CompaniesHouseMapper mapper,
            CompaniesHouseProperties properties,
            Clock clock,
            MeterRegistry meters) {
        this.api = api;
        this.cache = cache;
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * @throws InvalidCompanyNumberException malformed input
     * @throws CompanyNotFoundException Companies House returned 404
     * @throws CompaniesHouseUnavailableException unavailable and nothing cached
     */
    public Sourced<CompanyProfile> getProfile(String companyNumber) {
        CompanyNumber n = CompanyNumber.parse(companyNumber);
        return fetch("profile", n, () -> api.fetchProfile(n).orElseThrow(() -> new CompanyNotFoundException(n)))
                .map(mapper::toProfile);
    }

    public Sourced<List<Officer>> getOfficers(String companyNumber) {
        CompanyNumber n = CompanyNumber.parse(companyNumber);
        return fetch("officers", n, () -> api.fetchOfficers(n)).map(mapper::toOfficers);
    }

    public Sourced<List<Psc>> getPscs(String companyNumber) {
        CompanyNumber n = CompanyNumber.parse(companyNumber);
        return fetch("pscs", n, () -> api.fetchPscs(n)).map(mapper::toPscs);
    }

    /** PSC-register exemptions; a 404 (none) is cached like any other answer. */
    public Sourced<PscExemptions> getExemptions(String companyNumber) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    private Sourced<JsonNode> fetch(String operation, CompanyNumber number, Supplier<JsonNode> live) {
        String key = SOURCE + ":" + operation + ":" + number.value();
        Instant now = clock.instant();
        Optional<CacheEntry> cached = cache.find(key);

        if (cached.isPresent() && cached.get().isFresh(now)) {
            countCache("hit");
            return fromEntry(cached.get(), false);
        }
        countCache("miss");

        try {
            JsonNode payload = live.get();
            cache.put(new CacheEntry(key, SOURCE, payload, now, now.plus(properties.cacheTtl())));
            return new Sourced<>(payload, payload, now, false, false);
        } catch (CompaniesHouseUnavailableException e) {
            // Only outages fall back. CompanyNotFoundException is an answer and propagates untouched.
            if (cached.isEmpty()) {
                throw e;
            }
            countCache("stale");
            log.warn(
                    "source={} operation={} unavailable ({}); serving stale cache fetched at {}",
                    SOURCE,
                    operation,
                    e.getMessage(),
                    cached.get().fetchedAt());
            return fromEntry(cached.get(), true);
        }
    }

    private static Sourced<JsonNode> fromEntry(CacheEntry entry, boolean stale) {
        return new Sourced<>(entry.payload(), entry.payload(), entry.fetchedAt(), true, stale);
    }

    private void countCache(String result) {
        meters.counter("kyb.cache.requests", "source", SOURCE, "result", result).increment();
    }
}
