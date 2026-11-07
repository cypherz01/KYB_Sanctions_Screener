package com.cypherz01.kyb.companieshouse.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.LocalDate;
import java.util.List;

/**
 * Wire-format records mirroring the Companies House JSON (snake_case). Only the fields we use are
 * declared; everything else is ignored so upstream additions never break deserialisation. Kept
 * separate from the domain model so API changes are absorbed in {@code CompaniesHouseMapper}.
 */
public final class CompaniesHouseDtos {

    private CompaniesHouseDtos() {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProfileDto(
            String companyNumber,
            String companyName,
            String companyStatus,
            String type,
            LocalDate dateOfCreation,
            AddressDto registeredOfficeAddress,
            List<String> sicCodes,
            OverdueDto accounts,
            OverdueDto confirmationStatement,
            Boolean hasInsolvencyHistory) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AddressDto(
            String addressLine1,
            String addressLine2,
            String locality,
            String region,
            String postalCode,
            String country) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OverdueDto(Boolean overdue) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DateOfBirthDto(Integer month, Integer year) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OfficerListDto(List<OfficerDto> items, Integer totalResults) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OfficerDto(
            String name,
            String officerRole,
            LocalDate appointedOn,
            LocalDate resignedOn,
            String nationality,
            String countryOfResidence,
            DateOfBirthDto dateOfBirth) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PscListDto(List<PscDto> items, Integer totalResults) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PscDto(
            String name,
            String kind,
            String nationality,
            String countryOfResidence,
            DateOfBirthDto dateOfBirth,
            LocalDate notifiedOn,
            LocalDate ceasedOn,
            IdentificationDto identification,
            List<String> naturesOfControl) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IdentificationDto(
            String countryRegistered, String placeRegistered, String legalForm, String registrationNumber) {}
}
