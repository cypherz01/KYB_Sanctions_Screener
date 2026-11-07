package com.cypherz01.kyb.matching;

import com.ibm.icu.text.Normalizer2;
import com.ibm.icu.text.Transliterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.codec.language.DoubleMetaphone;

/**
 * The spec §8.1 normalisation pipeline, applied identically to subjects and sanctions names:
 * transliterate any script to Latin and strip diacritics (ICU), lower-case, turn {@code &} into
 * {@code and}, drop apostrophes and dots ({@code O'Brien}, {@code S.A.}), treat other punctuation
 * as a separator, remove honorifics (people) or legal forms (organisations: trailing forms, plus
 * the few written first, such as {@code OOO}), tokenise, then compute a Double Metaphone key per
 * token. Removal never empties a name: if every token is a title or legal form, the tokens are
 * kept. {@link SubjectType#ANY} applies both rule sets.
 *
 * <p>Thread-safe and stateless after construction.
 */
public class NameNormaliser {

    /**
     * Bumped whenever the pipeline's output changes. Dataset versions record the version their
     * stored names were normalised with, and stale ones are re-indexed.
     */
    public static final String VERSION = "1";

    /*
     * Russian BGN/PCGN first, because sanctions lists romanise Russian that way (Zhukov, Khodorkovsky,
     * Yevgeny). ICU's generic Cyrillic-Latin would give Zukov/Hodorkovskij. Then any remaining
     * script goes to Latin, and Latin to ASCII (also strips diacritics). ICU transliterators are
     * not documented as thread-safe, so each thread gets its own.
     */
    private static final ThreadLocal<Transliterator> TO_ASCII =
            ThreadLocal.withInitial(() -> Transliterator.getInstance("Russian-Latin/BGN; Any-Latin; Latin-ASCII"));
    private static final Normalizer2 NFKD = Normalizer2.getNFKDInstance();
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    // Joining marks: apostrophes, dots, BGN's middle dot and the soft/hard sign primes.
    private static final Pattern JOINERS = Pattern.compile("['`´‘’.·ʹʺʼ\"]");
    private static final Pattern SEPARATORS = Pattern.compile("[^a-z0-9]+");

    private final Set<String> honorifics;
    private final List<List<String>> trailingForms;
    private final List<List<String>> leadingForms;
    private final DoubleMetaphone metaphone = new DoubleMetaphone();

    public NameNormaliser(List<String> honorifics, List<String> legalForms, List<String> leadingLegalForms) {
        this.honorifics = new HashSet<>(honorifics);
        this.trailingForms = phrases(legalForms);
        this.leadingForms = phrases(leadingLegalForms);
    }

    /** @return the normalised name. Tokens are empty if nothing usable remains (e.g. blank input). */
    public NormalisedName normalise(String name, SubjectType type) {
        List<String> tokens = tokenise(name == null ? "" : name);
        if (type != SubjectType.ORGANISATION) {
            tokens = removeHonorifics(tokens);
        }
        if (type != SubjectType.PERSON) {
            tokens = removeLegalForms(tokens);
        }
        return fromTokens(name, tokens);
    }

    /**
     * Rebuilds a normalised name from tokens stored by an earlier {@link #normalise} (no
     * transliteration needed), for scoring retrieved candidates.
     */
    public NormalisedName fromTokens(String original, List<String> tokens) {
        List<String> copy = List.copyOf(tokens);
        List<String> phonetic = copy.stream().map(this::phonetic).toList();
        return new NormalisedName(
                original,
                String.join(" ", copy),
                copy,
                String.join(" ", copy.stream().sorted().toList()),
                phonetic,
                String.join(" ", phonetic.stream().sorted().toList()));
    }

    /**
     * Companies House officer names arrive as {@code SURNAME, Forenames} (spec §8.1.7).
     *
     * @return {@code "Forenames SURNAME"}, or the input unchanged if it has no comma
     */
    public static String surnameFirstToNatural(String name) {
        int comma = name.indexOf(',');
        if (comma < 0) {
            return name;
        }
        String surname = name.substring(0, comma).trim();
        String forenames = name.substring(comma + 1).trim();
        return forenames.isEmpty() ? surname : forenames + " " + surname;
    }

    private static List<String> tokenise(String name) {
        String s = TO_ASCII.get().transliterate(name);
        s = MARKS.matcher(NFKD.normalize(s)).replaceAll(""); // anything Latin-ASCII left accented
        s = s.toLowerCase(Locale.ROOT).replace("&", " and ");
        s = JOINERS.matcher(s).replaceAll("");
        s = SEPARATORS.matcher(s).replaceAll(" ").trim();
        return s.isEmpty() ? List.of() : Arrays.asList(s.split(" "));
    }

    private List<String> removeHonorifics(List<String> tokens) {
        List<String> kept = tokens.stream().filter(t -> !honorifics.contains(t)).toList();
        return kept.isEmpty() ? tokens : kept;
    }

    private List<String> removeLegalForms(List<String> tokens) {
        List<String> t = new ArrayList<>(tokens);
        boolean removed = true;
        while (removed) {
            removed = false;
            for (List<String> form : leadingForms) {
                if (t.size() > form.size() && t.subList(0, form.size()).equals(form)) {
                    t.subList(0, form.size()).clear();
                    removed = true;
                    break;
                }
            }
            for (List<String> form : trailingForms) {
                if (t.size() > form.size()
                        && t.subList(t.size() - form.size(), t.size()).equals(form)) {
                    t.subList(t.size() - form.size(), t.size()).clear();
                    removed = true;
                    break;
                }
            }
        }
        return List.copyOf(t);
    }

    private String phonetic(String token) {
        String code = metaphone.doubleMetaphone(token);
        return code == null || code.isEmpty() ? token : code; // e.g. digits have no code
    }

    /** Longest phrase first, so "limited liability company" wins over "company". */
    private static List<List<String>> phrases(List<String> forms) {
        return forms.stream()
                .map(f -> List.of(f.trim().split("\\s+")))
                .sorted(Comparator.comparingInt((List<String> p) -> p.size()).reversed())
                .toList();
    }
}
