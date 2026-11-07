package com.cypherz01.kyb.matching;

/** The request cannot be screened as given. Maps to HTTP 400. */
public class InvalidScreeningRequestException extends RuntimeException {

    public InvalidScreeningRequestException(String message) {
        super(message);
    }
}
