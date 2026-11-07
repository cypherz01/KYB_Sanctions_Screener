package com.cypherz01.kyb.support;

import com.cypherz01.kyb.check.ChecksProperties;
import com.cypherz01.kyb.scoring.ScoringProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/**
 * Configuration exactly as shipped (application.yml, scoring-rules.yml), bound without a Spring
 * context, so unit tests exercise the real rules and mappings rather than a test copy.
 */
public final class ShippedConfig {

    private ShippedConfig() {}

    public static ScoringProperties scoring() {
        return bind("scoring-rules.yml", "kyb.scoring", ScoringProperties.class);
    }

    public static ChecksProperties checks() {
        return bind("application.yml", "kyb.checks", ChecksProperties.class);
    }

    private static <T> T bind(String file, String prefix, Class<T> type) {
        try {
            var sources = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
            return new Binder(ConfigurationPropertySources.from(sources))
                    .bind(prefix, type)
                    .get();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
