package com.cypherz01.kyb.sanctions;

/** A line parsed as JSON but is not a usable FtM entity (no id or schema). Counted and skipped. */
public class MalformedEntityException extends RuntimeException {

    public MalformedEntityException(String message) {
        super(message);
    }
}
