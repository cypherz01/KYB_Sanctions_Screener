package com.cypherz01.kyb.fca;

import static org.assertj.core.api.Assertions.assertThat;

import com.cypherz01.kyb.fca.model.FcaFirmDetails;
import com.cypherz01.kyb.fca.model.FcaSearchHit;
import com.cypherz01.kyb.support.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FcaMapperTest {

    static final ObjectMapper JSON = new ObjectMapper();
    final FcaMapper mapper = new FcaMapper();

    @Test
    void mapsSearchHitsAndSplitsOffThePostcodeSuffix() {
        assertThat(mapper.toSearchHits(json(Fixtures.read("fca/search-acme.json"))))
                .containsExactly(
                        new FcaSearchHit("123456", "Acme Payments Ltd", "EC2A 1AB", "Authorised", "Firm"),
                        new FcaSearchHit(
                                "222222", "Acme Payments Holdings Limited", "M1 1AA", "No longer authorised", "Firm"),
                        new FcaSearchHit("333333", "Zeta Capital Partners LLP", "EC1A 1BB", "Authorised", "Firm"));
    }

    @Test
    void aNameWithoutAPostcodeSuffixIsKeptWhole() {
        FcaSearchHit hit = mapper.toSearchHits(json(Fixtures.read("fca/search-clone.json")))
                .get(1);

        assertThat(hit.name()).isEqualTo("Acme Payments Ltd (Clone of FCA authorised firm)");
        assertThat(hit.postcode()).isNull();
    }

    // Added in M4 green: the real Register writes "N/A" / "n/a" for a missing postcode.
    @Test
    void aNotApplicablePostcodeIsNoPostcode() {
        String envelope = "{\"Data\":[{\"Reference Number\":\"1\",\"Status\":\"Unauthorised\","
                + "\"Name\":\"Independence Loans (Clone) (Postcode: N/A)\"},"
                + "{\"Reference Number\":\"2\",\"Status\":\"Unauthorised\",\"Name\":\"Sunshine Credit (clone) (Postcode: n/a)\"}]}";

        assertThat(mapper.toSearchHits(json(envelope)))
                .extracting(FcaSearchHit::name, FcaSearchHit::postcode)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Independence Loans (Clone)", null),
                        org.assertj.core.groups.Tuple.tuple("Sunshine Credit (clone)", null));
    }

    @Test
    void noResultsMeansNoHits() {
        assertThat(mapper.toSearchHits(json(Fixtures.read("fca/search-empty.json"))))
                .isEmpty();
        assertThat(mapper.toSearchHits(json("{\"Status\":\"x\"}"))).isEmpty();
    }

    @Test
    void mapsFirmDetails() {
        assertThat(mapper.toFirmDetails(json(Fixtures.read("fca/firm-123456.json"))))
                .contains(new FcaFirmDetails(
                        "123456", "Acme Payments Ltd", "Authorised", "01/06/2019", "Regulated", "12345678"));
    }

    @ParameterizedTest
    @CsvSource(
            nullValues = "NULL",
            value = {"1026167,01026167", "01026167,01026167", "sc123456,SC123456", "' ',NULL", "'',NULL", "N/A,N/A"})
    void canonicalisesTheCompaniesHouseNumber(String published, String expected) {
        String envelope = "{\"Data\":[{\"FRN\":\"1\",\"Organisation Name\":\"A\",\"Companies House Number\":\""
                + published + "\"}]}";

        assertThat(mapper.toFirmDetails(json(envelope)).orElseThrow().companiesHouseNumber())
                .isEqualTo(expected);
    }

    @Test
    void absentCompaniesHouseNumberIsNull() {
        assertThat(mapper.toFirmDetails(json("{\"Data\":[{\"FRN\":\"1\",\"Organisation Name\":\"A\"}]}"))
                        .orElseThrow()
                        .companiesHouseNumber())
                .isNull();
    }

    @Test
    void firmEnvelopeWithoutDataIsEmpty() {
        assertThat(mapper.toFirmDetails(json("{\"Status\":\"x\",\"Data\":null}")))
                .isEmpty();
        assertThat(mapper.toFirmDetails(json("{\"Data\":[]}"))).isEmpty();
    }

    private static JsonNode json(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
