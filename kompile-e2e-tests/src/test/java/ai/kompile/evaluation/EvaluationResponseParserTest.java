/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EvaluationResponseParserTest {

    @Test
    void testParseWellFormedJson() {
        String json = "{\"score\": 0.85, \"explanation\": \"Good answer\"}";
        JsonNode node = EvaluationResponseParser.parse(json);
        assertNotNull(node);
        assertEquals(0.85, EvaluationResponseParser.getDouble(node, "score", 0.0));
        assertEquals("Good answer", EvaluationResponseParser.getString(node, "explanation", null));
    }

    @Test
    void testParseMarkdownFencedJson() {
        String json = "```json\n{\"score\": 0.9, \"explanation\": \"Very relevant\"}\n```";
        JsonNode node = EvaluationResponseParser.parse(json);
        assertNotNull(node);
        assertEquals(0.9, EvaluationResponseParser.getDouble(node, "score", 0.0));
    }

    @Test
    void testParseJsonWithSurroundingText() {
        String json = "Here is my evaluation:\n{\"score\": 0.7, \"explanation\": \"Mostly ok\"}\nDone.";
        JsonNode node = EvaluationResponseParser.parse(json);
        assertNotNull(node);
        assertEquals(0.7, EvaluationResponseParser.getDouble(node, "score", 0.0));
    }

    @Test
    void testParseMissingField() {
        String json = "{\"score\": 0.5}";
        JsonNode node = EvaluationResponseParser.parse(json);
        assertNotNull(node);
        assertEquals("default", EvaluationResponseParser.getString(node, "explanation", "default"));
        assertEquals(0, EvaluationResponseParser.getInt(node, "totalClaims", 0));
    }

    @Test
    void testParseNonNumericScore() {
        String json = "{\"score\": \"not a number\", \"explanation\": \"test\"}";
        JsonNode node = EvaluationResponseParser.parse(json);
        assertNotNull(node);
        assertEquals(-1.0, EvaluationResponseParser.getDouble(node, "score", -1.0));
    }

    @Test
    void testParseNullResponse() {
        assertNull(EvaluationResponseParser.parse(null));
    }

    @Test
    void testParseEmptyResponse() {
        assertNull(EvaluationResponseParser.parse(""));
        assertNull(EvaluationResponseParser.parse("   "));
    }

    @Test
    void testParseCompletelyInvalidJson() {
        assertNull(EvaluationResponseParser.parse("This is not JSON at all"));
    }

    @Test
    void testGetStringArray() {
        String json = "{\"hallucinations\": [\"claim 1\", \"claim 2\", \"claim 3\"]}";
        JsonNode node = EvaluationResponseParser.parse(json);
        assertNotNull(node);
        List<String> items = EvaluationResponseParser.getStringArray(node, "hallucinations");
        assertEquals(3, items.size());
        assertEquals("claim 1", items.get(0));
    }

    @Test
    void testGetStringArrayMissing() {
        String json = "{\"score\": 0.5}";
        JsonNode node = EvaluationResponseParser.parse(json);
        assertNotNull(node);
        List<String> items = EvaluationResponseParser.getStringArray(node, "hallucinations");
        assertTrue(items.isEmpty());
    }

    @Test
    void testGetStringArrayFromNull() {
        List<String> items = EvaluationResponseParser.getStringArray(null, "field");
        assertTrue(items.isEmpty());
    }

    @Test
    void testGetDoubleFromStringNumber() {
        String json = "{\"score\": \"0.75\"}";
        JsonNode node = EvaluationResponseParser.parse(json);
        assertNotNull(node);
        assertEquals(0.75, EvaluationResponseParser.getDouble(node, "score", 0.0));
    }

    @Test
    void testGetIntFromNode() {
        String json = "{\"totalClaims\": 5, \"supportedClaims\": 3}";
        JsonNode node = EvaluationResponseParser.parse(json);
        assertNotNull(node);
        assertEquals(5, EvaluationResponseParser.getInt(node, "totalClaims", 0));
        assertEquals(3, EvaluationResponseParser.getInt(node, "supportedClaims", 0));
    }

    @Test
    void testParseWithMarkdownFencesNoLanguage() {
        String json = "```\n{\"score\": 0.6}\n```";
        JsonNode node = EvaluationResponseParser.parse(json);
        assertNotNull(node);
        assertEquals(0.6, EvaluationResponseParser.getDouble(node, "score", 0.0));
    }
}
