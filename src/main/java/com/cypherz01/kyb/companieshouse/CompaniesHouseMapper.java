package com.cypherz01.kyb.companieshouse;

import com.cypherz01.kyb.companieshouse.model.CompanyProfile;
import com.cypherz01.kyb.companieshouse.model.Officer;
import com.cypherz01.kyb.companieshouse.model.Psc;
import com.cypherz01.kyb.companieshouse.model.PscKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/** Maps raw Companies House JSON to the domain model. Pure and side-effect free. */
public class CompaniesHouseMapper {

    public CompaniesHouseMapper(ObjectMapper objectMapper) {}

    public CompanyProfile toProfile(JsonNode json) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public List<Officer> toOfficers(JsonNode json) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public List<Psc> toPscs(JsonNode json) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public static PscKind kindOf(String companiesHouseKind) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
