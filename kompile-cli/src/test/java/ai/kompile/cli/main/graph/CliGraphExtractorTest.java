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
package ai.kompile.cli.main.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CliGraphExtractor} — focuses on JSON parsing logic,
 * prompt building, and edge cases. Does NOT require a live LLM or server.
 */
class CliGraphExtractorTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // extractJson — strips markdown fences and finds JSON in mixed responses
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testExtractJsonPlainJson() {
        String json = """
                {"entities": [], "relations": []}""";
        assertEquals(json.trim(), CliGraphExtractor.extractJson(json));
    }

    @Test
    void testExtractJsonWithMarkdownFences() {
        String response = """
                ```json
                {"entities": [{"id":"e1","name":"Alice"}], "relations": []}
                ```""";
        String extracted = CliGraphExtractor.extractJson(response);
        assertTrue(extracted.startsWith("{"), "Should start with {");
        assertTrue(extracted.contains("\"Alice\""), "Should contain entity name");
        assertFalse(extracted.contains("```"), "Should not contain markdown fences");
    }

    @Test
    void testExtractJsonWithSurroundingText() {
        String response = "Here is the extracted data:\n{\"entities\": [], \"relations\": []}\nDone.";
        String extracted = CliGraphExtractor.extractJson(response);
        assertTrue(extracted.startsWith("{"));
        assertTrue(extracted.endsWith("}"));
    }

    @Test
    void testExtractJsonNullOrEmpty() {
        assertEquals("{}", CliGraphExtractor.extractJson(null));
        assertEquals("{}", CliGraphExtractor.extractJson(""));
        assertEquals("{}", CliGraphExtractor.extractJson("   "));
    }

    @Test
    void testExtractJsonTripleFencesNoLanguage() {
        String response = "```\n{\"entities\": [{\"id\":\"e1\"}]}\n```";
        String extracted = CliGraphExtractor.extractJson(response);
        assertTrue(extracted.contains("\"e1\""));
        assertFalse(extracted.contains("```"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // parseResponse — full extraction result parsing
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testParseResponseValidJson() {
        // Use a null LLM client since we're only testing parseResponse
        CliGraphExtractor extractor = new CliGraphExtractor(null);
        String json = """
                {
                  "entities": [
                    {"id": "e1", "name": "Alice", "type": "PERSON", "description": "A software engineer", "confidence": 0.9},
                    {"id": "e2", "name": "Acme Corp", "type": "ORGANIZATION", "description": "A tech company", "confidence": 0.85}
                  ],
                  "relations": [
                    {"source": "e1", "target": "e2", "type": "WORKS_AT", "description": "Employment", "confidence": 0.88}
                  ]
                }""";

        CliGraphExtractor.ExtractionResult result = extractor.parseResponse(json);

        assertFalse(result.hasError());
        assertEquals(2, result.entityCount());
        assertEquals(1, result.relationCount());

        assertEquals("Alice", result.entities.get(0).name);
        assertEquals("PERSON", result.entities.get(0).type);
        assertEquals(0.9, result.entities.get(0).confidence, 0.001);

        assertEquals("Acme Corp", result.entities.get(1).name);
        assertEquals("ORGANIZATION", result.entities.get(1).type);

        assertEquals("e1", result.relations.get(0).source);
        assertEquals("e2", result.relations.get(0).target);
        assertEquals("WORKS_AT", result.relations.get(0).type);
    }

    @Test
    void testParseResponseWithAliases() {
        CliGraphExtractor extractor = new CliGraphExtractor(null);
        String json = """
                {
                  "entities": [
                    {"id": "e1", "name": "Acme Corp", "type": "ORGANIZATION", "description": "Tech co",
                     "aliases": ["Acme", "ACME Corporation"], "confidence": 0.95}
                  ],
                  "relations": []
                }""";

        CliGraphExtractor.ExtractionResult result = extractor.parseResponse(json);
        assertEquals(1, result.entityCount());
        assertNotNull(result.entities.get(0).aliases);
        assertEquals(2, result.entities.get(0).aliases.size());
        assertEquals("Acme", result.entities.get(0).aliases.get(0));
    }

    @Test
    void testParseResponseWithProperties() {
        CliGraphExtractor extractor = new CliGraphExtractor(null);
        String json = """
                {
                  "entities": [
                    {"id": "e1", "name": "Alice", "type": "PERSON", "description": "Engineer",
                     "properties": {"title": "CTO", "department": "Engineering"}}
                  ],
                  "relations": [
                    {"source": "e1", "target": "e2", "type": "MANAGES", "description": "Management",
                     "properties": {"since": "2020"}}
                  ]
                }""";

        CliGraphExtractor.ExtractionResult result = extractor.parseResponse(json);
        assertEquals("CTO", result.entities.get(0).properties.get("title"));
        assertEquals("2020", result.relations.get(0).properties.get("since"));
    }

    @Test
    void testParseResponseAlternateRelationshipsKey() {
        // Some LLMs use "relationships" instead of "relations"
        CliGraphExtractor extractor = new CliGraphExtractor(null);
        String json = """
                {
                  "entities": [
                    {"id": "e1", "name": "X", "type": "PERSON", "description": "A person"}
                  ],
                  "relationships": [
                    {"source": "e1", "target": "e2", "type": "KNOWS", "description": "Knows"}
                  ]
                }""";

        CliGraphExtractor.ExtractionResult result = extractor.parseResponse(json);
        assertEquals(1, result.relationCount(), "Should parse 'relationships' key");
    }

    @Test
    void testParseResponseInvalidJson() {
        CliGraphExtractor extractor = new CliGraphExtractor(null);
        String garbage = "This is not JSON at all, just random text without braces";

        CliGraphExtractor.ExtractionResult result = extractor.parseResponse(garbage);
        assertTrue(result.hasError());
        assertNotNull(result.parseError);
        assertEquals(0, result.entityCount());
        assertEquals(0, result.relationCount());
    }

    @Test
    void testParseResponseEmptyArrays() {
        CliGraphExtractor extractor = new CliGraphExtractor(null);
        String json = "{\"entities\": [], \"relations\": []}";

        CliGraphExtractor.ExtractionResult result = extractor.parseResponse(json);
        assertFalse(result.hasError());
        assertEquals(0, result.entityCount());
        assertEquals(0, result.relationCount());
    }

    @Test
    void testParseResponseSkipsIncompleteEntities() {
        CliGraphExtractor extractor = new CliGraphExtractor(null);
        // Entity without id or name should be skipped
        String json = """
                {
                  "entities": [
                    {"type": "PERSON", "description": "No id or name"},
                    {"id": "e1", "name": "Valid", "type": "PERSON", "description": "Has required fields"}
                  ],
                  "relations": []
                }""";

        CliGraphExtractor.ExtractionResult result = extractor.parseResponse(json);
        assertEquals(1, result.entityCount(), "Should skip entity without id/name");
        assertEquals("Valid", result.entities.get(0).name);
    }

    @Test
    void testParseResponseSkipsIncompleteRelations() {
        CliGraphExtractor extractor = new CliGraphExtractor(null);
        // Relation without type should be skipped
        String json = """
                {
                  "entities": [],
                  "relations": [
                    {"source": "e1", "target": "e2"},
                    {"source": "e1", "target": "e2", "type": "KNOWS", "description": "Valid"}
                  ]
                }""";

        CliGraphExtractor.ExtractionResult result = extractor.parseResponse(json);
        assertEquals(1, result.relationCount(), "Should skip relation without type");
    }

    @Test
    void testParseResponseWithMarkdownFences() {
        CliGraphExtractor extractor = new CliGraphExtractor(null);
        String response = """
                Here is the extraction:
                ```json
                {
                  "entities": [{"id": "e1", "name": "Test", "type": "CONCEPT", "description": "A test"}],
                  "relations": []
                }
                ```
                That's the result.""";

        CliGraphExtractor.ExtractionResult result = extractor.parseResponse(response);
        assertFalse(result.hasError());
        assertEquals(1, result.entityCount());
        assertEquals("Test", result.entities.get(0).name);
    }

    @Test
    void testParseResponseDefaultConfidence() {
        CliGraphExtractor extractor = new CliGraphExtractor(null);
        String json = """
                {
                  "entities": [{"id": "e1", "name": "X", "type": "THING", "description": "No confidence"}],
                  "relations": [{"source": "e1", "target": "e2", "type": "REL", "description": "No conf"}]
                }""";

        CliGraphExtractor.ExtractionResult result = extractor.parseResponse(json);
        assertEquals(1.0, result.entities.get(0).confidence, 0.001, "Default confidence should be 1.0");
        assertEquals(1.0, result.relations.get(0).confidence, 0.001, "Default confidence should be 1.0");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Extraction prompt instructions
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testExtractionPromptContainsSchemaFields() {
        String prompt = CliGraphExtractor.EXTRACTION_PROMPT_INSTRUCTIONS;
        assertTrue(prompt.contains("\"entities\""));
        assertTrue(prompt.contains("\"relations\""));
        assertTrue(prompt.contains("\"id\""));
        assertTrue(prompt.contains("\"name\""));
        assertTrue(prompt.contains("\"type\""));
        assertTrue(prompt.contains("\"source\""));
        assertTrue(prompt.contains("\"target\""));
        assertTrue(prompt.contains("\"confidence\""));
        assertTrue(prompt.contains("UPPERCASE"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ExtractionResult data class
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testExtractionResultDefaultState() {
        CliGraphExtractor.ExtractionResult result = new CliGraphExtractor.ExtractionResult();
        assertFalse(result.hasError());
        assertEquals(0, result.entityCount());
        assertEquals(0, result.relationCount());
        assertNull(result.parseError);
        assertNull(result.rawResponse);
    }
}
