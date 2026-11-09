package com.cypherz01.kyb.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The README is part of the deliverable (spec §13) and the route in for a newcomer (M7). These tests
 * catch what goes wrong with one quietly: sections missing, links dead, examples that no longer work.
 */
class ReadmeTest {

    static String readme;
    static List<String> headings;

    @BeforeAll
    static void read() throws IOException {
        readme = Files.readString(Path.of("README.md"));
        headings = readme.lines()
                .filter(l -> l.startsWith("#") && !l.startsWith("#!"))
                .toList();
    }

    @Test
    void hasEverySectionTheSpecRequires() {
        assertThat(headings)
                .contains(
                        "## What it does",
                        "## Architecture",
                        "## Quick start",
                        "## Design decisions and trade-offs",
                        "## Matching accuracy",
                        "## Performance",
                        "## Known limitations",
                        "## Data sources and licences");
    }

    @Test
    void explainsEachRequiredDesignDecision() {
        String decisions = section("## Design decisions and trade-offs").toLowerCase(Locale.ROOT);

        assertThat(decisions)
                .contains("bulk", "hosted api")
                .contains("rate limit", "cach")
                .contains("no sla", "degrade")
                .contains("rule-based", "ml")
                .contains("false positive", "false negative")
                .contains("replay", "append-only");
    }

    /** The README is already long, so the reasoning for v2 lives in the ADRs and must be reachable. */
    @Test
    void linksToTheDecisionRecordsForLookThrough() {
        assertThat(readme)
                .contains("docs/adr/0001-review-of-v1-and-plan.md")
                .contains("docs/adr/0002-look-through-scope.md")
                .contains("docs/adr/0003-effective-ownership-and-control.md");
    }

    @Test
    void linksToTheDecisionRecordsForMonitoring() {
        assertThat(readme)
                .contains("docs/adr/0004-monitoring-triggers-and-opt-in.md")
                .contains("docs/adr/0005-reverse-delta-screening.md")
                .contains("docs/adr/0006-webhook-outbox.md");
    }

    @Test
    void hasAnArchitectureDiagramAndWalkthrough() {
        String architecture = section("## Architecture");

        assertThat(architecture).contains("```mermaid");
        assertThat(architecture).contains("1. ");
    }

    @Test
    void theQuickStartOffersTheKeylessDemoFirst() {
        String quickStart = section("## Quick start");

        assertThat(quickStart).contains("scripts/demo.sh");
        assertThat(quickStart.indexOf("scripts/demo.sh")).isLessThan(quickStart.indexOf("cp .env.example .env"));
    }

    /** Health stays UP during the first import, so a check straight after startup would get a 503. */
    @Test
    void theQuickStartWaitsForTheSanctionsDataBeforeTheFirstCheck() {
        String quickStart = section("## Quick start");

        assertThat(quickStart).contains("/api/v1/admin/sanctions/status");
        assertThat(quickStart.indexOf("/api/v1/admin/sanctions/status"))
                .isLessThan(quickStart.indexOf("-X POST http://localhost:8080/api/v1/checks"));
    }

    /** The API refuses any call without the key, so an example without it can only ever fail. */
    @Test
    void everyApiExampleSendsTheApiKey() {
        List<String> curls =
                curlCommands().stream().filter(c -> c.contains("/api/")).toList();

        assertThat(curls).isNotEmpty();
        assertThat(curls).allSatisfy(c -> assertThat(c).contains("X-Api-Key"));
    }

    @Test
    void everyRelativeLinkResolves() {
        Matcher links = Pattern.compile("]\\(([^)#\\s]+)(#[^)\\s]*)?\\)").matcher(readme);
        List<String> broken = new ArrayList<>();
        while (links.find()) {
            String target = links.group(1);
            if (!target.matches("^[a-z]+:.*") && !Files.exists(Path.of(target))) {
                broken.add(target);
            }
        }

        assertThat(broken).isEmpty();
    }

    @Test
    void everyInPageLinkHasAHeading() {
        List<String> slugs = headings.stream().map(ReadmeTest::slug).toList();
        Matcher anchors = Pattern.compile("]\\(#([^)\\s]+)\\)").matcher(readme);
        List<String> broken = new ArrayList<>();
        while (anchors.find()) {
            if (!slugs.contains(anchors.group(1))) {
                broken.add(anchors.group(1));
            }
        }

        assertThat(broken).isEmpty();
    }

    @Test
    void statesTheLicenceLimitsAndTheDisclaimer() {
        String licences = section("## Data sources and licences");

        assertThat(licences).contains("CC BY-NC 4.0").containsIgnoringCase("non-commercial");
        assertThat(licences).contains("Companies House").contains("FCA");
        assertThat(readme).contains("not a compliance product");
    }

    @Test
    void reportsMeasuredAccuracyAndPerformance() {
        assertThat(section("## Matching accuracy")).contains("Precision", "Recall");
        assertThat(section("## Performance")).contains("p95").contains("ms");
    }

    @Test
    void noLongerDescribesItselfAsUnfinished() {
        assertThat(readme.toLowerCase(Locale.ROOT)).doesNotContain("work in progress");
    }

    /** The text under a level-2 heading, up to the next level-2 heading. */
    private static String section(String heading) {
        int start = readme.indexOf("\n" + heading + "\n");
        assertThat(start).as("section %s", heading).isGreaterThanOrEqualTo(0);
        int end = readme.indexOf("\n## ", start + heading.length());
        return end < 0 ? readme.substring(start) : readme.substring(start, end);
    }

    /** Each curl command, with its backslash-continued lines joined. */
    private static List<String> curlCommands() {
        List<String> commands = new ArrayList<>();
        String joined = readme.replace("\\\n", " ");
        joined.lines().map(String::strip).filter(l -> l.startsWith("curl ")).forEach(commands::add);
        return commands;
    }

    private static String slug(String heading) {
        return heading.replaceFirst("^#+\\s*", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 -]", "")
                .replace(' ', '-');
    }
}
