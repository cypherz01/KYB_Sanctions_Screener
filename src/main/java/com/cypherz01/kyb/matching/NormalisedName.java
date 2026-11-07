package com.cypherz01.kyb.matching;

import java.util.List;

/**
 * A name after the spec §8.1 pipeline.
 *
 * @param original the name as given
 * @param normalised ASCII, lower case, no punctuation, titles/legal forms removed; tokens in original
 *     order joined by single spaces (stored and trigram-indexed)
 * @param tokens the tokens of {@code normalised}, in order
 * @param sortedKey tokens sorted and joined, for order-insensitive comparison
 * @param phoneticTokens Double Metaphone primary key per token, aligned with {@code tokens}
 * @param phoneticKey phonetic tokens sorted and joined (stored and indexed for phonetic retrieval)
 */
public record NormalisedName(
        String original,
        String normalised,
        List<String> tokens,
        String sortedKey,
        List<String> phoneticTokens,
        String phoneticKey) {}
