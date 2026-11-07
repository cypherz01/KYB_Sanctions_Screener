package com.cypherz01.kyb.matching;

import java.time.YearMonth;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A birth date as sanctions lists and Companies House publish it: year only, year and month
 * (Companies House gives officers' month and year only), or a full date.
 *
 * @param month 1-12, or null
 * @param day 1-31, or null (only present with a month)
 */
public record PartialDate(int year, Integer month, Integer day) {

    private static final Pattern FORMAT = Pattern.compile("(\\d{4})(?:-(\\d{2})(?:-(\\d{2}))?)?");

    /**
     * @param text {@code yyyy}, {@code yyyy-MM} or {@code yyyy-MM-dd}
     * @return empty if the text is blank or not a valid date of one of those forms
     */
    public static Optional<PartialDate> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(text.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        int year = Integer.parseInt(m.group(1));
        if (m.group(2) == null) {
            return Optional.of(new PartialDate(year, null, null));
        }
        int month = Integer.parseInt(m.group(2));
        if (month < 1 || month > 12) {
            return Optional.empty();
        }
        if (m.group(3) == null) {
            return Optional.of(new PartialDate(year, month, null));
        }
        int day = Integer.parseInt(m.group(3));
        if (!YearMonth.of(year, month).isValidDay(day)) {
            return Optional.empty();
        }
        return Optional.of(new PartialDate(year, month, day));
    }

    /** {@code yyyy}, {@code yyyy-MM} or {@code yyyy-MM-dd}, matching what was parsed. */
    @Override
    public String toString() {
        if (month == null) {
            return String.format(Locale.ROOT, "%04d", year);
        }
        if (day == null) {
            return String.format(Locale.ROOT, "%04d-%02d", year, month);
        }
        return String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day);
    }
}
