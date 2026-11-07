package com.cypherz01.kyb.sanctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * HTTP access to OpenSanctions bulk data (no API key, no hosted API: spec §5.3). Reads the dataset
 * index, then streams the file to a caller-supplied handler so it is never held in memory. The
 * {@code latest} URLs answer with a 307 to a versioned artifact, so redirects are followed.
 */
public class OpenSanctionsSource {

    public static final String SOURCE = "opensanctions";
    private static final Logger log = LoggerFactory.getLogger(OpenSanctionsSource.class);

    private final RestClient index;
    private final RestClient download;
    private final SanctionsProperties properties;
    private final MeterRegistry meters;
    // Private mapper: the index is read from the raw stream whatever Content-Type the CDN sends.
    private final ObjectMapper json = new ObjectMapper();

    public OpenSanctionsSource(RestClient restClient, SanctionsProperties properties, MeterRegistry meters) {
        this.index = restClient;
        // Spring's JDK request factory treats its read timeout as a limit on the WHOLE exchange and
        // closes the body when it expires. That suits the small index, but it would cut off a
        // healthy multi-minute download. Downloads therefore use a factory without that timeout.
        // Stalls are caught by an idle watchdog on the body instead (see open()).
        this.download = restClient
                .mutate()
                .requestFactory(requestFactory(properties, null))
                .build();
        this.properties = properties;
        this.meters = meters;
    }

    /** Builds a RestClient with timeouts that follows redirects. */
    public static RestClient buildRestClient(RestClient.Builder builder, SanctionsProperties properties) {
        return builder.requestFactory(requestFactory(properties, properties.readTimeout()))
                .build();
    }

    private static JdkClientHttpRequestFactory requestFactory(SanctionsProperties properties, Duration totalTimeout) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                // NORMAL: follow redirects, except https -> http downgrades.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        if (totalTimeout != null) {
            factory.setReadTimeout(totalTimeout);
        }
        return factory;
    }

    /**
     * @return the current export as described by the index
     * @throws SanctionsSourceException index unreachable, non-2xx, or lacking the configured resource
     */
    public DatasetDescriptor describeLatest() {
        JsonNode body = get(index, "index", properties.indexUrl(), in -> {
            try {
                return json.readTree(in);
            } catch (IOException e) {
                throw new SanctionsSourceException("OpenSanctions index is not valid JSON", e);
            }
        });
        return toDescriptor(body);
    }

    /**
     * Streams the file body to {@code handler} while the connection is open. The transfer may take
     * as long as it needs, but it fails if no bytes arrive for {@code read-timeout}.
     *
     * @throws SanctionsSourceException unreachable, non-2xx after redirects, or stalled
     */
    public <T> T open(DatasetDescriptor descriptor, StreamHandler<T> handler) {
        return get(download, "download", descriptor.url(), in -> {
            try (InputStream watched = new IdleTimeoutInputStream(in, properties.readTimeout())) {
                return handler.handle(watched);
            }
        });
    }

    private <T> T get(RestClient rest, String operation, URI uri, StreamHandler<T> handler) {
        long start = System.nanoTime();
        try {
            return rest.get().uri(uri).exchange((req, res) -> {
                int status = res.getStatusCode().value();
                record(operation, Integer.toString(status), start);
                if (!res.getStatusCode().is2xxSuccessful()) {
                    throw new SanctionsSourceException("OpenSanctions " + operation + " returned HTTP " + status);
                }
                try {
                    return handler.handle(res.getBody());
                } catch (IOException e) {
                    throw new SanctionsSourceException(
                            "Reading OpenSanctions " + operation + " failed: " + e.getMessage(), e);
                }
            });
        } catch (ResourceAccessException e) {
            record(operation, "io_error", start);
            throw new SanctionsSourceException(
                    "OpenSanctions " + operation + " unreachable: "
                            + e.getMostSpecificCause().getClass().getSimpleName(),
                    e);
        }
    }

    private DatasetDescriptor toDescriptor(JsonNode index) {
        for (JsonNode resource : index.path("resources")) {
            if (properties.resourceName().equals(resource.path("name").asText())) {
                String url = blankToNull(resource.path("url").asText(null));
                if (url == null) {
                    throw new SanctionsSourceException(
                            "OpenSanctions index resource " + properties.resourceName() + " has no url");
                }
                String version = blankToNull(index.path("version").asText(null));
                if (version == null) {
                    version = blankToNull(index.path("updated_at").asText(null));
                }
                JsonNode size = resource.path("size");
                return new DatasetDescriptor(
                        version,
                        URI.create(url),
                        blankToNull(resource.path("checksum").asText(null)),
                        size.isNumber() ? size.asLong() : null);
            }
        }
        throw new SanctionsSourceException("OpenSanctions index has no resource named " + properties.resourceName());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private void record(String operation, String outcome, long startNanos) {
        long nanos = System.nanoTime() - startNanos;
        Timer.builder("kyb.external.calls")
                .description("Calls to external data sources")
                .tag("source", SOURCE)
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(meters)
                .record(nanos, TimeUnit.NANOSECONDS);
        // For downloads this is time to response headers; the import logs the total duration.
        log.info(
                "external call source={} operation={} status={} latencyMs={}",
                SOURCE,
                operation,
                outcome,
                TimeUnit.NANOSECONDS.toMillis(nanos));
    }

    @FunctionalInterface
    public interface StreamHandler<T> {
        T handle(InputStream body) throws IOException;
    }
}
