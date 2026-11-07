package com.cypherz01.kyb.matching;

import java.util.List;

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

    public NameNormaliser(List<String> honorifics, List<String> legalForms, List<String> leadingLegalForms) {}

    /** @return the normalised name. Tokens are empty if nothing usable remains (e.g. blank input). */
    public NormalisedName normalise(String name, SubjectType type) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    /**
     * Rebuilds a normalised name from tokens stored by an earlier {@link #normalise} (no
     * transliteration needed), for scoring retrieved candidates.
     */
    public NormalisedName fromTokens(String original, List<String> tokens) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    /**
     * Companies House officer names arrive as {@code SURNAME, Forenames} (spec §8.1.7).
     *
     * @return {@code "Forenames SURNAME"}, or the input unchanged if it has no comma
     */
    public static String surnameFirstToNatural(String name) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
