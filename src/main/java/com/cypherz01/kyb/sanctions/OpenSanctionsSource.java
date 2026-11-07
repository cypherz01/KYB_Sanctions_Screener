package com.cypherz01.kyb.sanctions;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.web.client.RestClient;

/**
 * HTTP access to OpenSanctions bulk data (no API key, no hosted API: spec §5.3). Reads the dataset
 * index, then streams the file to a caller-supplied handler so it is never held in memory. The
 * {@code latest} URLs answer with a 307 to a versioned artifact, so redirects are followed.
 */
public class OpenSanctionsSource {

    public static final String SOURCE = "opensanctions";

    public OpenSanctionsSource(RestClient restClient, SanctionsProperties properties, MeterRegistry meters) {}

    /** Builds a RestClient with timeouts that follows redirects. */
    public static RestClient buildRestClient(RestClient.Builder builder, SanctionsProperties properties) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    /**
     * @return the current export as described by the index
     * @throws SanctionsSourceException index unreachable, non-2xx, or lacking the configured resource
     */
    public DatasetDescriptor describeLatest() {
        throw new UnsupportedOperationException("not implemented yet");
    }

    /**
     * Streams the file body to {@code handler} while the connection is open.
     *
     * @throws SanctionsSourceException unreachable, or non-2xx after redirects
     */
    public <T> T open(DatasetDescriptor descriptor, StreamHandler<T> handler) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @FunctionalInterface
    public interface StreamHandler<T> {
        T handle(InputStream body) throws IOException;
    }
}
