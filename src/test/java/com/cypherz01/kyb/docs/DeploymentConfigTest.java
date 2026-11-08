package com.cypherz01.kyb.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** What a newcomer's first {@code docker compose up} depends on (M7). */
class DeploymentConfigTest {

    /** A Postgres already on the machine owns 5432, and the bind failure stops the whole stack. */
    @Test
    void theDatabaseDoesNotClaimTheDefaultPostgresPort() throws IOException {
        assertThat(ports(service(compose("docker-compose.yml"), "postgres"))).noneMatch(p -> p.contains("5432:5432"));
    }

    /** The spec's deployment is local-only, and metrics and API docs are not behind the API key. */
    @Test
    void publishedPortsAreLocalOnly() throws IOException {
        Map<String, Object> compose = compose("docker-compose.yml");

        assertThat(ports(service(compose, "app"))).isNotEmpty().allMatch(p -> p.startsWith("127.0.0.1:"));
        assertThat(ports(service(compose, "postgres"))).allMatch(p -> p.startsWith("127.0.0.1:"));
    }

    @Test
    void everyVariableTheAppReadsIsDocumentedInTheEnvExample() throws IOException {
        String example = Files.readString(Path.of(".env.example"));
        String config = Files.readString(Path.of("src/main/resources/application.yml"));
        Matcher variables = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]+)").matcher(config);
        List<String> undocumented = new ArrayList<>();
        while (variables.find()) {
            if (!example.contains(variables.group(1))) {
                undocumented.add(variables.group(1));
            }
        }

        assertThat(undocumented).isEmpty();
    }

    /** A Test key from the developer hub is rejected by the live API, with a confusing 401. */
    @Test
    void theEnvExampleSaysWhichKindOfCompaniesHouseKeyToCreate() throws IOException {
        assertThat(Files.readString(Path.of(".env.example"))).contains("Live");
    }

    // ---------- the keyless demo ----------

    @Test
    void theDemoNeedsNoEnvFileAndIsItsOwnProject() throws IOException {
        Map<String, Object> demo = compose("docker-compose.demo.yml");

        assertThat(demo.get("name")).isEqualTo("kyb-demo");
        assertThat(service(demo, "app")).doesNotContainKey("env_file");
    }

    /** It must run beside a real stack and leave nothing behind, so no volume and no database port. */
    @Test
    void theDemoDatabaseIsThrowaway() throws IOException {
        Map<String, Object> postgres = service(compose("docker-compose.demo.yml"), "postgres");

        assertThat(postgres).doesNotContainKey("ports").doesNotContainKey("volumes");
    }

    @Test
    void theDemoPublishesOnlyLocally() throws IOException {
        assertThat(ports(service(compose("docker-compose.demo.yml"), "app")))
                .isNotEmpty()
                .allMatch(p -> p.startsWith("127.0.0.1:"));
    }

    /** The whole point of the demo: synthetic data, so no real source can be reached from it. */
    @Test
    void theDemoTalksOnlyToItsStubs() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> environment = (Map<String, Object>)
                service(compose("docker-compose.demo.yml"), "app").get("environment");

        assertThat(environment.get("KYB_COMPANIESHOUSE_BASEURL").toString()).startsWith("http://stubs:");
        assertThat(environment.get("KYB_FCA_BASEURL").toString()).startsWith("http://stubs:");
        assertThat(environment.get("KYB_SANCTIONS_INDEXURL").toString()).startsWith("http://stubs:");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> compose(String file) throws IOException {
        return (Map<String, Object>) new Yaml().load(Files.readString(Path.of(file)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(Map<String, Object> compose, String name) {
        return ((Map<String, Map<String, Object>>) compose.get("services")).get(name);
    }

    @SuppressWarnings("unchecked")
    private static List<String> ports(Map<String, Object> service) {
        return (List<String>) service.getOrDefault("ports", List.of());
    }
}
