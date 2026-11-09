package com.cypherz01.kyb.check;

import com.cypherz01.kyb.cache.Sourced;
import com.cypherz01.kyb.companieshouse.model.CompanyProfile;
import com.cypherz01.kyb.companieshouse.model.Officer;
import com.cypherz01.kyb.companieshouse.model.Psc;
import com.cypherz01.kyb.companieshouse.model.PscExemptions;
import com.cypherz01.kyb.fca.model.FcaAssessment;
import com.cypherz01.kyb.ownership.ParentRecord;
import java.util.List;
import java.util.Map;

/**
 * What a check knows about a company from its sources, whether gathered live or from snapshots.
 *
 * @param parents what Companies House said about each parent company, by number, in the order they
 *     were looked up; null for a check made before ownership look-through existed, which never looked
 */
public record CheckEvidence(
        Sourced<CompanyProfile> profile,
        Sourced<List<Officer>> officers,
        Sourced<List<Psc>> pscs,
        Sourced<PscExemptions> exemptions,
        FcaAssessment regulatory,
        Map<String, ParentRecord> parents) {

    /** The registered office postcode, used to match the company to the FCA Register. May be null. */
    public static String postcodeOf(CompanyProfile company) {
        return company.registeredOffice() == null
                ? null
                : company.registeredOffice().postalCode();
    }
}
