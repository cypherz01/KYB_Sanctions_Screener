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
import com.cypherz01.kyb.ownership.ParentRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final String OWNERSHIP = "ownership";
    private static final String STATUS = "status";
    private static final String REASON = "reason";

    private final CompaniesHouseMapper mapper;
    private final FcaRegisterClient fca;

    public EvidenceSnapshots(CompaniesHouseMapper mapper, FcaRegisterClient fca) {
        this.mapper = mapper;
        this.fca = fca;
    }

    public List<SourceSnapshot> toSnapshots(CheckEvidence evidence) {
        FcaAssessment regulatory = evidence.regulatory();
        List<SourceSnapshot> snapshots = new ArrayList<>(List.of(
                companiesHouse(PROFILE, evidence.profile()),
                companiesHouse(OFFICERS, evidence.officers()),
                companiesHouse(PSCS, evidence.pscs()),
                companiesHouse(EXEMPTIONS, evidence.exemptions())));
        if (evidence.parents() != null) {
            snapshots.add(lookThroughMarker(evidence.parents()));
            evidence.parents().forEach((number, parent) -> snapshots.add(parentSnapshot(number, parent)));
        }
        snapshots.add(new SourceSnapshot(
                FcaRegisterClient.SOURCE,
                ASSESSMENT,
                regulatory.fetchedAt(),
                regulatory.fromCache(),
                regulatory.stale(),
                regulatory.payload()));
        return snapshots;
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
        return Optional.of(new CheckEvidence(
                profile.get(), officers.get(), pscs.get(), exemptions.get(), regulatory, parents(snapshots)));
    }

    /** Every look-through check has this, even with no parents, which is how an older check is told apart. */
    private static SourceSnapshot lookThroughMarker(Map<String, ParentRecord> parents) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        ArrayNode companies = payload.putArray("companies");
        parents.keySet().forEach(companies::add);
        return new SourceSnapshot(CompaniesHouseClient.SOURCE, OWNERSHIP, null, false, false, payload);
    }

    /** One row per parent, holding the raw answers, or why there were none. */
    private static SourceSnapshot parentSnapshot(String number, ParentRecord parent) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put(STATUS, parent.status().name());
        Instant fetchedAt = null;
        boolean fromCache = false;
        boolean stale = false;
        if (parent.status() == ParentRecord.Status.FOUND) {
            payload.set(PROFILE, parent.profile().payload());
            payload.set(PSCS, parent.pscs().payload());
            payload.set(EXEMPTIONS, parent.exemptions().payload());
            List<Sourced<?>> calls = List.of(parent.profile(), parent.pscs(), parent.exemptions());
            fetchedAt = calls.stream()
                    .map(Sourced::fetchedAt)
                    .min(Comparator.naturalOrder())
                    .orElseThrow();
            fromCache = calls.stream().allMatch(Sourced::fromCache);
            stale = calls.stream().anyMatch(Sourced::stale);
        } else if (parent.reason() != null) {
            payload.put(REASON, parent.reason());
        }
        return new SourceSnapshot(
                CompaniesHouseClient.SOURCE, OWNERSHIP + ":" + number, fetchedAt, fromCache, stale, payload);
    }

    /** @return the answers by company number, or null if the check predates look-through */
    private Map<String, ParentRecord> parents(List<SourceSnapshot> snapshots) {
        if (find(snapshots, CompaniesHouseClient.SOURCE, OWNERSHIP).isEmpty()) {
            return null;
        }
        Map<String, ParentRecord> parents = new LinkedHashMap<>();
        String prefix = OWNERSHIP + ":";
        for (SourceSnapshot s : snapshots) {
            if (s.source().equals(CompaniesHouseClient.SOURCE) && s.operation().startsWith(prefix)) {
                parents.put(s.operation().substring(prefix.length()), restoreParent(s));
            }
        }
        return parents;
    }

    private ParentRecord restoreParent(SourceSnapshot s) {
        JsonNode payload = s.payload();
        return switch (ParentRecord.Status.valueOf(payload.path(STATUS).asText())) {
            case NOT_FOUND -> ParentRecord.notFound();
            case UNAVAILABLE -> ParentRecord.unavailable(payload.path(REASON).asText());
            case FOUND ->
                ParentRecord.found(
                        restored(s, payload.path(PROFILE), mapper::toProfile),
                        restored(s, payload.path(PSCS), mapper::toPscs),
                        restored(s, payload.path(EXEMPTIONS), mapper::toExemptions));
        };
    }

    private static <T> Sourced<T> restored(SourceSnapshot s, JsonNode payload, Function<JsonNode, T> toValue) {
        return new Sourced<>(toValue.apply(payload), payload, s.fetchedAt(), s.fromCache(), s.stale());
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
            List<SourceSnapshot> snapshots, String operation, Function<JsonNode, T> toValue) {
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
