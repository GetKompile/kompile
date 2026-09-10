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

package ai.kompile.crawl.graph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorpusSchemaResponseParserTest {

    @Test
    void parsesPlainGraphSchemaJson() {
        String rawJson = """
                {
                  "nodeTypes": [
                    {
                      "label": "PERSON",
                      "description": "A person in the corpus."
                    },
                    {
                      "label": "ROLE",
                      "description": "A role in the corpus.",
                      "properties": []
                    }
                  ],
                  "relationshipTypes": [
                    {
                      "type": "HAS_ROLE",
                      "description": "A person has a role."
                    }
                  ],
                  "patterns": ["(PERSON)-[:HAS_ROLE]->(ROLE)"]
                }
                """;

        CorpusSchemaResponseParser.ParseResult result = CorpusSchemaResponseParser.parse(rawJson);
        assertNotNull(result.schema());
        assertTrue(result.errors().isEmpty());
        assertTrue(result.valid());
        assertNull(result.schema().getNodeTypes().get(0).getProperties());
        assertTrue(result.schema().getNodeTypes().get(1).getProperties().isEmpty());
        assertEquals("PERSON", result.schema().getNodeTypes().get(0).getLabel());
    }

    @Test
    void parsesStructuredToolArguments() {
        Map<String, Object> arguments = Map.of(
                "nodeTypes", List.of("person", "PERSON"),
                "relationshipTypes", List.of(),
                "patterns", List.of());

        CorpusSchemaResponseParser.ParseResult result =
                CorpusSchemaResponseParser.parse(arguments);

        assertTrue(result.valid());
        assertEquals(1, result.schema().getNodeTypes().size());
        assertEquals("PERSON", result.schema().getNodeTypes().get(0).getLabel());
        assertEquals("Corpus-derived node type PERSON.",
                result.schema().getNodeTypes().get(0).getDescription());
    }

    @Test
    void normalizesStructuredEndpointObjectsIntoGraphSchemaPatterns() {
        Map<String, Object> arguments = Map.of(
                "nodeTypes", List.of(
                        Map.of("label", "person", "description", "A person."),
                        Map.of("label", "company record", "description", "A company.",
                                "parentType", "organization")),
                "relationshipTypes", List.of(Map.of(
                        "type", "founded-by",
                        "description", "A person founded a company.",
                        "connectionFamily", "attribution")),
                "patterns", List.of(Map.of(
                        "sourceType", "person",
                        "relationshipType", "founded-by",
                        "targetType", "company record")));

        CorpusSchemaResponseParser.ParseResult result =
                CorpusSchemaResponseParser.parse(arguments);

        assertTrue(result.valid());
        assertEquals(List.of("PERSON", "COMPANY_RECORD"),
                result.schema().getNodeTypes().stream().map(type -> type.getLabel()).toList());
        assertEquals("FOUNDED_BY", result.schema().getRelationshipTypes().get(0).getType());
        assertEquals("ORGANIZATION", result.schema().getNodeTypes().get(1).getParentType());
        assertEquals("ATTRIBUTION",
                result.schema().getRelationshipTypes().get(0).getConnectionFamily());
        assertEquals(List.of("(PERSON)-[:FOUNDED_BY]->(COMPANY_RECORD)"),
                result.schema().getPatterns());
    }

    @Test
    void rejectsDuplicateLabelsWithConflictingHierarchyClassifications() {
        CorpusSchemaResponseParser.ParseResult result = CorpusSchemaResponseParser.parse(Map.of(
                "nodeTypes", List.of(
                        Map.of("label", "employee", "parentType", "person"),
                        Map.of("label", "EMPLOYEE", "parentType", "organization")),
                "relationshipTypes", List.of(),
                "patterns", List.of()));

        assertFalse(result.valid());
        assertTrue(result.errors().get(0).contains("conflicting parentType"));
    }

    @Test
    void parsesFencedGraphSchemaJson() {
        String fencedJson = """
                ```json
                {
                  "nodeTypes": [
                    {
                      "label": "PERSON",
                      "description": "A person in the corpus.",
                      "properties": [
                        {"name": "email", "type": "String"}
                      ]
                    }
                  ],
                  "relationshipTypes": [
                    {
                      "type": "HAS_ROLE",
                      "description": "A person has a role."
                    }
                  ],
                  "patterns": []
                }
                ```
                """;

        CorpusSchemaResponseParser.ParseResult result = CorpusSchemaResponseParser.parse(fencedJson);
        assertNotNull(result.schema());
        assertTrue(result.errors().isEmpty());
        assertTrue(result.valid());
        assertEquals(1, result.schema().getNodeTypes().size());
        assertEquals("PERSON", result.schema().getNodeTypes().get(0).getLabel());
    }

    @Test
    void ignoresUnknownJsonFields() {
        String rawJson = """
                {
                  "nodeTypes": [],
                  "relationshipTypes": [],
                  "patterns": [],
                  "unexpected": {
                    "answer": "ignored"
                  }
                }
                """;

        CorpusSchemaResponseParser.ParseResult result = CorpusSchemaResponseParser.parse(rawJson);
        assertNotNull(result.schema());
        assertTrue(result.valid());
        assertTrue(result.errors().isEmpty());
        assertTrue(result.schema().getNodeTypes().isEmpty());
        assertTrue(result.schema().getRelationshipTypes().isEmpty());
    }

    @Test
    void rejectsBareStringTypeArraysInsteadOfInventingIncompleteDefinitions() {
        String rawJson = """
                {
                  "nodeTypes": ["detail", "forecast"],
                  "relationshipTypes": ["CO_OCCURS"],
                  "patterns": ["column"]
                }
                """;

        CorpusSchemaResponseParser.ParseResult result = CorpusSchemaResponseParser.parse(rawJson);
        assertFalse(result.valid());
        assertNull(result.schema());
        assertTrue(result.errors().get(0).startsWith("[SCHEMA_JSON]"));
        assertTrue(result.errors().get(0).contains("NodeType"));
    }

    @Test
    void reportsMalformedJsonWithoutThrowing() {
        String rawJson = "not-json";

        CorpusSchemaResponseParser.ParseResult result = CorpusSchemaResponseParser.parse(rawJson);
        assertFalse(result.valid());
        assertNull(result.schema());
        assertTrue(result.errors().get(0).startsWith("[SCHEMA_JSON]"));
    }
}
