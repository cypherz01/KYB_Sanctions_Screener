package com.cypherz01.kyb.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public final class Fixtures {

    private Fixtures() {}

    public static String read(String classpathResource) {
        try (InputStream in = Fixtures.class.getClassLoader().getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing fixture: " + classpathResource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
