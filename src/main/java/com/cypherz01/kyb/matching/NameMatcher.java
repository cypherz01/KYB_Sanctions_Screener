package com.cypherz01.kyb.matching;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;

/**
 * Stage 2 of matching (spec §8.3): scores one candidate against the subject in Java. It combines
 * token-sorted Jaro-Winkler, token overlap, phonetic agreement and per-token fuzzy alignment with
 * the configured weights, then adjusts for birth date and country agreement. A schema the subject
 * type cannot match is excluded outright. Deterministic and side-effect free, so a stored check
 * can be re-scored exactly (M6).
 */
public class NameMatcher {

    private static final JaroWinklerSimilarity JW = new JaroWinklerSimilarity();
    private static final ComponentScores ZERO = new ComponentScores(0, 0, 0, 0);

    private final MatchingProperties props;

    public NameMatcher(MatchingProperties properties) {
        this.props = properties;
    }

    /** The POSSIBLE band threshold: below it two names are not treated as the same. */
    public double possibleThreshold() {
        return props.bands().possible();
    }

    public MatchScore score(MatchSubject subject, MatchCandidate candidate) {
        if (!subject.type().schemas().contains(candidate.schema())) {
            return new MatchScore(
                    0,
                    0,
                    null,
                    ZERO,
                    List.of("Excluded: entity type mismatch (" + describe(subject.type()) + " vs sanctioned "
                            + candidate.schema() + ")"));
        }
        NormalisedName a = subject.name();
        NormalisedName b = candidate.name();
        if (a.tokens().isEmpty() || b.tokens().isEmpty()) {
            return new MatchScore(0, 0, null, ZERO, List.of("No comparable name tokens"));
        }

        List<String> evidence = new ArrayList<>();
        evidence.add(relation(a, b, candidate.matchedName()));

        // Align the shorter name's tokens one-to-one with the longer name's (subject first on ties).
        boolean subjectShorter = a.tokens().size() <= b.tokens().size();
        NormalisedName shorter = subjectShorter ? a : b;
        NormalisedName longer = subjectShorter ? b : a;
        List<Pair> pairs = align(shorter, longer);

        int n = shorter.tokens().size();
        double overlap = pairs.stream().filter(p -> p.kind == Kind.EXACT).count() / (double) n;
        double phonetic = pairs.stream().filter(p -> p.samePhonetic).count() / (double) n;
        double alignment = pairs.stream().mapToDouble(p -> p.similarity).sum() / n;
        double jaroWinkler = alignedJaroWinkler(pairs, shorter, longer, subjectShorter);
        ComponentScores components = new ComponentScores(jaroWinkler, overlap, phonetic, alignment);

        MatchingProperties.Weights w = props.weights();
        double weightSum = w.jaroWinkler() + w.tokenOverlap() + w.phonetic() + w.alignment();
        double name = (w.jaroWinkler() * jaroWinkler
                        + w.tokenOverlap() * overlap
                        + w.phonetic() * phonetic
                        + w.alignment() * alignment)
                / weightSum;
        // Unmatched extra tokens (a middle name on one side) cost a little, never the match.
        double coverage = n / (double) longer.tokens().size();
        name *= 1 - props.tokens().extraTokenPenalty() * (1 - coverage);
        name = clamp(name);

        pairEvidence(pairs, subjectShorter, evidence);
        extraTokenEvidence(pairs, longer, subjectShorter, evidence);

        double similarity = name;
        similarity += birthDateAdjustment(subject, candidate, evidence);
        similarity += countryAdjustment(subject, candidate, evidence);
        similarity = clamp(similarity);

        // Too little of the name to identify anyone: at most POSSIBLE (spec §8.4 initials).
        String cap = null;
        if (pairs.stream().anyMatch(p -> p.kind == Kind.INITIAL)) {
            cap = "part of the match rests on an initial";
        } else if (n == 1 && longer.tokens().size() > 1) {
            cap = "only one name token could be compared";
        }
        if (cap != null && similarity >= props.bands().strong()) {
            similarity = Math.nextDown(props.bands().strong());
            evidence.add("Capped below STRONG because " + cap);
        }

        evidence.add(String.format(
                Locale.ROOT,
                "Name similarity %.2f (jaro-winkler %.2f, token overlap %.2f, phonetic %.2f, alignment %.2f)",
                name,
                jaroWinkler,
                overlap,
                phonetic,
                alignment));
        return new MatchScore(name, similarity, band(similarity), components, List.copyOf(evidence));
    }

    // ---------- name ----------

    private enum Kind {
        EXACT,
        PHONETIC,
        FUZZY,
        INITIAL
    }

    /** One aligned token pair: {@code s} from the shorter name, {@code l} from the longer. */
    private record Pair(int si, int li, String s, String l, double similarity, Kind kind, boolean samePhonetic) {}

