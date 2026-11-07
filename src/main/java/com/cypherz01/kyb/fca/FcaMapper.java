package com.cypherz01.kyb.fca;

import com.cypherz01.kyb.companieshouse.CompanyNumber;
import com.cypherz01.kyb.companieshouse.InvalidCompanyNumberException;
import com.cypherz01.kyb.fca.model.FcaFirmDetails;
import com.cypherz01.kyb.fca.model.FcaSearchHit;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps the Register's JSON envelope ({@code Status}, {@code Message}, {@code ResultInfo}, {@code
 * Data}) to domain records. Field names have spaces ({@code "Reference Number"}), so they are read
 * by name rather than through bound DTOs. Unknown fields are ignored.
 */
public class FcaMapper {

    /** The Register appends the postcode to firm names in search results. */
    private static final Pattern POSTCODE_SUFFIX = Pattern.compile("^(.*?)\\s*\\(Postcode:\\s*([^)]*)\\)\\s*$");

    /** @return firm hits from a search envelope; empty when {@code Data} is missing or null */
    public List<FcaSearchHit> toSearchHits(JsonNode envelope) {
        List<FcaSearchHit> hits = new ArrayList<>();
        for (JsonNode d : envelope.path("Data")) {
            String name = text(d, "Name");
            String postcode = null;
            if (name != null) {
                Matcher m = POSTCODE_SUFFIX.matcher(name);
                if (m.matches()) {
                    name = m.group(1).trim();
                    postcode = postcode(m.group(2));
                }
            }
            hits.add(new FcaSearchHit(
                    text(d, "Reference Number"),
                    name,
                    postcode,
                    text(d, "Status"),
                    text(d, "Type of business or Individual")));
        }
        return hits;
    }

    /** @return the first {@code Data} entry of a {@code /Firm/{FRN}} envelope, if any */
    public Optional<FcaFirmDetails> toFirmDetails(JsonNode envelope) {
        JsonNode d = envelope.path("Data").path(0);
        if (!d.isObject()) {
            return Optional.empty();
        }
        return Optional.of(new FcaFirmDetails(
                text(d, "FRN"),
                text(d, "Organisation Name"),
                text(d, "Status"),
                text(d, "Status Effective Date"),
                text(d, "Business Type"),
                companiesHouseNumber(text(d, "Companies House Number"))));
    }

    /** The real Register writes "N/A" or "n/a" when it has no postcode (seen in the smoke run). */
    private static String postcode(String published) {
        String p = blankToNull(published);
        return p == null || p.equalsIgnoreCase("n/a") || p.equalsIgnoreCase("na") ? null : p;
    }

    private static String companiesHouseNumber(String published) {
        if (published == null) {
            return null;
        }
        try {
            return CompanyNumber.parse(published).value();
        } catch (InvalidCompanyNumberException e) {
            return published.toUpperCase(Locale.ROOT); // kept as published; compared verbatim
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isValueNode() && !v.isNull() ? blankToNull(v.asText()) : null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
