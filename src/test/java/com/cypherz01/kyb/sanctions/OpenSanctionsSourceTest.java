package com.cypherz01.kyb.sanctions;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cypherz01.kyb.support.Fixtures;
import com.cypherz01.kyb.support.OpenSanctionsStubs;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.RestClient;

/** HTTP behaviour of the bulk-data source, against WireMock (no Spring context). */
@ExtendWith(OutputCaptureExtension.class)
class OpenSanctionsSourceTest {

    static final String FIXTURE = "sanctions/entities-v2.ftm.json";
    static final String VERSION = "20261107135250-gvs";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    MeterRegistry meters;
    OpenSanctionsSource source;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        SanctionsProperties properties = new SanctionsProperties(
                URI.create(wm.baseUrl() + OpenSanctionsStubs.INDEX_PATH),
                "entities.ftm.json",
                Set.of("Person"),
                Set.of("sanction"),
                100,
                3,
                10,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                "-",
                "Europe/London",
                false);
        source = new OpenSanctionsSource(
                OpenSanctionsSource.buildRestClient(RestClient.builder(), properties), properties, meters);
    }

    @Test
    void describesTheConfiguredResourceFromTheIndex() {
        OpenSanctionsStubs.publish(wm.getRuntimeInfo().getWireMock(), wm.baseUrl(), VERSION, FIXTURE);

        DatasetDescriptor d = source.describeLatest();

        byte[] body = Fixtures.bytes(FIXTURE);
        assertThat(d.versionLabel()).isEqualTo(VERSION);
        // picks entities.ftm.json, not the first resource listed (targets.simple.csv)
        assertThat(d.url()).isEqualTo(URI.create(wm.baseUrl() + OpenSanctionsStubs.LATEST_FILE_PATH));
        assertThat(d.sha1()).isEqualTo(OpenSanctionsStubs.sha1(body));
        assertThat(d.sizeBytes()).isEqualTo(body.length);
    }

    @Test
    void indexWithoutAChecksumGivesANullSha1() {
        byte[] body = Fixtures.bytes(FIXTURE);
        OpenSanctionsStubs.publish(wm.getRuntimeInfo().getWireMock(), wm.baseUrl(), VERSION, body, null);

        assertThat(source.describeLatest().sha1()).isNull();
    }

    @Test
    void indexWithoutTheConfiguredResourceIsASourceError() {
        wm.stubFor(get(urlPathEqualTo(OpenSanctionsStubs.INDEX_PATH))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                                "{\"version\":\"v\",\"resources\":[{\"name\":\"names.txt\",\"url\":\"http://x\"}]}")));

        assertThatThrownBy(source::describeLatest)
                .isInstanceOf(SanctionsSourceException.class)
                .hasMessageContaining("entities.ftm.json");
    }

    @Test
    void indexServerErrorIsASourceError() {
        wm.stubFor(get(urlPathEqualTo(OpenSanctionsStubs.INDEX_PATH))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(source::describeLatest)
                .isInstanceOf(SanctionsSourceException.class)
                .hasMessageContaining("503");
    }

    @Test
    void indexThatIsNotJsonIsASourceError() {
        wm.stubFor(get(urlPathEqualTo(OpenSanctionsStubs.INDEX_PATH))
                .willReturn(aResponse().withHeader("Content-Type", "text/html").withBody("<html>maintenance</html>")));

        assertThatThrownBy(source::describeLatest).isInstanceOf(SanctionsSourceException.class);
    }

    @Test
    void opensTheFileFollowingTheLatestRedirect() {
        OpenSanctionsStubs.publish(wm.getRuntimeInfo().getWireMock(), wm.baseUrl(), VERSION, FIXTURE);
        DatasetDescriptor d = source.describeLatest();

        byte[] streamed = source.open(d, in -> in.readAllBytes());

        assertThat(streamed).isEqualTo(Fixtures.bytes(FIXTURE));
        wm.verify(getRequestedFor(urlPathEqualTo(OpenSanctionsStubs.artifactPath(VERSION))));
    }

    @Test
    void fileNotFoundIsASourceError() {
        DatasetDescriptor d = new DatasetDescriptor("v", URI.create(wm.baseUrl() + "/missing.json"), null, null);
        wm.stubFor(get(urlPathEqualTo("/missing.json")).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> source.open(d, in -> in.readAllBytes()))
                .isInstanceOf(SanctionsSourceException.class)
                .hasMessageContaining("404");
    }

    @Test
    void unreachableHostIsASourceError() {
        DatasetDescriptor d =
                new DatasetDescriptor("v", URI.create("http://127.0.0.1:1/entities.ftm.json"), null, null);

        assertThatThrownBy(() -> source.open(d, in -> in.readAllBytes())).isInstanceOf(SanctionsSourceException.class);
    }

    @Test
    void logsAndTimesEachExternalCall(CapturedOutput output) {
        OpenSanctionsStubs.publish(wm.getRuntimeInfo().getWireMock(), wm.baseUrl(), VERSION, FIXTURE);

        source.open(source.describeLatest(), in -> in.readAllBytes());

        assertThat(output).contains("external call source=opensanctions operation=index status=200");
        assertThat(output).contains("external call source=opensanctions operation=download status=200");
        assertThat(meters.find("kyb.external.calls")
                        .tags("source", "opensanctions", "operation", "download", "outcome", "200")
                        .timer())
                .isNotNull();
    }
}
