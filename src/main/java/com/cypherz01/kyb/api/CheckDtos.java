package com.cypherz01.kyb.api;

/** Request body for {@code POST /api/v1/checks} (spec §7). The response is CheckResponse. */
public final class CheckDtos {

    private CheckDtos() {}

    /** @param companyNumber Companies House number, e.g. {@code 09446231} (zero-padding optional) */
    public record CreateCheckRequest(String companyNumber) {}
}
