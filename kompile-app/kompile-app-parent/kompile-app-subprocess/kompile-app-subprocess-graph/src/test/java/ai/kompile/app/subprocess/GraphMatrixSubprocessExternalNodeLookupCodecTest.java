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
package ai.kompile.app.subprocess;

import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphMatrixSubprocessExternalNodeLookupCodecTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void encodesLookupRecordsAsBuiltInJsonAndRoundTripsNullableScope() {
        List<KnowledgeGraphService.ExternalNodeLookup> lookups = List.of(
                new KnowledgeGraphService.ExternalNodeLookup("process:run-1", NodeLevel.CUSTOM, 42L),
                new KnowledgeGraphService.ExternalNodeLookup("process:step-1", NodeLevel.ENTITY, null));

        ArrayNode payload = GraphMatrixSubprocessMain.encodeExternalNodeLookups(lookups, mapper);

        assertEquals(2, payload.size());
        assertEquals("process:run-1", payload.get(0).path("externalId").asText());
        assertEquals("CUSTOM", payload.get(0).path("nodeType").asText());
        assertEquals(42L, payload.get(0).path("factSheetId").asLong());
        assertTrue(payload.get(1).path("factSheetId").isNull());
        assertEquals(lookups, GraphMatrixSubprocessMain.decodeExternalNodeLookups(payload));
    }

    @Test
    void preservesNullRowsAndNullableFieldsWithoutRecordDeserialization() {
        ArrayNode payload = mapper.createArrayNode();
        payload.addNull();
        payload.addObject()
                .putNull("externalId")
                .putNull("nodeType")
                .putNull("factSheetId");

        List<KnowledgeGraphService.ExternalNodeLookup> decoded =
                GraphMatrixSubprocessMain.decodeExternalNodeLookups(payload);

        assertEquals(2, decoded.size());
        assertNull(decoded.get(0));
        assertEquals(new KnowledgeGraphService.ExternalNodeLookup(null, null, null), decoded.get(1));
    }

    @Test
    void encodesEveryKnowledgeGraphBatchRecordWithoutBeanSerialization() {
        List<Object> records = List.of(
                new KnowledgeGraphService.SnippetSpec("document-1", 7L, "snippet-1", "text", 3),
                new KnowledgeGraphService.NodeSpec(
                        NodeLevel.ENTITY, "entity-1", "Entity", "description", Map.of("score", 1)),
                new KnowledgeGraphService.EdgeSpec(
                        "source-1", "target-1", EdgeType.USER_DEFINED, 0.75, "description",
                        "related", "{}", EdgeProvenance.INFERRED, 7L),
                new KnowledgeGraphService.NodeUpdate(
                        "node-1", "Renamed", "updated", Map.of("active", true)),
                new KnowledgeGraphService.NodeMetadataUpdate("node-2", Map.of("kgeVersion", 2)),
                new KnowledgeGraphService.EdgeMetadataUpdate("edge-1", Map.of("confidence", 0.9)));

        ArrayNode payload = GraphMatrixSubprocessMain.encodeKnowledgeGraphRecordList(records, mapper);

        assertEquals(6, payload.size());
        assertEquals("document-1", payload.get(0).path("parentExternalId").asText());
        assertEquals("ENTITY", payload.get(1).path("nodeType").asText());
        assertEquals("USER_DEFINED", payload.get(2).path("edgeType").asText());
        assertEquals("INFERRED", payload.get(2).path("provenance").asText());
        assertTrue(payload.get(3).path("additionalMetadata").path("active").asBoolean());
        assertEquals(2, payload.get(4).path("additionalMetadata").path("kgeVersion").asInt());
        assertEquals(0.9, payload.get(5).path("additionalMetadata").path("confidence").asDouble());
    }

    @Test
    void decodesEveryBatchRecordWithBeanDiscoveryDisabled() {
        ObjectMapper nativeLikeMapper = JsonMapper.builder()
                .disable(MapperFeature.AUTO_DETECT_CREATORS)
                .disable(MapperFeature.AUTO_DETECT_FIELDS)
                .disable(MapperFeature.AUTO_DETECT_GETTERS)
                .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
                .disable(MapperFeature.AUTO_DETECT_SETTERS)
                .build();
        KnowledgeGraphService.SnippetSpec snippet = new KnowledgeGraphService.SnippetSpec(
                "document-1", 7L, "snippet-1", "text", 3);
        KnowledgeGraphService.NodeSpec node = new KnowledgeGraphService.NodeSpec(
                NodeLevel.ENTITY, "entity-1", "Entity", "description",
                Map.of("score", 1, "nested", List.of(true, "ok")));
        KnowledgeGraphService.NodeUpdate nodeUpdate = new KnowledgeGraphService.NodeUpdate(
                "node-1", "Renamed", "updated", Map.of("active", true));
        KnowledgeGraphService.NodeMetadataUpdate nodeMetadata =
                new KnowledgeGraphService.NodeMetadataUpdate("node-2", Map.of("kgeVersion", 2));
        KnowledgeGraphService.EdgeSpec edge = new KnowledgeGraphService.EdgeSpec(
                "run-node", "step-node", EdgeType.HIERARCHICAL, 1.0, "contains",
                "contains", null, EdgeProvenance.EXTRACTED, 11L);
        KnowledgeGraphService.EdgeMetadataUpdate edgeMetadata =
                new KnowledgeGraphService.EdgeMetadataUpdate("edge-1", Map.of("confidence", 0.9));

        assertEquals(List.of(snippet), GraphMatrixSubprocessMain.decodeSnippetSpecs(
                GraphMatrixSubprocessMain.encodeKnowledgeGraphRecordList(List.of(snippet), nativeLikeMapper)));
        assertEquals(List.of(node), GraphMatrixSubprocessMain.decodeNodeSpecs(
                GraphMatrixSubprocessMain.encodeKnowledgeGraphRecordList(List.of(node), nativeLikeMapper)));
        assertEquals(List.of(nodeUpdate), GraphMatrixSubprocessMain.decodeNodeUpdates(
                GraphMatrixSubprocessMain.encodeKnowledgeGraphRecordList(List.of(nodeUpdate), nativeLikeMapper)));
        assertEquals(List.of(nodeMetadata), GraphMatrixSubprocessMain.decodeNodeMetadataUpdates(
                GraphMatrixSubprocessMain.encodeKnowledgeGraphRecordList(List.of(nodeMetadata), nativeLikeMapper)));
        assertEquals(List.of(edge), GraphMatrixSubprocessMain.decodeEdgeSpecs(
                GraphMatrixSubprocessMain.encodeKnowledgeGraphRecordList(List.of(edge), nativeLikeMapper)));
        assertEquals(List.of(edgeMetadata), GraphMatrixSubprocessMain.decodeEdgeMetadataUpdates(
                GraphMatrixSubprocessMain.encodeKnowledgeGraphRecordList(List.of(edgeMetadata), nativeLikeMapper)));
    }

    @Test
    void rejectsMetadataValuesThatWouldRequireBeanSerialization() {
        KnowledgeGraphService.NodeMetadataUpdate update =
                new KnowledgeGraphService.NodeMetadataUpdate("node-1", Map.of("custom", new Object()));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> GraphMatrixSubprocessMain.encodeKnowledgeGraphRecordList(List.of(update), mapper));

        assertTrue(error.getMessage().contains("unsupported native JSON metadata value"));

        ObjectNode tree = mapper.createObjectNode();
        tree.set("custom", mapper.getNodeFactory().pojoNode(new Object()));
        KnowledgeGraphService.NodeMetadataUpdate treeUpdate =
                new KnowledgeGraphService.NodeMetadataUpdate("node-2", Map.of("tree", tree));
        IllegalArgumentException treeError = assertThrows(IllegalArgumentException.class,
                () -> GraphMatrixSubprocessMain.encodeKnowledgeGraphRecordList(List.of(treeUpdate), mapper));
        assertTrue(treeError.getMessage().contains("unsupported native JSON metadata node"));
    }

    @Test
    void rejectsNonArrayPayloads() {
        assertThrows(IllegalArgumentException.class,
                () -> GraphMatrixSubprocessMain.decodeExternalNodeLookups(mapper.createObjectNode()));
    }
}
