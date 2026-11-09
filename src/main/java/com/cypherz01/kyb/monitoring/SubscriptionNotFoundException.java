package com.cypherz01.kyb.monitoring;

/** There is no active subscription for this company. */
public class SubscriptionNotFoundException extends RuntimeException {

    public SubscriptionNotFoundException(String companyNumber) {
        super("Company " + companyNumber + " is not being monitored");
    }
}
