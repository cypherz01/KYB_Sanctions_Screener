package com.cypherz01.kyb.companieshouse;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.cypherz01.kyb.cache.SourceCache;
import com.cypherz01.kyb.cache.Sourced;
import com.cypherz01.kyb.companieshouse.model.PscExemptions;
import com.cypherz01.kyb.support.Fixtures;
import com.cypherz01.kyb.support.MutableClockConfig;
import com.cypherz01.kyb.support.PostgresTestcontainer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * PSC-register exemptions (M1 open item for NO_PSC_DECLARED). The shape was verified live on Tesco
 * PLC: an exemption item without {@code exempt_to} is current; no exemptions is a 404.
 */
@SpringBootTest(properties = "kyb.sanctions.cron=-")
@Import({PostgresTestcontainer.class, MutableClockConfig.class})
class CompaniesHouseExemptionsTest {

    static final String NUMBER = "12345678";
    static final String PATH = "/company/" + NUMBER + "/exemptions";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void companiesHouse(DynamicPropertyRegistry registry) {
        registry.add("kyb.companies-house.base-url", wm::baseUrl);
        registry.add("kyb.companies-house.api-key", () -> "it-key");
        registry.add("kyb.companies-house.retry.base-delay", () -> "10ms");
    }

    @Autowired
    CompaniesHouseClient client;

    @Autowired
    SourceCache cache;

    final CompaniesHouseMapper mapper = new CompaniesHouseMapper(new ObjectMapper());

    @BeforeEach
    void reset() {
        wm.resetAll();
        cache.clearAll();
    }

    @Test
    void onlyExemptionsWithoutAnEndDateAreCurrent() {
        wm.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(Fixtures.read("companieshouse/exemptions.json"))));

        Sourced<PscExemptions> e = client.getExemptions(NUMBER);

        assertThat(e.value().current()).containsExactly("psc-exempt-as-trading-on-uk-regulated-market");
        assertThat(e.value().exempt()).isTrue();
        assertThat(e.payload().path("exemptions").isObject()).isTrue(); // raw payload kept for audit
    }

    @Test
    void aCompanyWithNoExemptionsIsANotFoundAndThatAnswerIsCached() {
        wm.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(404)));

        Sourced<PscExemptions> first = client.getExemptions(NUMBER);
        Sourced<PscExemptions> second = client.getExemptions(NUMBER);

        assertThat(first.value().exempt()).isFalse();
        assertThat(first.value().current()).isEmpty();
        assertThat(second.fromCache()).isTrue();
        wm.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void expiredExemptionsOnlyMeanNotExempt() throws Exception {
        PscExemptions e = mapper.toExemptions(
                new ObjectMapper()
                        .readTree(
                                """
                        {"exemptions":{"psc_exempt_as_shares_admitted_on_market":{
                          "exemption_type":"psc-exempt-as-shares-admitted-on-market",
                          "items":[{"exempt_from":"2016-06-30","exempt_to":"2019-01-01"}]}}}
                        """));

        assertThat(e.exempt()).isFalse();
    }

    @Test
    void anEmptyObjectMeansNoExemptions() throws Exception {
        assertThat(mapper.toExemptions(new ObjectMapper().readTree("{}"))).isEqualTo(PscExemptions.NONE);
    }

    @Test
    void theExemptionTypeFallsBackToTheKeyWhenAbsent() throws Exception {
        PscExemptions e = mapper.toExemptions(
                new ObjectMapper()
                        .readTree(
                                "{\"exemptions\":{\"psc_exempt_as_trading_on_eu_regulated_market\":{\"items\":[{\"exempt_from\":\"2018-01-01\"}]}}}"));

        assertThat(e.current()).containsExactly("psc-exempt-as-trading-on-eu-regulated-market");
    }
}
