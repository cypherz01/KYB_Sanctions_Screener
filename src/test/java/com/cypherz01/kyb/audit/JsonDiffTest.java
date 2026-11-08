package com.cypherz01.kyb.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.cypherz01.kyb.audit.JsonDiff.Difference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonDiffTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void equalDocumentsHaveNoDifferences() throws Exception {
        assertThat(diff("{\"a\":1,\"b\":[{\"c\":\"x\"}]}", "{\"a\":1,\"b\":[{\"c\":\"x\"}]}"))
                .isEmpty();
    }

    @Test
    void reportsAChangedValueWithBothSides() throws Exception {
        Difference d = diff("{\"score\":70}", "{\"score\":72}").get(0);

        assertThat(d.path()).isEqualTo("score");
        assertThat(d.original().asInt()).isEqualTo(70);
        assertThat(d.replayed().asInt()).isEqualTo(72);
    }

    @Test
    void pathsReachIntoObjectsAndArrays() throws Exception {
        List<Difference> diffs = diff(
                "{\"factors\":[{\"code\":\"A\",\"points\":10},{\"code\":\"B\",\"points\":5}]}",
                "{\"factors\":[{\"code\":\"A\",\"points\":10},{\"code\":\"B\",\"points\":6}]}");

        assertThat(diffs).extracting(Difference::path).containsExactly("factors[1].points");
    }

    @Test
    void aMissingFieldHasNullOnThatSide() throws Exception {
        List<Difference> removed = diff("{\"a\":1,\"b\":2}", "{\"a\":1}");
        List<Difference> added = diff("{\"a\":1}", "{\"a\":1,\"b\":2}");

        assertThat(removed.get(0).path()).isEqualTo("b");
        assertThat(removed.get(0).replayed()).isNull();
        assertThat(added.get(0).path()).isEqualTo("b");
        assertThat(added.get(0).original()).isNull();
    }

    @Test
    void anArrayOfADifferentLengthReportsTheExtraElements() throws Exception {
        List<Difference> diffs = diff("{\"f\":[1]}", "{\"f\":[1,2,3]}");

        assertThat(diffs).extracting(Difference::path).containsExactly("f[1]", "f[2]");
        assertThat(diffs.get(0).original()).isNull();
    }

    @Test
    void aChangeOfTypeIsOneDifferenceAtThatPath() throws Exception {
        assertThat(diff("{\"a\":{\"b\":1}}", "{\"a\":[1]}"))
                .extracting(Difference::path)
                .containsExactly("a");
    }

    @Test
    void anExplicitNullDiffersFromAValue() throws Exception {
        assertThat(diff("{\"a\":null}", "{\"a\":1}"))
                .extracting(Difference::path)
                .containsExactly("a");
    }

    @Test
    void differentScalarDocumentsDifferAtTheRoot() throws Exception {
        assertThat(diff("1", "2")).extracting(Difference::path).containsExactly("$");
    }

    @Test
    void fieldOrderDoesNotMatter() throws Exception {
        assertThat(diff("{\"a\":1,\"b\":2}", "{\"b\":2,\"a\":1}")).isEmpty();
    }

    @Test
    void differencesComeInDocumentOrder() throws Exception {
        assertThat(diff("{\"z\":1,\"a\":[1,2],\"m\":1}", "{\"z\":2,\"a\":[1,3],\"m\":2}"))
                .extracting(Difference::path)
                .containsExactly("z", "a[1]", "m");
    }

    private List<Difference> diff(String original, String replayed) throws Exception {
        JsonNode a = json.readTree(original);
        JsonNode b = json.readTree(replayed);
        return JsonDiff.diff(a, b);
    }
}
