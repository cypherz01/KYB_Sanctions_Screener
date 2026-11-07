package com.cypherz01.kyb.companieshouse;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A validated, canonical Companies House company number: 8 characters, either all digits or a
 * two-character prefix (e.g. SC, NI, OC) followed by 6 digits.
 */
public record CompanyNumber(String value) {

    private static final Pattern CANONICAL = Pattern.compile("[A-Z0-9]{2}\\d{6}");
    private static final Pattern DIGITS_ONLY = Pattern.compile("\\d{1,8}");

    public CompanyNumber {
        if (value == null || !CANONICAL.matcher(value).matches()) {
            throw new InvalidCompanyNumberException(value);
        }
    }

    /**
     * Parses user input: trims, upper-cases, and left-pads purely numeric input with zeros (users
     * often drop the leading zero of e.g. {@code 09446231}).
     *
     * @throws InvalidCompanyNumberException if the input cannot be a company number
     */
    public static CompanyNumber parse(String raw) {
        if (raw == null) {
            throw new InvalidCompanyNumberException(null);
        }
        String candidate = raw.trim().toUpperCase(Locale.ROOT);
        if (DIGITS_ONLY.matcher(candidate).matches()) {
            candidate = "0".repeat(8 - candidate.length()) + candidate;
        }
        return new CompanyNumber(candidate);
    }

    @Override
    public String toString() {
        return value;
    }
}
