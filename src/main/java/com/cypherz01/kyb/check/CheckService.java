package com.cypherz01.kyb.check;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * Runs a KYB check (spec §4 CheckOrchestrator). It fetches the Companies House profile, officers,
 * PSCs and exemptions, pins the active sanctions dataset version, assesses the FCA Register,
 * screens every subject against that one version, scores, stores the result append-only, and
 * returns it.
 */
public class CheckService {

    /**
     * @throws com.cypherz01.kyb.companieshouse.InvalidCompanyNumberException 400
     * @throws com.cypherz01.kyb.companieshouse.CompanyNotFoundException 404
     * @throws com.cypherz01.kyb.companieshouse.CompaniesHouseUnavailableException 503 (nothing cached)
     * @throws com.cypherz01.kyb.matching.SanctionsDataUnavailableException 503 (no dataset)
     */
    public CheckResponse run(String companyNumber) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    /**
     * @return the stored response, exactly as it was returned when the check ran
     * @throws CheckNotFoundException unknown id
     */
    public JsonNode find(UUID checkId) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
