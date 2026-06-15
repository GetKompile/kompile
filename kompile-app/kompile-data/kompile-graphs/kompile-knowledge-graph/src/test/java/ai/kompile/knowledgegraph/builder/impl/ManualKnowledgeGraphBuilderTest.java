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

package ai.kompile.knowledgegraph.builder.impl;

import ai.kompile.core.graphbuilder.*;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ManualKnowledgeGraphBuilder} — identity, type,
 * buildFromChunks no-op behaviour, progress callback, config, and capability flags.
 */
class ManualKnowledgeGraphBuilderTest {

    private ManualKnowledgeGraphBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new ManualKnowledgeGraphBuilder();
    }

    private RetrievedDoc doc(String id, String text) {
        return RetrievedDoc.builder().id(id).text(text).metadata(Map.of()).build();
    }

    private GraphBuildContext context() {
        return new GraphBuildContext("job-1", null, "jpa");
    }

    // ─── Identity ─────────────────────────────────────────────────────

    @Test
    void getId_returnsManualBuilder() {
        assertEquals("manual-builder", builder.getId());
        assertEquals(ManualKnowledgeGraphBuilder.BUILDER_ID, builder.getId());
    }

    @Test
    void getDisplayName_notBlank() {
        assertNotNull(builder.getDisplayName());
        assertFalse(builder.getDisplayName().isBlank());
    }

    @Test
    void getType_isManual() {
        assertEquals(GraphBuilderType.MANUAL, builder.getType());
    }

    @Test
    void getDescription_notBlank() {
        assertNotNull(builder.getDescription());
        assertFalse(builder.getDescription().isBlank());
    }

    // ─── buildFromChunks ──────────────────────────────────────────────

    @Test
    void buildFromChunks_returnsEmptyList() {
        List<RetrievedDoc> chunks = List.of(doc("c1", "hello"), doc("c2", "world"));
        List<ProposedTriple> result = builder.buildFromChunks(chunks, context(), null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void buildFromChunks_emptyChunks_returnsEmpty() {
        List<ProposedTriple> result = builder.buildFromChunks(List.of(), context(), null);
        assertTrue(result.isEmpty());
    }

    @Test
    void buildFromChunks_nullChunks_returnsEmpty() {
        List<ProposedTriple> result = builder.buildFromChunks(null, context(), null);
        assertTrue(result.isEmpty());
    }

    @Test
    void buildFromChunks_firesProgressCallback() {
        AtomicReference<BuildProgress> captured = new AtomicReference<>();
        List<RetrievedDoc> chunks = List.of(doc("c1", "text"), doc("c2", "more"));

        builder.buildFromChunks(chunks, context(), captured::set);

        BuildProgress progress = captured.get();
        assertNotNull(progress);
        assertEquals("job-1", progress.jobId());
        assertEquals(2, progress.totalChunks());
        assertEquals(0, progress.proposalsCreated());
        assertEquals(BuildProgress.Status.COMPLETED, progress.status());
    }

    @Test
    void buildFromChunks_nullCallback_noException() {
        assertDoesNotThrow(() ->
                builder.buildFromChunks(List.of(doc("c1", "text")), context(), null));
    }

    @Test
    void buildFromChunks_nullChunks_callbackReportsZeroTotal() {
        AtomicReference<BuildProgress> captured = new AtomicReference<>();
        builder.buildFromChunks(null, context(), captured::set);

        assertEquals(0, captured.get().totalChunks());
    }

    // ─── Extraction log ───────────────────────────────────────────────

    @Test
    void getExtractionLog_returnsEmpty() {
        Optional<List<ExtractionLogEntry>> log = builder.getExtractionLog("any-job");
        assertTrue(log.isEmpty());
    }

    @Test
    void supportsExtractionLog_false() {
        assertFalse(builder.supportsExtractionLog());
    }

    // ─── Concurrent indexing ──────────────────────────────────────────

    @Test
    void supportsConcurrentIndexing_true() {
        assertTrue(builder.supportsConcurrentIndexing());
    }

    // ─── Config ───────────────────────────────────────────────────────

    @Test
    void config_initiallyNull() {
        assertNull(builder.getConfig());
    }

    @Test
    void configure_storesConfig() {
        BuilderConfig config = new BuilderConfig(
                "openai", "gpt-4", 0.5, 100,
                List.of("PERSON"), List.of("WORKS_AT"),
                0.7, true, 0.8, null, Map.of());
        builder.configure(config);
        assertSame(config, builder.getConfig());
    }
}
