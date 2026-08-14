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

        String prompt = CorpusSchemaPromptBuilder.build(passages, null);

        int chunkBIndex = prompt.indexOf("chunkId: chunk-b");
        int chunkAIndex = prompt.indexOf("chunkId: chunk-a");
        assertTrue(chunkBIndex >= 0);
        assertTrue(chunkAIndex > chunkBIndex);

        assertTrue(prompt.contains("node type"));
        assertTrue(prompt.contains("relationship type"));
        assertTrue(prompt.contains("patterns object"));
        assertTrue(prompt.contains("Type names must be UPPER_SNAKE_CASE and match [A-Z][A-Z0-9_]*"));
        assertTrue(prompt.contains("never for a particular name or value"));
        assertTrue(prompt.contains("Call submit_corpus_schema exactly once"));
        assertFalse(prompt.contains("sent by"));
    }

    @Test
    void boundsOneModelBatchWithoutSeedingDomainTypes() {
        Map<String, String> passages = new LinkedHashMap<>();
        for (int index = 0; index < 30; index++) {
            passages.put(String.format("chunk-%02d", index), "Observation " + index);
        }

        String prompt = CorpusSchemaPromptBuilder.build(passages, null);

        assertEquals(8, countOccurrences(prompt, "BEGIN CORPUS PASSAGE"));
        assertTrue(prompt.contains("chunkId: chunk-00"));
        assertTrue(prompt.contains("chunkId: chunk-07"));
        assertFalse(prompt.contains("chunkId: chunk-08"));
        assertFalse(prompt.contains("EMAIL_MESSAGE"));
        assertFalse(prompt.contains("SENT_BY"));
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
        assertTrue(prompt.contains("Do not delete, rename, redefine, or repeat these types"));
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
