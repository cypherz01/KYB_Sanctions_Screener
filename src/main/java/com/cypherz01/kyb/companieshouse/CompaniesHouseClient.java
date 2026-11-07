package com.cypherz01.kyb.companieshouse;

import com.cypherz01.kyb.cache.SourceCache;
import com.cypherz01.kyb.cache.Sourced;
import com.cypherz01.kyb.companieshouse.model.CompanyProfile;
import com.cypherz01.kyb.companieshouse.model.Officer;
import com.cypherz01.kyb.companieshouse.model.Psc;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;

/**
 * The Companies House facade used by the rest of the service. Cache first (TTL from config). On a
 * miss, a live call through {@link CompaniesHouseApi}. If Companies House is unavailable and an
 * expired entry exists, that entry is served with {@code stale=true} rather than failing the check.
 */
public class CompaniesHouseClient {

    public static final String SOURCE = "companies-house";

    public CompaniesHouseClient(
            CompaniesHouseApi api,
            SourceCache cache,
            CompaniesHouseMapper mapper,
            CompaniesHouseProperties properties,
            Clock clock,
            MeterRegistry meters) {}

    /**
     * @throws InvalidCompanyNumberException malformed input
     * @throws CompanyNotFoundException Companies House returned 404
     * @throws CompaniesHouseUnavailableException unavailable and nothing cached
     */
    public Sourced<CompanyProfile> getProfile(String companyNumber) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public Sourced<List<Officer>> getOfficers(String companyNumber) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public Sourced<List<Psc>> getPscs(String companyNumber) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
