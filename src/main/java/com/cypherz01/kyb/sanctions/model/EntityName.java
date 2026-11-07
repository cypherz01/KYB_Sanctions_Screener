package com.cypherz01.kyb.sanctions.model;

public record EntityName(String value, NameKind kind) {

    /** Anything other than a primary name counts as an alias (spec §6 {@code is_alias}). */
    public boolean isAlias() {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
