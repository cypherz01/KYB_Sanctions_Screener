package com.cypherz01.kyb.matching;

import com.cypherz01.kyb.sanctions.DatasetVersion;
import com.cypherz01.kyb.sanctions.SanctionsRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Screens one name against the active sanctions dataset. It validates and normalises the
 * request, retrieves candidates (trigram similarity unioned with phonetic-key matches, only
 * comparable schemas, capped), scores each with {@link NameMatcher}, keeps the best name per
 * entity, and returns the banded matches.
 */
public class ScreeningService {

    private static final Pattern ISO_ALPHA2 = Pattern.compile("[A-Za-z]{2}");

    private final SanctionsRepository datasets;
    private final CandidateRetriever retriever;
    private final NameNormaliser normaliser;
    private final NameMatcher matcher;
    private final MatchingProperties props;
    private final MeterRegistry meters;

    public ScreeningService(
            SanctionsRepository datasets,
            CandidateRetriever retriever,
            NameNormaliser normaliser,
            NameMatcher matcher,
            MatchingProperties props,
            MeterRegistry meters) {
        this.datasets = datasets;
        this.retriever = retriever;
        this.normaliser = normaliser;
        this.matcher = matcher;
        this.props = props;
        this.meters = meters;
    }

    /**
     * @throws InvalidScreeningRequestException blank or unusable name, invalid birth date or country
     * @throws SanctionsDataUnavailableException no active dataset (nothing imported yet)
     */
    public ScreeningResult screen(ScreeningRequest request) {
        long start = System.nanoTime();
        MatchSubject subject = subject(request);
        DatasetVersion active = datasets.findActive()
                .orElseThrow(() -> new SanctionsDataUnavailableException(
                        "No sanctions dataset has been imported yet, so screening is unavailable"));

        List<MatchCandidate> candidates = retriever.retrieve(active.id(), subject);
        Map<Long, Scored> bestPerEntity = new LinkedHashMap<>();
        for (MatchCandidate c : candidates) {
            MatchScore s = matcher.score(subject, c);
            if (s.band() == null) {
                continue;
            }
            bestPerEntity.merge(c.entityId(), new Scored(c, s), (a, b) -> BEST.compare(a, b) <= 0 ? a : b);
        }
        List<ScreeningMatch> matches = bestPerEntity.values().stream()
                .sorted(BEST)
                .limit(props.maxMatches())
                .map(this::toMatch)
                .toList();

        Timer.builder("kyb.screening")
                .description("Single-name sanctions screens")
                .tag("outcome", matches.isEmpty() ? "no_match" : "match")
                .register(meters)
                .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        return new ScreeningResult(
                request.name(),
                subject.type(),
                subject.name().normalised(),
                active.id(),
                active.versionLabel(),
                candidates.size(),
                matches);
    }

    private record Scored(MatchCandidate candidate, MatchScore score) {}

    /** Highest similarity first; then primary names over aliases; then a stable order. */
    private static final Comparator<Scored> BEST = Comparator.comparingDouble(
                    (Scored s) -> s.score().similarity())
            .reversed()
            .thenComparing(s -> s.candidate().nameKind())
            .thenComparing(s -> s.candidate().externalId())
            .thenComparingLong(s -> s.candidate().nameId());

    private MatchSubject subject(ScreeningRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new InvalidScreeningRequestException("name must not be blank");
        }
        SubjectType type = request.type() == null ? SubjectType.ANY : request.type();
        NormalisedName name = normaliser.normalise(request.name(), type);
        if (name.tokens().isEmpty()) {
            throw new InvalidScreeningRequestException("name has nothing to screen once punctuation is removed");
        }

        PartialDate birthDate = null;
        if (request.birthDate() != null && !request.birthDate().isBlank()) {
            birthDate = PartialDate.parse(request.birthDate())
                    .orElseThrow(() ->
                            new InvalidScreeningRequestException("birthDate must be yyyy, yyyy-MM or yyyy-MM-dd"));
        }

        Set<String> countries = Set.of();
        if (request.country() != null && !request.country().isBlank()) {
            String c = request.country().trim();
            if (!ISO_ALPHA2.matcher(c).matches()) {
                throw new InvalidScreeningRequestException("country must be an ISO 3166-1 alpha-2 code, e.g. GB");
            }
            countries = Set.of(c.toLowerCase(Locale.ROOT));
        }
        return new MatchSubject(name, type, birthDate, countries);
    }

    private ScreeningMatch toMatch(Scored s) {
        MatchCandidate c = s.candidate();
        Set<String> lists = new LinkedHashSet<>();
        for (String id : c.sourceLists()) {
            lists.add(props.sourceListLabels().getOrDefault(id, id));
        }
        Set<String> countries = new LinkedHashSet<>(c.nationalities());
        countries.addAll(c.countries());
        return new ScreeningMatch(
                c.externalId(),
                c.schema(),
                c.caption(),
                c.matchedName(),
                c.nameKind(),
                Math.round(s.score().similarity() * 1000) / 1000.0,
                s.score().band(),
                new ArrayList<>(lists),
                c.programIds(),
                c.birthDates(),
                new ArrayList<>(countries),
                s.score().evidence());
    }
}
