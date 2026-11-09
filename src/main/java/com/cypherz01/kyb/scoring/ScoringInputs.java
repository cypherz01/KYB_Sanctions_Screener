package com.cypherz01.kyb.scoring;

import com.cypherz01.kyb.companieshouse.model.CompanyProfile;
import com.cypherz01.kyb.companieshouse.model.Officer;
import com.cypherz01.kyb.companieshouse.model.Psc;
import com.cypherz01.kyb.companieshouse.model.PscExemptions;
import com.cypherz01.kyb.fca.model.FcaAssessment;
import com.cypherz01.kyb.ownership.ChainStop;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the rules look at, and nothing else, so scoring is a pure function of its inputs:
 * the same inputs always give the same decision (M6 replay).
 *
 * @param officers all officers returned, including resigned ones (officer churn counts both)
 * @param pscs all PSCs returned, including ceased ones
 * @param datasetVersion label of the sanctions dataset version the subjects were screened against
 * @param asOf the check date, for age and churn rules (never "today" at scoring time)
 * @param ownershipStops where the company's ownership chains stopped before reaching a natural end
 */
public record ScoringInputs(
        CompanyProfile profile,
        List<Officer> officers,
        List<Psc> pscs,
        PscExemptions exemptions,
        SourceInfo companiesHouse,
        FcaAssessment fca,
        List<SubjectScreening> screenings,
        String datasetVersion,
        LocalDate asOf,
        List<ChainStop> ownershipStops) {}
