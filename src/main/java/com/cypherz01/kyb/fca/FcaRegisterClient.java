package com.cypherz01.kyb.fca;

import com.cypherz01.kyb.cache.CacheEntry;
import com.cypherz01.kyb.cache.SourceCache;
import com.cypherz01.kyb.companieshouse.CompanyNumber;
import com.cypherz01.kyb.companieshouse.InvalidCompanyNumberException;
import com.cypherz01.kyb.fca.model.FcaAssessment;
import com.cypherz01.kyb.fca.model.FcaAvailability;
import com.cypherz01.kyb.fca.model.FcaFirmDetails;
import com.cypherz01.kyb.fca.model.FcaFirmMatch;
import com.cypherz01.kyb.fca.model.FcaMatchBasis;
import com.cypherz01.kyb.fca.model.FcaSearchHit;
import com.cypherz01.kyb.fca.model.FcaWarningMatch;
import com.cypherz01.kyb.matching.MatchCandidate;
import com.cypherz01.kyb.matching.MatchSubject;
import com.cypherz01.kyb.matching.NameMatcher;
import com.cypherz01.kyb.matching.NameNormaliser;
import com.cypherz01.kyb.matching.SubjectType;
import com.cypherz01.kyb.sanctions.model.NameKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Establishes a company's regulatory status from the FCA Register (spec §5.2).
 *
 * <ol>
 *   <li>Search the Register by the company name. The response is cached for {@code cache-ttl}.
 *   <li>Score every hit's name with the sanctions matcher (same normaliser, organisation rules).
 *   <li>Hits with a warning status or a clone/unauthorised name marker that match the name become
 *       warnings.
 *   <li>For the strongest authorised hits (up to {@code max-details-lookups}), fetch firm details:
 *       the same Companies House number is a certain match, a different one rules the firm out.
 *       Otherwise the match rests on name and postcode, with that confidence.
 * </ol>
 *
 * Companies House does not carry the FRN, so this matching is unavoidable. If the Register is
 * unavailable, expired cached responses are used (STALE). Without them the result is UNAVAILABLE,
 * never an exception, so a check still completes.
 */
public class FcaRegisterClient {

    public static final String SOURCE = "fca-register";
    private static final Logger log = LoggerFactory.getLogger(FcaRegisterClient.class);
    private static final Pattern PARENTHESISED = Pattern.compile("\\([^)]*\\)");

    private final FcaRegisterApi api;
    private final SourceCache cache;
    private final FcaMapper mapper;
    private final NameNormaliser normaliser;
    private final NameMatcher matcher;
    private final FcaProperties props;
    private final Clock clock;
    private final MeterRegistry meters;
    private final List<Pattern> warningMarkers;
    private final double possible;

    public FcaRegisterClient(
            FcaRegisterApi api,
            SourceCache cache,
            FcaMapper mapper,
            NameNormaliser normaliser,
            NameMatcher matcher,
            FcaProperties properties,
            Clock clock,
            MeterRegistry meters) {
        this.api = api;
        this.cache = cache;
        this.mapper = mapper;
        this.normaliser = normaliser;
        this.matcher = matcher;
        this.props = properties;
        this.clock = clock;
        this.meters = meters;
        this.warningMarkers = properties.warningNameMarkers().stream()
                .map(m -> Pattern.compile("\\b" + Pattern.quote(m) + "\\b", Pattern.CASE_INSENSITIVE))
                .toList();
        // Same bar as sanctions screening: below POSSIBLE a name is not considered the same.
        this.possible = matcher.possibleThreshold();
    }

