/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.core.graphrag.format;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LlmJsonExtractor}.
 *
 * <p>Pins the tool-call-prefix regression: a naive {@code indexOf('{')} slices from the
 * first brace of a CLI-agent log line (e.g. {@code "[kompile] read | {"filePath":...}"})
 * instead of the extraction result object, which then parses into zero entities and is
 * silently treated as a successful (but empty) crawl.
 */
class LlmJsonExtractorTest {

    @Test
    void nullReturnsNull() {
        assertNull(LlmJsonExtractor.extractJsonObject(null));
    }

    @Test
    void blankReturnsNull() {
        assertNull(LlmJsonExtractor.extractJsonObject("   \n\t "));
    }

    @Test
    void cleanJsonPassedThroughUnchanged() {
        String json = "{\"entities\":[{\"id\":\"e1\",\"title\":\"Alice\"}],\"relationships\":[]}";
        assertEquals(json, LlmJsonExtractor.extractJsonObject(json));
    }

    @Test
    void stripsJsonCodeFence() {
        String inner = "{\"entities\":[{\"id\":\"e1\"}],\"relationships\":[]}";
        String fenced = "```json\n" + inner + "\n```";
        assertEquals(inner, LlmJsonExtractor.extractJsonObject(fenced));
    }

    @Test
    void skipsToolCallPrefixLineAndAnchorsOnEntities() {
        // The exact regression: a tool-call log line whose own '{' precedes the real result.
        String response = "[kompile] read | {\"filePath\":\"/tmp/x\"}\n"
                + "{\"entities\":[{\"id\":\"e1\",\"title\":\"Alice\"}],\"relationships\":[]}";

        String extracted = LlmJsonExtractor.extractJsonObject(response);

        assertTrue(extracted.startsWith("{\"entities\""),
                "should anchor on the {\"entities\" result object, got: " + extracted);
        assertTrue(extracted.contains("\"e1\""), "should retain the real entity");
        assertFalse(extracted.contains("filePath"),
                "must not slice from the tool-call log line's brace");
    }

    @Test
    void skipsLeadingProseBeforeJson() {
        String response = "Sure, here is the extracted graph:\n"
                + "{\"entities\":[{\"id\":\"n1\"}],\"relationships\":[]}";

        String extracted = LlmJsonExtractor.extractJsonObject(response);

        assertTrue(extracted.startsWith("{\"entities\""), "got: " + extracted);
        assertFalse(extracted.contains("Sure"));
    }

    @Test
    void dropsTrailingLogLinesAfterJson() {
        String response = "{\"entities\":[{\"id\":\"e1\"}],\"relationships\":[]}\n"
                + "[kompile] extraction complete";

        String extracted = LlmJsonExtractor.extractJsonObject(response);

        assertTrue(extracted.endsWith("}"), "should end at the JSON's closing brace, got: " + extracted);
        assertFalse(extracted.contains("complete"));
    }

    @Test
    void completesOnlyMissingFinalContainerClosures() {
        String missingOuterBrace = """
                {"selection":{"decision":"SELECT","candidateOrdinal":1,"schemaGap":false,
                 "confidence":0.5,"qualifiers":{},"reason":"source-grounded match"}
                """.strip();
        String missingArrayAndRoot = """
                {"propositions":[{"text":"Acme acquired Initech","subject":"Acme",
                 "predicate":"acquired","object":"Initech"}
                """.strip();

        assertEquals(missingOuterBrace + "}", LlmJsonExtractor.extractJsonObject(missingOuterBrace));
        assertEquals(missingArrayAndRoot + "]}",
                LlmJsonExtractor.extractJsonObject(missingArrayAndRoot));
    }

    @Test
    void neverInventsTruncatedJsonContent() {
        String truncatedString = "{\"selection\":{\"reason\":\"source-grounded";
        String missingValue = "{\"selection\":{\"candidateOrdinal\":";

        assertEquals(truncatedString, LlmJsonExtractor.extractJsonObject(truncatedString));
        assertEquals(missingValue, LlmJsonExtractor.extractJsonObject(missingValue));
    }

    @Test
    void fallsBackToFirstBraceWhenEntitiesNotFirstKey() {
        // No literal {"entities" adjacency (relationships come first) -> line-walk fallback
        // must still grab the root object's opening brace, not fail.
        String response = "{\"relationships\":[],\"entities\":[{\"id\":\"e1\"}]}";
        assertEquals(response, LlmJsonExtractor.extractJsonObject(response));
    }
}
