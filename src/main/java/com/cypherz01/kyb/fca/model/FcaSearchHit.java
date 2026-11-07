package com.cypherz01.kyb.fca.model;

/**
 * One firm from a Register name search.
 *
 * @param frn the FCA Firm Reference Number ({@code Reference Number})
 * @param name the firm name without the {@code (Postcode: ...)} suffix the Register appends
 * @param postcode the postcode from that suffix, or null
 * @param status e.g. {@code Authorised}, {@code No longer authorised}, {@code Unauthorised}
 * @param type {@code Type of business or Individual}
 */
public record FcaSearchHit(String frn, String name, String postcode, String status, String type) {}
