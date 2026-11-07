package com.cypherz01.kyb.support;

import com.cypherz01.kyb.matching.MatchingProperties;
import com.cypherz01.kyb.matching.NameMatcher;
import com.cypherz01.kyb.matching.NameNormaliser;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/**
 * The matching settings exactly as shipped in application.yml, bound without a Spring context.
 * Unit tests and the accuracy report therefore measure the real configuration, not a test copy.
 */
public final class MatchingDefaults {

    private static final MatchingProperties PROPERTIES = load();

    private MatchingDefaults() {}

    public static MatchingProperties properties() {
        return PROPERTIES;
    }

    public static NameNormaliser normaliser() {
        return new NameNormaliser(PROPERTIES.honorifics(), PROPERTIES.legalForms(), PROPERTIES.leadingLegalForms());
    }

    public static NameMatcher matcher() {
        return new NameMatcher(PROPERTIES);
    }

    private static MatchingProperties load() {
        try {
            var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
            return new Binder(ConfigurationPropertySources.from(sources))
                    .bind("kyb.matching", MatchingProperties.class)
                    .get();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
