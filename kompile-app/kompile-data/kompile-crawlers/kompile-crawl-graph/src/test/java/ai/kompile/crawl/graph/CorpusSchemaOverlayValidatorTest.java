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

import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.PropertyType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorpusSchemaOverlayValidatorTest {

    @Test
    void acceptsCompleteValidOverlay() {
        GraphSchema overlay = new GraphSchema(
                List.of(
                        new NodeType("PERSON", "A person", List.of(new PropertyType("function", "String"))),
                        new NodeType("ROLE", "A role", null)
                ),
                List.of(new RelationshipType("HAS_ROLE", "person has role", null, List.of())),
                List.of("(PERSON)-[:HAS_ROLE]->(ROLE)")
        );

        CorpusSchemaOverlayValidator.Result result = CorpusSchemaOverlayValidator.validate(null, overlay);

        assertTrue(result.valid());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void rejectsLowercaseOrWhitespacePaddedTypeNames() {
        GraphSchema overlay = new GraphSchema(
                List.of(new NodeType(" person", "A person", null)),
                List.of(new RelationshipType(" has_role ", "whitespace padded", null, List.of())),
                List.of()
        );

        CorpusSchemaOverlayValidator.Result result = CorpusSchemaOverlayValidator.validate(null, overlay);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.startsWith("[SCHEMA_TYPE_NAME]")));
    }

    @Test
    void rejectsDuplicateGeneratedNamesCaseInsensitively() {
        GraphSchema overlay = new GraphSchema(
                List.of(
                        new NodeType("PERSON", "A person", null),
                        new NodeType("person", "another person", null)
                ),
                List.of(),
                List.of()
        );

        CorpusSchemaOverlayValidator.Result result = CorpusSchemaOverlayValidator.validate(null, overlay);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.startsWith("[SCHEMA_DUPLICATE_TYPE]")));
    }

    @Test
    void rejectsUnsupportedPropertyTypes() {
        GraphSchema overlay = new GraphSchema(
                List.of(new NodeType("PERSON", "A person", List.of(new PropertyType("function", "Textual")))),
                List.of(),
                List.of()
        );

        CorpusSchemaOverlayValidator.Result result = CorpusSchemaOverlayValidator.validate(null, overlay);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.startsWith("[SCHEMA_PROPERTY_TYPE]")));
    }

    @Test
    void rejectsPatternsWithUnknownEndpoints() {
        GraphSchema overlay = new GraphSchema(
                List.of(
                        new NodeType("PERSON", "A person", null),
                        new NodeType("ROLE", "A role", null)
                ),
                List.of(new RelationshipType("HAS_ROLE", "person has role", null, List.of())),
                List.of("(PERSON)-[:HAS_ROLE]->(UNKNOWN)")
        );

        CorpusSchemaOverlayValidator.Result result = CorpusSchemaOverlayValidator.validate(null, overlay);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.startsWith("[SCHEMA_PATTERN_ENDPOINT]")));
    }

    @Test
    void requiresOnePatternForEveryGeneratedRelationshipType() {
        GraphSchema overlay = new GraphSchema(
                List.of(new NodeType("PERSON", "A person", null), new NodeType("ROLE", "A role", null)),
                List.of(new RelationshipType("HAS_ROLE", "person has role", null, List.of())),
                List.of()
        );

        CorpusSchemaOverlayValidator.Result result = CorpusSchemaOverlayValidator.validate(null, overlay);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.startsWith("[SCHEMA_RELATION_PATTERN_MISSING]")));
    }

    @Test
    void rejectsAliasesUsedAsDirectedPatternRelationshipTypes() {
        GraphSchema overlay = new GraphSchema(
                List.of(new NodeType("PERSON", "A person", null), new NodeType("ROLE", "A role", null)),
                List.of(new RelationshipType("HAS_ROLE", "person has role", null, List.of("serves_as"))),
                List.of("(PERSON)-[:SERVES_AS]->(ROLE)")
        );

        CorpusSchemaOverlayValidator.Result result = CorpusSchemaOverlayValidator.validate(null, overlay);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.startsWith("[SCHEMA_PATTERN_RELATION]")));
    }
}
