package com.cypherz01.kyb.ownership;

import com.cypherz01.kyb.cache.Sourced;
import com.cypherz01.kyb.companieshouse.model.CompanyProfile;
import com.cypherz01.kyb.companieshouse.model.Psc;
import com.cypherz01.kyb.companieshouse.model.PscExemptions;
import java.util.List;

/**
 * What Companies House said about one parent company during a check. "Not found" and "unavailable"
 * are answers too: they decide the result, so they are recorded and replayed like any other.
 *
 * @param reason why the parent was unavailable, otherwise null
 */
public record ParentRecord(
        Status status,
        Sourced<CompanyProfile> profile,
        Sourced<List<Psc>> pscs,
        Sourced<PscExemptions> exemptions,
        String reason) {

    public enum Status {
        FOUND,
        NOT_FOUND,
        UNAVAILABLE
    }

    public static ParentRecord found(
            Sourced<CompanyProfile> profile, Sourced<List<Psc>> pscs, Sourced<PscExemptions> exemptions) {
        return new ParentRecord(Status.FOUND, profile, pscs, exemptions, null);
    }

    public static ParentRecord notFound() {
        return new ParentRecord(Status.NOT_FOUND, null, null, null, null);
    }

    public static ParentRecord unavailable(String reason) {
        return new ParentRecord(Status.UNAVAILABLE, null, null, null, reason);
    }
}
