package com.cypherz01.kyb.companieshouse.model;

import java.time.LocalDate;
import java.util.List;

/** The subset of the Companies House company profile that KYB rules care about. */
public record CompanyProfile(
        String companyNumber,
        String name,
        String status,
        String type,
        LocalDate incorporatedOn,
        Address registeredOffice,
        List<String> sicCodes,
        boolean accountsOverdue,
        boolean confirmationStatementOverdue,
        boolean hasInsolvencyHistory) {

    public CompanyProfile {
        sicCodes = sicCodes == null ? List.of() : List.copyOf(sicCodes);
    }
}
