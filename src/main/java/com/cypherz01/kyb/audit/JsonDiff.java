package com.cypherz01.kyb.audit;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
        List<Difference> differences = new ArrayList<>();
        walk("", original, replayed, differences);
        return List.copyOf(differences);
    }

    private static void walk(String path, JsonNode a, JsonNode b, List<Difference> out) {
        if (Objects.equals(a, b)) {
            return;
        }
        if (a != null && b != null && a.isObject() && b.isObject()) {
            a.fieldNames().forEachRemaining(name -> walk(field(path, name), a.get(name), b.get(name), out));
            b.fieldNames().forEachRemaining(name -> {
                if (!a.has(name)) {
                    walk(field(path, name), null, b.get(name), out);
                }
            });
        } else if (a != null && b != null && a.isArray() && b.isArray()) {
            for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
                walk(path + "[" + i + "]", a.get(i), b.get(i), out);
            }
        } else {
            out.add(new Difference(path.isEmpty() ? "$" : path, a, b));
        }
    }

    private static String field(String path, String name) {
        return path.isEmpty() ? name : path + "." + name;
    }
}
