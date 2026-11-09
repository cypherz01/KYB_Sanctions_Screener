package com.cypherz01.kyb.ownership;

import com.cypherz01.kyb.cache.Sourced;
import com.cypherz01.kyb.companieshouse.model.CompanyProfile;
import com.cypherz01.kyb.companieshouse.model.Psc;
import com.cypherz01.kyb.companieshouse.model.PscExemptions;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * What Companies House said about one parent company during a check. "Not found" and "unavailable"
 * are answers too: they decide the result, so they are recorded and replayed like any other.
 *
 * <p>A found parent always has its PSCs. Its profile and exemptions can be null if those calls alone
 * failed, as losing either must not discard owners who loaded.
 *
 * @param reason why the parent, or part of it, was unavailable, otherwise null
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
            Sourced<CompanyProfile> profile,
            Sourced<List<Psc>> pscs,
            Sourced<PscExemptions> exemptions,
            String reason) {
        return new ParentRecord(Status.FOUND, profile, pscs, exemptions, reason);
    }

    /** The calls that were answered, in a fixed order. */
    public List<Sourced<?>> answeredCalls() {
        return Stream.of(profile, pscs, exemptions)
                .filter(Objects::nonNull)
                .<Sourced<?>>map(call -> call)
                .toList();
    }

    public static ParentRecord notFound() {
        return new ParentRecord(Status.NOT_FOUND, null, null, null, null);
    }

    public static ParentRecord unavailable(String reason) {
        return new ParentRecord(Status.UNAVAILABLE, null, null, null, reason);
    }
}