    /** Greedy one-to-one assignment, best pairs first (ties broken by position: deterministic). */
    private List<Pair> align(NormalisedName shorter, NormalisedName longer) {
        List<Pair> all = new ArrayList<>();
        for (int i = 0; i < shorter.tokens().size(); i++) {
            for (int j = 0; j < longer.tokens().size(); j++) {
                all.add(pair(i, j, shorter, longer));
            }
        }
        all.sort(Comparator.comparingDouble(Pair::similarity)
                .reversed()
                .thenComparingInt(Pair::si)
                .thenComparingInt(Pair::li));
        Set<Integer> usedS = new HashSet<>();
        Set<Integer> usedL = new HashSet<>();
        List<Pair> chosen = new ArrayList<>();
        for (Pair p : all) {
            if (!usedS.contains(p.si) && !usedL.contains(p.li)) {
                usedS.add(p.si);
                usedL.add(p.li);
                chosen.add(p);
            }
        }
        chosen.sort(Comparator.comparingInt(Pair::si));
        return chosen;
    }

    private Pair pair(int i, int j, NormalisedName shorter, NormalisedName longer) {
        String s = shorter.tokens().get(i);
        String l = longer.tokens().get(j);
        boolean samePhonetic =
                shorter.phoneticTokens().get(i).equals(longer.phoneticTokens().get(j));
        if (s.equals(l)) {
            return new Pair(i, j, s, l, 1.0, Kind.EXACT, true);
        }
        if (s.length() == 1 || l.length() == 1) {
            boolean initial = s.length() == 1 ? l.startsWith(s) : s.startsWith(l);
            return new Pair(i, j, s, l, initial ? props.tokens().initialSimilarity() : 0, Kind.INITIAL, false);
        }
        double sim = JW.apply(s, l);
        if (samePhonetic && sim < props.tokens().phoneticSimilarity()) {
            return new Pair(i, j, s, l, props.tokens().phoneticSimilarity(), Kind.PHONETIC, true);
        }
        return new Pair(i, j, s, l, sim, samePhonetic ? Kind.PHONETIC : Kind.FUZZY, samePhonetic);
    }

    /**
     * Token-sorted Jaro-Winkler, sorted by the alignment: both names are written in the order of the
     * longer name's sorted aligned tokens, then the unmatched tokens. Plain alphabetical sorting
     * would let a spelling variant reorder one side (Yevgeny Prigozhin sorts as "prigozhin yevgeny",
     * Evgeny Prigozhin as "evgeny prigozhin") and wreck the comparison.
     */
    private static double alignedJaroWinkler(
            List<Pair> pairs, NormalisedName shorter, NormalisedName longer, boolean subjectShorter) {
        List<Pair> ordered = new ArrayList<>(pairs);
        ordered.sort(Comparator.comparing(Pair::l).thenComparing(Pair::s));
        StringBuilder s = new StringBuilder();
        StringBuilder l = new StringBuilder();
        Set<Integer> usedS = new HashSet<>();
        Set<Integer> usedL = new HashSet<>();
        for (Pair p : ordered) {
            s.append(p.s).append(' ');
            l.append(p.l).append(' ');
            usedS.add(p.si);
            usedL.add(p.li);
        }
        appendUnused(s, shorter.tokens(), usedS);
        appendUnused(l, longer.tokens(), usedL);
        return JW.apply(s.toString().trim(), l.toString().trim());
    }

