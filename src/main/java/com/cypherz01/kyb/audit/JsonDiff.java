package com.cypherz01.kyb.audit;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Finds where two JSON documents differ, so a replay can say what changed rather than just "no". */
public final class JsonDiff {

    /**
     * One place the documents differ.
     *
     * @param path e.g. {@code factors[0].points}; {@code $} for the document itself
     * @param original the value in the first document, or null if absent
     * @param replayed the value in the second document, or null if absent
     */
    public record Difference(String path, JsonNode original, JsonNode replayed) {}

    private JsonDiff() {}

    /** @return every differing leaf, in document order; empty when the documents are equal */
    public static List<Difference> diff(JsonNode original, JsonNode replayed) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