    /**
     * @param companyName the registered company name to search for
     * @param companyNumber Companies House number, compared with the Register's; may be null
     * @param postcode registered office postcode, compared when the number cannot be; may be null
     */
    public FcaAssessment assess(String companyName, String companyNumber, String postcode) {
        Responses responses = new Responses();
        List<String> evidence = new ArrayList<>();

        Fetched search;
        try {
            search = fetch("search:" + companyName.trim().toLowerCase(Locale.ROOT), () -> api.searchFirms(companyName));
        } catch (FcaUnavailableException e) {
            return new FcaAssessment(
                    FcaAvailability.UNAVAILABLE,
                    companyName,
                    null,
                    List.of(),
                    List.of("Regulatory status could not be verified: " + e.getMessage()),
                    null,
                    false,
                    false,
                    null,
                    e.getMessage());
        }
        responses.add(search);
        responses.payload.set("search", search.payload);

        List<FcaSearchHit> hits = mapper.toSearchHits(search.payload);
        evidence.add("Register search for '" + companyName + "' returned " + hits.size() + " firm(s)");
        MatchSubject subject = new MatchSubject(
                normaliser.normalise(companyName, SubjectType.ORGANISATION), SubjectType.ORGANISATION, null, Set.of());

        List<FcaWarningMatch> warnings = new ArrayList<>();
        List<Scored> authorised = new ArrayList<>();
        for (FcaSearchHit hit : hits) {
            if (hit.frn() == null || hit.name() == null) {
                continue;
            }
            Optional<String> marker = marker(hit.name());
            boolean warningStatus = in(hit.status(), props.warningStatuses());
            double similarity = bestSimilarity(subject, cleaned(hit.name()));
            if (similarity < possible) {
                continue;
            }
            if (warningStatus || marker.isPresent()) {
                List<String> why = new ArrayList<>();
                if (warningStatus) {
                    why.add("Register status '" + hit.status() + "'");
                }
                marker.ifPresent(m -> why.add("Name marked '" + m + "': a clone or unauthorised-firm entry"));
                why.add(String.format(Locale.ROOT, "Name similarity %.2f to '%s'", similarity, hit.name()));
                warnings.add(new FcaWarningMatch(hit.frn(), hit.name(), hit.status(), round(similarity), why));
            } else if (in(hit.status(), props.authorisedStatuses())) {
                authorised.add(new Scored(hit, similarity));
            } else {
                evidence.add("Similar firm '" + hit.name() + "' (FRN " + hit.frn() + ") is '" + hit.status()
                        + "', not a current authorisation");
            }
        }

        FcaFirmMatch match = matchAuthorised(authorised, companyNumber, postcode, responses, evidence);
        if (match == null && authorised.isEmpty()) {
            evidence.add("No authorised firm with a matching name");
        }
        return new FcaAssessment(
                responses.stale ? FcaAvailability.STALE : FcaAvailability.AVAILABLE,
                companyName,
                match,
                List.copyOf(warnings),
                List.copyOf(evidence),
                responses.oldest,
                responses.allFromCache,
                responses.stale,
                responses.payload,
                null);
    }

    /**
     * Repeats {@link #assess} using only the responses a past assessment stored: no call, no cache.
     * The same inputs give an equal assessment, including whatever was unavailable at the time.
     */
    public FcaAssessment reassess(String companyName, String companyNumber, String postcode, FcaSnapshot snapshot) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    private FcaFirmMatch matchAuthorised(
            List<Scored> authorised,
            String companyNumber,
            String postcode,
            Responses responses,
            List<String> evidence) {
        authorised.sort(
                Comparator.comparingDouble(Scored::similarity).reversed().thenComparing(s -> s.hit()
                        .frn()));
        String number = canonical(companyNumber);
        FcaFirmMatch best = null;
        int lookups = 0;
        for (Scored s : authorised) {
            FcaSearchHit hit = s.hit();
            FcaFirmDetails details = null;
            if (number != null && lookups < props.maxDetailsLookups()) {
                lookups++;
                try {
                    Fetched firm = fetch("firm:" + hit.frn(), () -> api.firm(hit.frn()));
                    responses.add(firm);
                    responses.firms().set(hit.frn(), firm.payload);
                    details = mapper.toFirmDetails(firm.payload).orElse(null);
                } catch (FcaUnavailableException e) {
                    evidence.add("Firm details for FRN " + hit.frn() + " unavailable (" + e.getMessage()
                            + "); compared on name only");
                }
            }
            if (details != null && details.companiesHouseNumber() != null) {
                if (details.companiesHouseNumber().equals(number)) {
                    evidence.add("FRN " + hit.frn() + " is registered to Companies House number " + number);
                    return new FcaFirmMatch(
                            hit.frn(),
                            hit.name(),
                            hit.status(),
                            FcaMatchBasis.COMPANIES_HOUSE_NUMBER,
                            1.0,
                            hit.postcode(),
                            number);
                }
                evidence.add("FRN " + hit.frn() + " ('" + hit.name() + "') is registered to Companies House number "
                        + details.companiesHouseNumber() + ", not " + number + ": a different company");
                continue;
            }
            boolean samePostcode = postcode != null
                    && hit.postcode() != null
                    && postcodeKey(postcode).equals(postcodeKey(hit.postcode()));
            double confidence = Math.min(1.0, s.similarity() + (samePostcode ? props.postcodeBoost() : 0));
            FcaFirmMatch candidate = new FcaFirmMatch(
                    hit.frn(),
                    hit.name(),
                    hit.status(),
                    samePostcode ? FcaMatchBasis.NAME_AND_POSTCODE : FcaMatchBasis.NAME,
                    round(confidence),
                    hit.postcode(),
                    details == null ? null : details.companiesHouseNumber());
            if (best == null || candidate.confidence() > best.confidence()) {
                best = candidate;
            }
        }
        if (best != null) {
            evidence.add(String.format(
                    Locale.ROOT,
                    "Matched FRN %s '%s' on %s (confidence %.2f)",
                    best.frn(),
                    best.name(),
                    best.basis() == FcaMatchBasis.NAME_AND_POSTCODE ? "name and postcode" : "name only",
                    best.confidence()));
        }
        return best;
    }