    private static void appendUnused(StringBuilder sb, List<String> tokens, Set<Integer> used) {
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (!used.contains(i)) {
                rest.add(tokens.get(i));
            }
        }
        rest.stream().sorted().forEach(t -> sb.append(t).append(' '));
    }

    private static String relation(NormalisedName a, NormalisedName b, String matchedName) {
        String subjectRaw = a.original() == null ? "" : a.original().trim();
        if (subjectRaw.equalsIgnoreCase(matchedName == null ? "" : matchedName.trim())) {
            return "Names identical";
        }
        if (a.normalised().equals(b.normalised()) || a.sortedKey().equals(b.sortedKey())) {
            String how = nonLatin(subjectRaw) || nonLatin(matchedName)
                    ? "after transliteration"
                    : nonAscii(subjectRaw) || nonAscii(matchedName)
                            ? "after removing diacritics"
                            : "after normalisation (case, punctuation, titles, legal forms)";
            if (!a.normalised().equals(b.normalised())) {
                return "Same name in a different word order, " + how;
            }
            return "Normalised names identical " + how;
        }
        return "Names similar but not identical";
    }

    private static void pairEvidence(List<Pair> pairs, boolean subjectShorter, List<String> evidence) {
        for (Pair p : pairs) {
            String subj = subjectShorter ? p.s : p.l;
            String cand = subjectShorter ? p.l : p.s;
            switch (p.kind) {
                case EXACT -> {}
                case PHONETIC ->
                    evidence.add(String.format(
                            Locale.ROOT, "Phonetic match: '%s' ~ '%s' (similarity %.2f)", subj, cand, p.similarity));
                case FUZZY ->
                    evidence.add(String.format(
                            Locale.ROOT,
                            "Spelling difference: '%s' vs '%s' (similarity %.2f)",
                            subj,
                            cand,
                            p.similarity));
                case INITIAL ->
                    evidence.add(
                            p.similarity > 0
                                    ? "Initial '" + subj + "' matches '" + cand + "' (weak)"
                                    : "Initial '" + subj + "' does not match '" + cand + "'");
            }
        }
    }

    private static void extraTokenEvidence(
            List<Pair> pairs, NormalisedName longer, boolean subjectShorter, List<String> evidence) {
        Set<Integer> used = new HashSet<>();
        pairs.forEach(p -> used.add(p.li));
        for (int i = 0; i < longer.tokens().size(); i++) {
            if (!used.contains(i)) {
                evidence.add("'" + longer.tokens().get(i) + "' appears only in the "
                        + (subjectShorter ? "sanctions" : "subject") + " name");
            }
        }
    }

    // ---------- secondary identifiers ----------

    private enum DateAgreement {
        EXACT,
        PARTIAL,
        NEUTRAL,
        MISMATCH
    }

    private double birthDateAdjustment(MatchSubject subject, MatchCandidate candidate, List<String> evidence) {
        PartialDate mine = subject.birthDate();
        if (mine == null) {
            return 0;
        }
        DateAgreement best = null;
        PartialDate bestDate = null;
        for (String text : candidate.birthDates()) {
            Optional<PartialDate> listed = PartialDate.parse(text);
            if (listed.isEmpty()) {
                continue; // e.g. "circa 1970": unusable, ignored
            }
            DateAgreement agreement = agreement(mine, listed.get());
            if (best == null || agreement.ordinal() < best.ordinal()) {
                best = agreement;
                bestDate = listed.get();
            }
        }
        if (best == null) {
            return 0;
        }
        MatchingProperties.BirthDate cfg = props.birthDate();
        return switch (best) {
            case EXACT -> {
                evidence.add("Birth date matches exactly (" + mine + ")");
                yield cfg.exactBoost();
            }
            case PARTIAL -> {
                evidence.add((mine.month() != null && bestDate.month() != null
                                ? "Birth year and month match ("
                                : "Birth year matches (")
                        + common(mine, bestDate) + ")");
                yield cfg.partialBoost();
            }
            case NEUTRAL -> {
                evidence.add("Birth dates close (" + mine + " vs " + bestDate + "), within tolerance");
                yield 0;
            }
            case MISMATCH -> {
                evidence.add("Birth year differs (" + mine.year() + " vs " + bestDate.year()
                        + "): likely a different person");
                yield -cfg.mismatchPenalty();
            }
        };
    }

    private DateAgreement agreement(PartialDate a, PartialDate b) {
        if (Math.abs(a.year() - b.year()) > props.birthDate().yearTolerance()) {
            return DateAgreement.MISMATCH;
        }
        if (a.year() != b.year()) {
            return DateAgreement.NEUTRAL;
        }
        if (a.month() != null && b.month() != null && !a.month().equals(b.month())) {
            return DateAgreement.NEUTRAL;
        }
        if (a.day() != null && b.day() != null) {
            return a.day().equals(b.day()) ? DateAgreement.EXACT : DateAgreement.NEUTRAL;
        }
        return DateAgreement.PARTIAL; // agree as far as both are known
    }

    private static String common(PartialDate a, PartialDate b) {
        return a.month() != null && b.month() != null
                ? new PartialDate(a.year(), a.month(), null).toString()
                : Integer.toString(a.year());
    }

    private double countryAdjustment(MatchSubject subject, MatchCandidate candidate, List<String> evidence) {
        if (subject.countries().isEmpty()) {
            return 0;
        }
        Set<String> theirs = new LinkedHashSet<>(candidate.nationalities());
        theirs.addAll(candidate.countries());
        if (theirs.isEmpty()) {
            return 0;
        }
        String label = candidate.schema().equals("Person") ? "Nationality" : "Country";
        List<String> shared =
                subject.countries().stream().filter(theirs::contains).sorted().toList();
        if (!shared.isEmpty()) {
            evidence.add(label + " matches (" + upper(shared) + ")");
            return props.country().matchBoost();
        }
        evidence.add(label + " differs ("
                + upper(subject.countries().stream().sorted().toList()) + " vs " + upper(List.copyOf(theirs)) + ")");
        return -props.country().mismatchPenalty();
    }

    // ---------- helpers ----------

    private MatchBand band(double similarity) {
        if (similarity >= props.bands().strong()) {
            return MatchBand.STRONG;
        }
        if (similarity >= props.bands().possible()) {
            return MatchBand.POSSIBLE;
        }
        return null;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static String upper(List<String> codes) {
        return String.join(", ", codes).toUpperCase(Locale.ROOT);
    }

    private static boolean nonAscii(String s) {
        return s != null && s.chars().anyMatch(c -> c > 127);
    }

    private static boolean nonLatin(String s) {
        return s != null
                && s.codePoints()
                        .filter(Character::isLetter)
                        .anyMatch(c -> Character.UnicodeScript.of(c) != Character.UnicodeScript.LATIN);
    }

    private static String describe(SubjectType type) {
        return switch (type) {
            case PERSON -> "subject is a person";
            case ORGANISATION -> "subject is an organisation";
            case ANY -> "subject";
        };
    }
}
