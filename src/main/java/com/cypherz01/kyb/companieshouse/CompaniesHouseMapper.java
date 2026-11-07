package com.cypherz01.kyb.companieshouse;

import com.cypherz01.kyb.companieshouse.dto.CompaniesHouseDtos.AddressDto;
import com.cypherz01.kyb.companieshouse.dto.CompaniesHouseDtos.DateOfBirthDto;
import com.cypherz01.kyb.companieshouse.dto.CompaniesHouseDtos.OfficerDto;
import com.cypherz01.kyb.companieshouse.dto.CompaniesHouseDtos.OfficerListDto;
import com.cypherz01.kyb.companieshouse.dto.CompaniesHouseDtos.OverdueDto;
import com.cypherz01.kyb.companieshouse.dto.CompaniesHouseDtos.ProfileDto;
import com.cypherz01.kyb.companieshouse.dto.CompaniesHouseDtos.PscDto;
import com.cypherz01.kyb.companieshouse.dto.CompaniesHouseDtos.PscListDto;
import com.cypherz01.kyb.companieshouse.model.Address;
import com.cypherz01.kyb.companieshouse.model.CompanyProfile;
import com.cypherz01.kyb.companieshouse.model.Officer;
import com.cypherz01.kyb.companieshouse.model.Psc;
import com.cypherz01.kyb.companieshouse.model.PscExemptions;
import com.cypherz01.kyb.companieshouse.model.PscKind;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/** Maps raw Companies House JSON to the domain model. Pure and side-effect free. */
public class CompaniesHouseMapper {

    private final ObjectMapper objectMapper;

    public CompaniesHouseMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CompanyProfile toProfile(JsonNode json) {
        ProfileDto d = read(json, ProfileDto.class);
        return new CompanyProfile(
                d.companyNumber(),
                d.companyName(),
                d.companyStatus(),
                d.type(),
                d.dateOfCreation(),
                toAddress(d.registeredOfficeAddress()),
                d.sicCodes(),
                isOverdue(d.accounts()),
                isOverdue(d.confirmationStatement()),
                Boolean.TRUE.equals(d.hasInsolvencyHistory()));
    }

    public List<Officer> toOfficers(JsonNode json) {
        OfficerListDto list = read(json, OfficerListDto.class);
        if (list.items() == null) {
            return List.of();
        }
        return list.items().stream().map(CompaniesHouseMapper::toOfficer).toList();
    }

    /**
     * @return the exemption types with at least one item lacking {@code exempt_to}; {@code {}} (how
     *     a cached 404 is stored) means none
     */
    public PscExemptions toExemptions(JsonNode json) {
        List<String> current = new ArrayList<>();
        json.path("exemptions").properties().forEach(e -> {
            boolean inForce = false;
            for (JsonNode item : e.getValue().path("items")) {
                if (item.path("exempt_to").isMissingNode()
                        || item.path("exempt_to").isNull()) {
                    inForce = true;
                }
            }
            if (inForce) {
                String type = e.getValue().path("exemption_type").asText("");
                current.add(type.isBlank() ? e.getKey().replace('_', '-') : type);
            }
        });
        return current.isEmpty() ? PscExemptions.NONE : new PscExemptions(current);
    }

    public List<Psc> toPscs(JsonNode json) {
        PscListDto list = read(json, PscListDto.class);
        if (list.items() == null) {
            return List.of();
        }
        return list.items().stream().map(CompaniesHouseMapper::toPsc).toList();
    }

    public static PscKind kindOf(String companiesHouseKind) {
        if (companiesHouseKind == null) {
            return PscKind.UNKNOWN;
        }
        // Order matters only for readability: the prefixes are disjoint.
        if (companiesHouseKind.startsWith("super-secure-")) {
            return PscKind.SUPER_SECURE;
        }
        if (companiesHouseKind.startsWith("individual-")) {
            return PscKind.INDIVIDUAL;
        }
        if (companiesHouseKind.startsWith("corporate-entity-")) {
            return PscKind.CORPORATE;
        }
        if (companiesHouseKind.startsWith("legal-person-")) {
            return PscKind.LEGAL_PERSON;
        }
        return PscKind.UNKNOWN;
    }

    private static Officer toOfficer(OfficerDto o) {
        return new Officer(
                o.name(),
                o.officerRole(),
                o.officerRole() != null && o.officerRole().startsWith("corporate-"),
                o.appointedOn(),
                o.resignedOn(),
                o.nationality(),
                o.countryOfResidence(),
                toYearMonth(o.dateOfBirth()));
    }

    private static Psc toPsc(PscDto p) {
        return new Psc(
                p.name(),
                kindOf(p.kind()),
                p.nationality(),
                p.countryOfResidence(),
                toYearMonth(p.dateOfBirth()),
                p.notifiedOn(),
                p.ceasedOn(),
                p.identification() == null ? null : p.identification().countryRegistered(),
                p.naturesOfControl());
    }

    private static Address toAddress(AddressDto a) {
        if (a == null) {
            return null;
        }
        return new Address(a.addressLine1(), a.addressLine2(), a.locality(), a.region(), a.postalCode(), a.country());
    }

    private static boolean isOverdue(OverdueDto d) {
        return d != null && Boolean.TRUE.equals(d.overdue());
    }

    private static YearMonth toYearMonth(DateOfBirthDto dob) {
        if (dob == null || dob.year() == null || dob.month() == null) {
            return null;
        }
        return YearMonth.of(dob.year(), dob.month());
    }

    private <T> T read(JsonNode json, Class<T> type) {
        try {
            return objectMapper.treeToValue(json, type);
        } catch (JsonProcessingException e) {
            // Upstream broke its documented contract: treat as unavailable rather than crash the check.
            throw new CompaniesHouseUnavailableException(
                    "Unexpected Companies House response format for " + type.getSimpleName(), e);
        }
    }
}
