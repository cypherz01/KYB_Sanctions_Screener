package com.cypherz01.kyb.companieshouse;

/** Input is not a syntactically valid company number (maps to HTTP 400 in M5). */
public class InvalidCompanyNumberException extends RuntimeException {

    public InvalidCompanyNumberException(String raw) {
        super("Invalid company number format: '" + raw + "'");
    }
}
