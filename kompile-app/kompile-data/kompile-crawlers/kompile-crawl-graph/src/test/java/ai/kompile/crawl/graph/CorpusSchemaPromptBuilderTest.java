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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorpusSchemaPromptBuilderTest {

    @Test
    void preservesPassageOrderAndRendersOutputContract() {
        Map<String, String> passages = new LinkedHashMap<>();
        passages.put("chunk-b", "Second passage");
        passages.put("chunk-a", "First passage");

        String prompt = CorpusSchemaPromptBuilder.build(passages, null);

        int chunkBIndex = prompt.indexOf("chunkId: chunk-b");
        int chunkAIndex = prompt.indexOf("chunkId: chunk-a");
        assertTrue(chunkBIndex >= 0);
        assertTrue(chunkAIndex > chunkBIndex);

        assertTrue(prompt.contains("\"nodeTypes\""));
        assertTrue(prompt.contains("\"relationshipTypes\""));
        assertTrue(prompt.contains("\"patterns\""));
        assertTrue(prompt.contains("Node and relationship names must match [A-Z][A-Z0-9_]*"));
        assertTrue(prompt.contains("Schema definitions are vocabulary and constraints, not source evidence."));
    }

    @Test
    void rendersConfiguredSchemaAsAuthoritative() {
        NodeType person = new NodeType("PERSON", "A person.", List.of(new PropertyType("email", "String")));
        RelationshipType hasRole = new RelationshipType(
                "HAS_ROLE",
                "Person has a role.",
                List.of(),
                List.of("serves_as")
        );
        GraphSchema configuredSchema = new GraphSchema(
                List.of(person),
                List.of(hasRole),
                List.of("(PERSON)-[:HAS_ROLE]->(ROLE)")
        );

        String prompt = CorpusSchemaPromptBuilder.build(
                Map.of("chunk", "Alice serves as lead engineer."),
                configuredSchema
        );

        assertTrue(prompt.contains("PERSON"));
        assertTrue(prompt.contains("email"));
        assertTrue(prompt.contains("HAS_ROLE"));
        assertTrue(prompt.contains("serves_as"));
        assertTrue(prompt.contains("(PERSON)-[:HAS_ROLE]->(ROLE)"));
        assertTrue(prompt.contains("do not rename or delete existing canonical names"));
    }

    @Test
    void unificationShapeDoesNotSeedConcreteTypesOrAliasPatterns() {
        CorpusSchemaCandidates.Inventory inventory = new CorpusSchemaCandidates.Inventory(
                List.of(new CorpusSchemaCandidates.NodeCandidate(
                        "person", List.of("person"), List.of("person"), 2, 0.9,
                        List.of("chunk-1"), List.of()
                )),
                List.of()
        );

        String prompt = CorpusSchemaUnificationPromptBuilder.build(inventory, null);

        assertTrue(prompt.contains("{ \"nodeTypes\": [], \"relationshipTypes\": [], \"patterns\": [] }"));
        assertTrue(prompt.contains("Every nodeTypes item MUST be a JSON object"));
        assertTrue(prompt.contains("Never put bare strings in nodeTypes"));
        assertTrue(prompt.contains("Every relationshipTypes item MUST be a JSON object"));
        assertTrue(prompt.contains("Never put bare strings in relationshipTypes"));
        assertTrue(prompt.contains("(SOURCE_TYPE)-[:RELATIONSHIP_TYPE]->(TARGET_TYPE)"));
        assertTrue(prompt.contains("aliases are not valid values inside [:...]"));
        assertFalse(prompt.contains("ORGANIZATION"));
        assertFalse(prompt.contains("ASSET"));
        assertFalse(prompt.contains("OWNS"));
    }
}
