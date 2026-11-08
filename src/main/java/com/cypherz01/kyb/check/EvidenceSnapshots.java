package com.cypherz01.kyb.check;

import com.cypherz01.kyb.audit.SourceSnapshot;
import com.cypherz01.kyb.cache.Sourced;
import com.cypherz01.kyb.companieshouse.CompaniesHouseClient;
import com.cypherz01.kyb.companieshouse.CompaniesHouseMapper;
import com.cypherz01.kyb.companieshouse.model.CompanyProfile;
import com.cypherz01.kyb.companieshouse.model.Officer;
import com.cypherz01.kyb.companieshouse.model.Psc;
import com.cypherz01.kyb.companieshouse.model.PscExemptions;
import com.cypherz01.kyb.fca.FcaRegisterClient;
import com.cypherz01.kyb.fca.FcaSnapshot;
import com.cypherz01.kyb.fca.model.FcaAssessment;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Converts a check's evidence to the snapshots stored with it, and back. Both directions live here
 * so they cannot disagree. Restoring runs the same mappers and FCA matching as the live path.
 */
public class EvidenceSnapshots {

    private static final String PROFILE = "profile";
    private static final String OFFICERS = "officers";
    private static final String PSCS = "pscs";
    private static final String EXEMPTIONS = "exemptions";
    private static final String ASSESSMENT = "assessment";

    private final CompaniesHouseMapper mapper;
    private final FcaRegisterClient fca;

    public EvidenceSnapshots(CompaniesHouseMapper mapper, FcaRegisterClient fca) {
        this.mapper = mapper;
        this.fca = fca;
    }

    public List<SourceSnapshot> toSnapshots(CheckEvidence evidence) {
        FcaAssessment regulatory = evidence.regulatory();
        return List.of(
                companiesHouse(PROFILE, evidence.profile()),
                companiesHouse(OFFICERS, evidence.officers()),
                companiesHouse(PSCS, evidence.pscs()),
                companiesHouse(EXEMPTIONS, evidence.exemptions()),
                new SourceSnapshot(
                        FcaRegisterClient.SOURCE,
                        ASSESSMENT,
                        regulatory.fetchedAt(),
                        regulatory.fromCache(),
                        regulatory.stale(),
                        regulatory.payload()));
    }

    /** @return the evidence, or empty if any snapshot is missing */
    public Optional<CheckEvidence> restore(String companyNumber, List<SourceSnapshot> snapshots) {
        Optional<Sourced<CompanyProfile>> profile = companiesHouse(snapshots, PROFILE, mapper::toProfile);
        Optional<Sourced<List<Officer>>> officers = companiesHouse(snapshots, OFFICERS, mapper::toOfficers);
        Optional<Sourced<List<Psc>>> pscs = companiesHouse(snapshots, PSCS, mapper::toPscs);
        Optional<Sourced<PscExemptions>> exemptions = companiesHouse(snapshots, EXEMPTIONS, mapper::toExemptions);
        Optional<SourceSnapshot> assessment = find(snapshots, FcaRegisterClient.SOURCE, ASSESSMENT);
        if (profile.isEmpty() || officers.isEmpty() || pscs.isEmpty() || exemptions.isEmpty() || assessment.isEmpty()) {
            return Optional.empty();
        }
        CompanyProfile company = profile.get().value();
        SourceSnapshot s = assessment.get();
        FcaAssessment regulatory = fca.reassess(
                company.name(),
                companyNumber,
                CheckEvidence.postcodeOf(company),
                new FcaSnapshot(s.payload(), s.fetchedAt(), s.fromCache(), s.stale()));
        return Optional.of(new CheckEvidence(profile.get(), officers.get(), pscs.get(), exemptions.get(), regulatory));
    }

    private static SourceSnapshot companiesHouse(String operation, Sourced<?> sourced) {
        return new SourceSnapshot(
                CompaniesHouseClient.SOURCE,
                operation,
                sourced.fetchedAt(),
                sourced.fromCache(),
                sourced.stale(),
                sourced.payload());
    }

    private static <T> Optional<Sourced<T>> companiesHouse(
            List<SourceSnapshot> snapshots,
            String operation,
            Function<com.fasterxml.jackson.databind.JsonNode, T> toValue) {
        return find(snapshots, CompaniesHouseClient.SOURCE, operation)
                .map(s -> new Sourced<>(
                        toValue.apply(s.payload()), s.payload(), s.fetchedAt(), s.fromCache(), s.stale()));
    }

    private static Optional<SourceSnapshot> find(List<SourceSnapshot> snapshots, String source, String operation) {
        return snapshots.stream()
                .filter(s -> s.source().equals(source) && s.operation().equals(operation))
                .findFirst();
    }
}
