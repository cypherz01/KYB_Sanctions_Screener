package com.cypherz01.kyb.companieshouse;

/**
 * Companies House has no company with this number. A normal business outcome, not a service
 * failure (maps to HTTP 404 in M5).
 */
public class CompanyNotFoundException extends RuntimeException {

    private final CompanyNumber companyNumber;

    public CompanyNotFoundException(CompanyNumber companyNumber) {
        super("Company not found: " + companyNumber);
        this.companyNumber = companyNumber;
    }

    public CompanyNumber companyNumber() {
        return companyNumber;
    }
}
