package com.cypherz01.kyb.sanctions;

import java.net.URI;

/**
 * What the upstream index says the current export is.
 *
 * @param versionLabel upstream export version, e.g. {@code 20261107135250-gvs}
 * @param url the file to download (may redirect)
 * @param sha1 published SHA-1 (hex) of the file, or {@code null} if the index has none
 * @param sizeBytes published size, or {@code null}
 */
public record DatasetDescriptor(String versionLabel, URI url, String sha1, Long sizeBytes) {}
