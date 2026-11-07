package com.cypherz01.kyb.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public final class Fixtures {

    private Fixtures() {}

    public static String read(String classpathResource) {
        return new String(bytes(classpathResource), StandardCharsets.UTF_8);
    }

    /** Exact bytes, for fixtures whose checksum matters. */
    public static byte[] bytes(String classpathResource) {
        try (InputStream in = Fixtures.class.getClassLoader().getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing fixture: " + classpathResource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
