package com.cypherz01.kyb.sanctions.model;

/** Which FtM property a screenable name came from. */
public enum NameKind {
    /** {@code name}, or composed from {@code firstName}/{@code middleName}/{@code lastName} */
    PRIMARY,
    /** {@code alias} */
    ALIAS,
    /** {@code previousName} */
    PREVIOUS
}
