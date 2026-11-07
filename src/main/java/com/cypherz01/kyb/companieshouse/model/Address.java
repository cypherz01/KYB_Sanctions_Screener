package com.cypherz01.kyb.companieshouse.model;

public record Address(
        String addressLine1, String addressLine2, String locality, String region, String postalCode, String country) {}
