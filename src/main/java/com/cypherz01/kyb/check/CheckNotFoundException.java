package com.cypherz01.kyb.check;

import java.util.UUID;

/** No stored check has this id. Maps to HTTP 404. */
public class CheckNotFoundException extends RuntimeException {

    public CheckNotFoundException(UUID id) {
        super("No KYB check with id " + id);
    }
}
