package com.cypherz01.kyb.companieshouse;

/**
 * A validated, canonical Companies House company number: 8 characters, either all digits or a
 * two-character prefix (e.g. SC, NI, OC) followed by 6 digits.
 */
public record CompanyNumber(String value) {

    /**
     * Parses user input: trims, upper-cases, and left-pads purely numeric input with zeros (users
     * often drop the leading zero of e.g. {@code 09446231}).
     *
     * @throws InvalidCompanyNumberException if the input cannot be a company number
     */
    public static CompanyNumber parse(String raw) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public String toString() {
        return value;
    }
}
