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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorpusSchemaPromptBuilderTest {

    @Test
    void preservesPassageOrderAndRendersOutputContract() {
        Map<String, String> passages = new LinkedHashMap<>();
        passages.put("chunk-b", "Second passage");
        passages.put("chunk-a", "First passage");

        String prompt = CorpusSchemaPromptBuilder.build(
                passages, null, CorpusSchemaPromptBuilder.TypePass.NODE_TYPES);

        int chunkBIndex = prompt.indexOf("chunkId: chunk-b");
        int chunkAIndex = prompt.indexOf("chunkId: chunk-a");
        assertTrue(chunkBIndex >= 0);
        assertTrue(chunkAIndex > chunkBIndex);

        assertTrue(prompt.contains("schema type design only"));
        assertTrue(prompt.contains("reusable node types"));
        assertTrue(prompt.contains("All labels must be UPPER_SNAKE_CASE and match [A-Z][A-Z0-9_]*"));
        assertTrue(prompt.contains("Call submit_node_types exactly once"));
        assertTrue(prompt.contains("Do not extract entity instances, relation instances"));
        assertFalse(prompt.contains("submit_relationship_types"));
        assertFalse(prompt.contains("\"patterns\""));
        assertFalse(prompt.contains("sourceType"));
        assertFalse(prompt.contains("targetType"));
    }

    @Test
    void boundsOneModelBatchWithoutSeedingDomainTypes() {
        Map<String, String> passages = new LinkedHashMap<>();
        for (int index = 0; index < 30; index++) {
            passages.put(String.format("chunk-%02d", index), "Observation " + index);
        }

        String prompt = CorpusSchemaPromptBuilder.build(
                passages, null, CorpusSchemaPromptBuilder.TypePass.NODE_TYPES);

        assertEquals(8, countOccurrences(prompt, "BEGIN CORPUS PASSAGE"));
        assertTrue(prompt.contains("chunkId: chunk-00"));
        assertTrue(prompt.contains("chunkId: chunk-07"));
        assertFalse(prompt.contains("chunkId: chunk-08"));
        assertFalse(prompt.contains("EMAIL_MESSAGE"));
        assertFalse(prompt.contains("SENT_BY"));
        assertFalse(prompt.contains("AUTHOR"));
        assertFalse(prompt.contains("PUBLISHER"));
        assertFalse(prompt.contains("WRITES_FOR"));
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

        String nodePrompt = CorpusSchemaPromptBuilder.build(
                Map.of("chunk", "Alice serves as lead engineer."),
                configuredSchema,
                CorpusSchemaPromptBuilder.TypePass.NODE_TYPES
        );

        assertTrue(nodePrompt.contains("PERSON"));
        assertFalse(nodePrompt.contains("email"));
        assertFalse(nodePrompt.contains("HAS_ROLE"));
        assertFalse(nodePrompt.contains("serves_as"));
        assertFalse(nodePrompt.contains("(PERSON)-[:HAS_ROLE]->(ROLE)"));

        String relationshipPrompt = CorpusSchemaPromptBuilder.build(
                Map.of("chunk", "Alice serves as lead engineer."),
                configuredSchema,
                CorpusSchemaPromptBuilder.TypePass.RELATIONSHIP_TYPES);
        assertTrue(relationshipPrompt.contains("Frozen node types"));
        assertTrue(relationshipPrompt.contains("PERSON"));
        assertTrue(relationshipPrompt.contains("HAS_ROLE"));
        assertTrue(relationshipPrompt.contains("submit_relationship_types"));
        assertFalse(relationshipPrompt.contains("serves_as"));
        assertFalse(relationshipPrompt.contains("(PERSON)-[:HAS_ROLE]->(ROLE)"));
        assertTrue(relationshipPrompt.contains("never relation instances, triples"));
    }

    @Test
    void relationshipPassNeverRequestsEntitiesOrEndpointPatterns() {
        String prompt = CorpusSchemaPromptBuilder.build(
                Map.of("chunk", "Mei submitted the forecast."),
                new GraphSchema(List.of(new NodeType("PERSON", "A person", null)), null, null),
                CorpusSchemaPromptBuilder.TypePass.RELATIONSHIP_TYPES);

        assertTrue(prompt.contains("relationship schema types only")
                || prompt.contains("reusable relationship types"));
        assertTrue(prompt.contains("do not repeat node types"));
        assertTrue(prompt.contains("never relation instances, triples"));
        assertFalse(prompt.contains("sourceType"));
        assertFalse(prompt.contains("targetType"));
    }

    @Test
    void consolidationTreatsBatchLabelsAsUntrustedAndOmitsCorpusInstances() {
        GraphSchema frozen = new GraphSchema(
                List.of(new NodeType("PERSON", "A person", null)), null, null);

        String nodePrompt = CorpusSchemaPromptBuilder.buildConsolidation(
                frozen,
                CorpusSchemaPromptBuilder.TypePass.NODE_TYPES,
                new LinkedHashMap<>(Map.of(
                        "AMER_FORECAST_Q3_FINAL", 1,
                        "FORECAST", 3)));

        assertTrue(nodePrompt.contains("UNTRUSTED BATCH PROPOSALS"));
        assertTrue(nodePrompt.contains("AMER_FORECAST_Q3_FINAL"));
        assertTrue(nodePrompt.contains("FORECAST"));
        assertTrue(nodePrompt.contains("Drop weak proposals"));
        assertTrue(nodePrompt.contains("at most 12 high-confidence labels"));
        assertFalse(nodePrompt.contains("CORPUS PASSAGES"));
        assertFalse(nodePrompt.contains("BEGIN CORPUS PASSAGE"));

        String relationshipPrompt = CorpusSchemaPromptBuilder.buildConsolidation(
                frozen,
                CorpusSchemaPromptBuilder.TypePass.RELATIONSHIP_TYPES,
                Map.of("FORECAST", 1, "USES_CURRENCY", 2));
        assertTrue(relationshipPrompt.contains("directed predicate"));
        assertTrue(relationshipPrompt.contains("Never return a frozen node type"));
        assertTrue(relationshipPrompt.contains("PERSON"));
        assertFalse(relationshipPrompt.contains("sourceType"));
        assertFalse(relationshipPrompt.contains("targetType"));
    }

    @Test
    void topicEvidenceConstrainsBothDiscoveryAndConsolidationWithoutBecomingSchema() {
        CorpusTopicEvidence evidence = new CorpusTopicEvidence(
                "multilingual-e5-small", 384, 0.42, 1,
                List.of(new CorpusTopicEvidence.Topic(
                        "topic-0001",
                        List.of("chunk-en", "chunk-es"),
                        List.of("chunk-en", "chunk-es"),
                        Map.of("en", 1, "es", 1),
                        Map.of("en", List.of("forecast"), "es", List.of("previsión")))));

        String discovery = CorpusSchemaPromptBuilder.build(
                Map.of("chunk-en", "Revenue forecast approved."),
                null,
                CorpusSchemaPromptBuilder.TypePass.NODE_TYPES,
                evidence);
        assertTrue(discovery.contains("CORPUS TOPIC EVIDENCE"));
        assertTrue(discovery.contains("multilingual-e5-small"));
        assertTrue(discovery.contains("previsión"));
        assertTrue(discovery.contains("Topic ids and terms are not schema labels"));

        String consolidation = CorpusSchemaPromptBuilder.buildConsolidation(
                null,
                CorpusSchemaPromptBuilder.TypePass.NODE_TYPES,
                Map.of("FORECAST", 2),
                evidence);
        assertTrue(consolidation.contains("CORPUS TOPIC EVIDENCE"));
        assertTrue(consolidation.contains("representativeChunkIds"));
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