    // ---------- cache-first fetching (as Companies House, M1) ----------

    private record Fetched(JsonNode payload, Instant fetchedAt, boolean fromCache, boolean stale) {}

    private record Scored(FcaSearchHit hit, double similarity) {}

    private static final class Responses {
        final ObjectNode payload = JsonNodeFactory.instance.objectNode();
        Instant oldest;
        boolean allFromCache = true;
        boolean stale;

        void add(Fetched f) {
            oldest = oldest == null || f.fetchedAt.isBefore(oldest) ? f.fetchedAt : oldest;
            allFromCache &= f.fromCache;
            stale |= f.stale;
        }

        ObjectNode firms() {
            return payload.has("firms") ? (ObjectNode) payload.get("firms") : payload.putObject("firms");
        }
    }

    private Fetched fetch(String key, Supplier<JsonNode> live) {
        String fullKey = SOURCE + ":" + key;
        Instant now = clock.instant();
        Optional<CacheEntry> cached = cache.find(fullKey);
        if (cached.isPresent() && cached.get().isFresh(now)) {
            countCache("hit");
            return new Fetched(cached.get().payload(), cached.get().fetchedAt(), true, false);
        }
        countCache("miss");
        try {
            JsonNode payload = live.get();
            cache.put(new CacheEntry(fullKey, SOURCE, payload, now, now.plus(props.cacheTtl())));
            return new Fetched(payload, now, false, false);
        } catch (FcaUnavailableException e) {
            if (cached.isEmpty()) {
                throw e;
            }
            countCache("stale");
            log.warn(
                    "source={} key={} unavailable ({}); serving stale cache fetched at {}",
                    SOURCE,
                    key,
                    e.getMessage(),
                    cached.get().fetchedAt());
            return new Fetched(cached.get().payload(), cached.get().fetchedAt(), true, true);
        }
    }

    private void countCache(String result) {
        meters.counter("kyb.cache.requests", "source", SOURCE, "result", result).increment();
    }

    // ---------- names ----------

    private double similarity(MatchSubject subject, String name) {
        MatchCandidate candidate = new MatchCandidate(
                0,
                0,
                "fca",
                "Company",
                name,
                name,
                NameKind.PRIMARY,
                normaliser.normalise(name, SubjectType.ORGANISATION),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        return matcher.score(subject, candidate).similarity();
    }

    private Optional<String> marker(String name) {
        for (int i = 0; i < warningMarkers.size(); i++) {
            if (warningMarkers.get(i).matcher(name).find()) {
                return Optional.of(props.warningNameMarkers().get(i));
            }
        }
        return Optional.empty();
    }

    /** The name without "(Clone of FCA authorised firm)"-style notes or marker words, for matching. */
    /**
     * Register entries can hold alternative names ("Cater Allen / Cater Allen Private Bank", seen in
     * the smoke run), so the best-matching alternative counts.
     */
    private double bestSimilarity(MatchSubject subject, String name) {
        double best = similarity(subject, name);
        if (name.contains("/")) {
            for (String alternative : name.split("/")) {
                if (!alternative.isBlank()) {
                    best = Math.max(best, similarity(subject, alternative.trim()));
                }
            }
        }
        return best;
    }

    private String cleaned(String name) {
        String s = PARENTHESISED.matcher(name).replaceAll(" ");
        for (Pattern p : warningMarkers) {
            s = p.matcher(s).replaceAll(" ");
        }
        return s.isBlank() ? name : s;
    }

    private static boolean in(String status, List<String> statuses) {
        return status != null && statuses.stream().anyMatch(status::equalsIgnoreCase);
    }

    private static String canonical(String companyNumber) {
        if (companyNumber == null || companyNumber.isBlank()) {
            return null;
        }
        try {
            return CompanyNumber.parse(companyNumber).value();
        } catch (InvalidCompanyNumberException e) {
            return companyNumber.trim().toUpperCase(Locale.ROOT);
        }
    }

    private static String postcodeKey(String postcode) {
        return postcode.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
