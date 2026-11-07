package com.cypherz01.kyb.matching;

import java.util.Optional;

/**
 * A birth date as sanctions lists and Companies House publish it: year only, year and month
 * (Companies House gives officers' month and year only), or a full date.
 *
 * @param month 1-12, or null
 * @param day 1-31, or null (only present with a month)
 */
public record PartialDate(int year, Integer month, Integer day) {

    /**
     * @param text {@code yyyy}, {@code yyyy-MM} or {@code yyyy-MM-dd}
     * @return empty if the text is blank or not a valid date of one of those forms
     */
    public static Optional<PartialDate> parse(String text) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    /** {@code yyyy}, {@code yyyy-MM} or {@code yyyy-MM-dd}, matching what was parsed. */
    @Override
    public String toString() {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
