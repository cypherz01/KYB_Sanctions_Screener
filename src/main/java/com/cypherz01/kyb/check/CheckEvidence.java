package com.cypherz01.kyb.check;

import com.cypherz01.kyb.cache.Sourced;
import com.cypherz01.kyb.companieshouse.model.CompanyProfile;
import com.cypherz01.kyb.companieshouse.model.Officer;
import com.cypherz01.kyb.companieshouse.model.Psc;
import com.cypherz01.kyb.companieshouse.model.PscExemptions;
import com.cypherz01.kyb.fca.model.FcaAssessment;
import java.util.List;

/** What a check knows about a company from its sources, whether gathered live or from snapshots. */
public record CheckEvidence(
        Sourced<CompanyProfile> profile,
        Sourced<List<Officer>> officers,
        Sourced<List<Psc>> pscs,
        Sourced<PscExemptions> exemptions,
        FcaAssessment regulatory) {

    /** The registered office postcode, used to match the company to the FCA Register. May be null. */
    public static String postcodeOf(CompanyProfile company) {
        return company.registeredOffice() == null
                ? null
                : company.registeredOffice().postalCode();
    }
}
