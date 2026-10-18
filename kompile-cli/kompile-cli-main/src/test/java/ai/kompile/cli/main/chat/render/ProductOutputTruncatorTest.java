package ai.kompile.cli.main.chat.render;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProductOutputTruncatorTest {
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final OutputTruncator truncator = new OutputTruncator();

    @Test void typedJsonAboveCodingPreviewBudgetRemainsComplete() throws Exception {
        String source = mapper.writeValueAsString(Map.of("candidateSet", Map.of("evidence", "x".repeat(24_000))));
        var result = truncator.truncateProduct(source, "mcp_tool_call", mapper);
        assertFalse(result.isTruncated());
        assertNull(result.getSavedFile());
        assertEquals(mapper.readTree(source), mapper.readTree(result.getOutput()));
        assertFalse(result.getOutput().contains("\n"));
    }

    @Test void prettyArraysDoNotLoseItemsAtTheOrdinaryLineLimit() throws Exception {
        var array = mapper.createArrayNode();
        var pretty = new StringBuilder("[\n");
        for (int i = 0; i < 2_500; i++) {
            array.add(i);
            pretty.append(i).append(i < 2_499 ? ",\n" : "\n]");
        }
        assertTrue(pretty.toString().lines().count() > 2_000);
        var result = truncator.truncateProduct(pretty.toString(), "mcp_tool_call", mapper);
        assertFalse(result.isTruncated());
        assertEquals(array, mapper.readTree(result.getOutput()));
    }

    @Test void exactUtf8BudgetIsInclusive() throws Exception {
        String source = "{\"x\":\"" + "a".repeat(65_528) + "\"}";
        assertEquals(65_536, source.getBytes(StandardCharsets.UTF_8).length);
        var result = truncator.truncateProduct(source, "mcp_tool_call", mapper);
        assertFalse(result.isTruncated());
        assertEquals(source, result.getOutput());
    }

    @Test void multibyteOversizeHasValidBoundedNonReplayGuidance() throws Exception {
        String source = mapper.writeValueAsString(Map.of("x", "漢".repeat(22_000)));
        var result = truncator.truncateProduct(source, "mcp_tool_call", mapper);
        assertTrue(result.isTruncated());
        assertNull(result.getSavedFile());
        var notice = mapper.readTree(result.getOutput());
        assertEquals("kompile.product-tool-projection.v1", notice.path("schemaVersion").asText());
        assertTrue(notice.path("projectionTruncated").asBoolean());
        assertTrue(notice.path("observedUtf8Bytes").asInt() > 65_536);
        assertTrue(notice.path("guidance").asText().contains("Do not replay a mutation"));
        assertFalse(result.getOutput().contains("漢"));
        assertTrue(result.getOutput().getBytes(StandardCharsets.UTF_8).length < 1024);
    }

    @Test void oversizedInputGuardReturnsOnlyTheBoundedProjectionNotice() throws Exception {
        String source = "{\"x\":\"" + "a".repeat(1_048_577) + "\"}";
        var result = truncator.truncateProduct(source, "mcp_tool_call", mapper);
        assertTrue(result.isTruncated());
        assertNull(result.getSavedFile());
        var notice = mapper.readTree(result.getOutput());
        assertTrue(notice.path("projectionTruncated").asBoolean());
        assertEquals(source.getBytes(StandardCharsets.UTF_8).length, notice.path("observedUtf8Bytes").asInt());
        assertTrue(result.getOutput().length() < 1024);
    }

    @Test void decimalsAndLargeIntegersRetainTheirValues() throws Exception {
        String source = "{\"chance\":0.1000000000000000000001,\"id\":123456789012345678901234567890}";
        var result = truncator.truncateProduct(source, "mcp_tool_call", mapper);
        var reader = mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        assertEquals(reader.readTree(source), reader.readTree(result.getOutput()));
    }

    @Test void textNullAndMalformedOrAmbiguousJsonStayUnmodified() {
        assertNull(truncator.truncateProduct(null, "mcp_tool_call", mapper).getOutput());
        for (String source : new String[]{"plain result", "", "{\"x\":1} trailing", "{\"x\":1,\"x\":2}", "[incomplete"}) {
            var result = truncator.truncateProduct(source, "mcp_tool_call", mapper);
            assertFalse(result.isTruncated());
            assertEquals(source, result.getOutput());
        }
        assertEquals("ordinary", truncator.truncate("ordinary", "bash").getOutput());
    }
}
