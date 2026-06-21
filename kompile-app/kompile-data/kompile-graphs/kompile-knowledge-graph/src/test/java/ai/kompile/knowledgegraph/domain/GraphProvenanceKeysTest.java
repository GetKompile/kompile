/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the store-agnostic provenance projection over a node's structural lineage + metadata.
 */
class GraphProvenanceKeysTest {

    @Test
    void describe_surfacesLineageAndProvenanceMetadata() {
        GraphNode source = GraphNode.builder()
                .nodeId("src-1").externalId("channel:slack:m1").nodeType(NodeLevel.SOURCE)
                .title("slack message").sourceType("SLACK").build();
        GraphNode node = GraphNode.builder()
                .nodeId("n1").externalId("entity:acme corp").nodeType(NodeLevel.ENTITY).title("Acme Corp")
                .sourceNode(source)
                .metadataJson("{\"entityType\":\"ORGANIZATION\",\"_source\":\"channel:slack\","
                        + "\"_sourceDocumentId\":\"channel:slack:m1\",\"_extractedAt\":\"2026-06-19T00:00:00Z\"}")
                .build();

        Map<String, Object> out = GraphProvenanceKeys.describe(node);

        assertEquals("n1", out.get("nodeId"));
        assertEquals("entity:acme corp", out.get("externalId"));
        assertEquals("ENTITY", out.get("nodeType"));
        assertEquals("channel:slack:m1", out.get("sourceExternalId"));
        assertEquals("SLACK", out.get("sourceType"));

        @SuppressWarnings("unchecked")
        Map<String, Object> prov = (Map<String, Object>) out.get("provenance");
        assertEquals("channel:slack", prov.get("source"));
        assertEquals("channel:slack:m1", prov.get("sourceDocumentId"));
        assertEquals("2026-06-19T00:00:00Z", prov.get("extractedAt"));
        // entityType is not a reserved provenance key — excluded from the provenance sub-map.
        assertFalse(prov.containsKey("entityType"));
    }

    @Test
    void describe_nullNode_returnsEmpty() {
        assertTrue(GraphProvenanceKeys.describe(null).isEmpty());
    }

    @Test
    void crawl_buildsProvenanceMapWithReservedKeys() {
        Map<String, Object> m = GraphProvenanceKeys.crawl("job-1", "doc.pdf", "chunk-3", "gpt-x");
        assertEquals("crawl", m.get(GraphProvenanceKeys.SOURCE));
        assertEquals("job-1", m.get(GraphProvenanceKeys.CRAWL_RUN_ID));
        assertEquals("doc.pdf", m.get(GraphProvenanceKeys.SOURCE_DOCUMENT_ID));
        assertEquals("chunk-3", m.get(GraphProvenanceKeys.SOURCE_CHUNK_ID));
        assertEquals("gpt-x", m.get(GraphProvenanceKeys.EXTRACTION_MODEL));
    }

    @Test
    void crawl_omitsNullFields() {
        Map<String, Object> m = GraphProvenanceKeys.crawl("job-1", null, null);
        assertEquals("crawl", m.get(GraphProvenanceKeys.SOURCE));
        assertEquals("job-1", m.get(GraphProvenanceKeys.CRAWL_RUN_ID));
        assertFalse(m.containsKey(GraphProvenanceKeys.SOURCE_DOCUMENT_ID));
        assertFalse(m.containsKey(GraphProvenanceKeys.EXTRACTION_MODEL));
    }

    @Test
    void upload_buildsProvenanceMapWithSourceUpload() {
        Map<String, Object> m = GraphProvenanceKeys.upload("task-abc-123", "report.pdf");
        assertEquals("upload", m.get(GraphProvenanceKeys.SOURCE));
        assertEquals("task-abc-123", m.get(GraphProvenanceKeys.SOURCE_DOCUMENT_ID));
        assertEquals("report.pdf", m.get(GraphProvenanceKeys.SOURCE_CHUNK_ID));
        // CRAWL_RUN_ID must not be set for uploads — wrong provenance origin
        assertFalse(m.containsKey(GraphProvenanceKeys.CRAWL_RUN_ID));
        assertFalse(m.containsKey(GraphProvenanceKeys.EXTRACTION_MODEL));
    }

    @Test
    void upload_omitsNullFileName() {
        Map<String, Object> m = GraphProvenanceKeys.upload("task-xyz", null);
        assertEquals("upload", m.get(GraphProvenanceKeys.SOURCE));
        assertEquals("task-xyz", m.get(GraphProvenanceKeys.SOURCE_DOCUMENT_ID));
        assertFalse(m.containsKey(GraphProvenanceKeys.SOURCE_CHUNK_ID));
    }

    @Test
    void upload_omitsNullTaskId() {
        Map<String, Object> m = GraphProvenanceKeys.upload(null, "file.csv");
        assertEquals("upload", m.get(GraphProvenanceKeys.SOURCE));
        assertFalse(m.containsKey(GraphProvenanceKeys.SOURCE_DOCUMENT_ID));
        assertEquals("file.csv", m.get(GraphProvenanceKeys.SOURCE_CHUNK_ID));
    }

    @Test
    void upload_sourceDistinctFromCrawl() {
        Map<String, Object> crawlMap = GraphProvenanceKeys.crawl("run-1", "doc.pdf", "chunk-0");
        Map<String, Object> uploadMap = GraphProvenanceKeys.upload("task-1", "doc.pdf");
        assertNotEquals(crawlMap.get(GraphProvenanceKeys.SOURCE), uploadMap.get(GraphProvenanceKeys.SOURCE));
        assertEquals("crawl", crawlMap.get(GraphProvenanceKeys.SOURCE));
        assertEquals("upload", uploadMap.get(GraphProvenanceKeys.SOURCE));
    }
}
