package com.cypherz01.kyb.matching;

import java.util.Set;

/**
 * What kind of party is being screened. It decides which normalisation rules apply (honorifics for
 * people, legal forms for organisations) and which sanctions schemas are comparable: a person is
 * never matched against a company or the reverse (spec §8.3).
 */
public enum SubjectType {
    PERSON(Set.of("Person", "LegalEntity")),
    ORGANISATION(Set.of("Company", "Organization", "LegalEntity")),
    /** Unknown (e.g. ad-hoc screening without a type): both rule sets, every schema. */
    ANY(Set.of("Person", "Company", "Organization", "LegalEntity"));

    private final Set<String> schemas;

    SubjectType(Set<String> schemas) {
        this.schemas = schemas;
    }

    /** FtM schemas this subject may be matched against. */
    public Set<String> schemas() {
        return schemas;
    }
}
