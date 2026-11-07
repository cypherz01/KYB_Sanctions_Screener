package com.cypherz01.kyb.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.client.WireMock;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Publishes a fixture on WireMock the way OpenSanctions does: an index naming the version, the
 * file's SHA-1 and a {@code latest} URL, which answers 307 with a versioned artifact URL.
 */
public final class OpenSanctionsStubs {

    public static final String INDEX_PATH = "/datasets/latest/sanctions/index.json";
    public static final String LATEST_FILE_PATH = "/datasets/latest/sanctions/entities.ftm.json";

    private OpenSanctionsStubs() {}

    public static String artifactPath(String version) {
        return "/artifacts/sanctions/" + version + "/entities.ftm.json";
    }

    /** Publishes {@code fixture} as export {@code version}, with its correct SHA-1. */
    public static void publish(WireMock wm, String baseUrl, String version, String fixture) {
        byte[] body = Fixtures.bytes(fixture);
        publish(wm, baseUrl, version, body, sha1(body));
    }

    /** Publishes {@code body} but advertises {@code advertisedSha1} in the index. */
    public static void publish(WireMock wm, String baseUrl, String version, byte[] body, String advertisedSha1) {
        wm.register(get(urlPathEqualTo(INDEX_PATH))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(indexJson(version, baseUrl + LATEST_FILE_PATH, advertisedSha1, body.length))));
        wm.register(get(urlPathEqualTo(LATEST_FILE_PATH))
                .willReturn(aResponse().withStatus(307).withHeader("Location", baseUrl + artifactPath(version))));
        wm.register(get(urlPathEqualTo(artifactPath(version)))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json+ftm")
                        .withBody(body)));
    }

    /** Shape copied from the real index.json (verified 2026-11-07), trimmed to what is read. */
    public static String indexJson(String version, String fileUrl, String sha1, long size) {
        String checksum = sha1 == null ? "" : "\"checksum\":\"" + sha1 + "\",";
        return """
                {"name":"sanctions","type":"collection","version":"%s","updated_at":"2026-11-07T13:52:50",
                 "entity_count":309872,"target_count":73897,
                 "resources":[
                   {"name":"targets.simple.csv","checksum":"0000000000000000000000000000000000000000",
                    "mime_type":"text/csv","size":78791045,"path":"targets.simple.csv",
                    "url":"https://example.invalid/targets.simple.csv"},
                   {"name":"entities.ftm.json",%s"mime_type":"application/json+ftm","size":%d,
                    "path":"entities.ftm.json","url":"%s"}
                 ]}
                """
                .formatted(version, checksum, size, fileUrl);
    }

    public static String sha1(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
