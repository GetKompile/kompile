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

package ai.kompile.knowledgegraph.builder.service;

import ai.kompile.core.graphbuilder.*;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GraphBuilderRegistry} — builder lookup by ID/type,
 * default builder selection, duplicate ID handling, and empty registry.
 */
class GraphBuilderRegistryTest {

    private GraphBuilderRegistry registry;

    private KnowledgeGraphBuilder llmBuilder;
    private KnowledgeGraphBuilder manualBuilder;

    @BeforeEach
    void setUp() {
        llmBuilder = stubBuilder("llm-builder", "LLM Builder", GraphBuilderType.LLM);
        manualBuilder = stubBuilder("manual-builder", "Manual Builder", GraphBuilderType.MANUAL);
        registry = new GraphBuilderRegistry(List.of(llmBuilder, manualBuilder));
        registry.init();
    }

    private KnowledgeGraphBuilder stubBuilder(String id, String name, GraphBuilderType type) {
        return new KnowledgeGraphBuilder() {
            @Override public String getId() { return id; }
            @Override public String getDisplayName() { return name; }
            @Override public GraphBuilderType getType() { return type; }
            @Override public String getDescription() { return name + " description"; }
            @Override public void configure(BuilderConfig config) {}
            @Override public BuilderConfig getConfig() { return null; }
            @Override public List<ProposedTriple> buildFromChunks(
                    List<RetrievedDoc> chunks, GraphBuildContext context,
                    Consumer<BuildProgress> progressCallback) { return List.of(); }
            @Override public Optional<List<ExtractionLogEntry>> getExtractionLog(String jobId) { return Optional.empty(); }
            @Override public boolean supportsExtractionLog() { return false; }
            @Override public boolean supportsConcurrentIndexing() { return false; }
        };
    }

    // ─── getBuilder ───────────────────────────────────────────────────

    @Test
    void getBuilder_byExactId() {
        Optional<KnowledgeGraphBuilder> result = registry.getBuilder("llm-builder");
        assertTrue(result.isPresent());
        assertEquals("llm-builder", result.get().getId());
    }

    @Test
    void getBuilder_unknownId_returnsEmpty() {
        assertTrue(registry.getBuilder("nonexistent").isEmpty());
    }

    @Test
    void getBuilder_null_returnsEmpty() {
        assertTrue(registry.getBuilder(null).isEmpty());
    }

    // ─── getAllBuilders ───────────────────────────────────────────────

    @Test
    void getAllBuilders_returnsAll() {
        List<KnowledgeGraphBuilder> all = registry.getAllBuilders();
        assertEquals(2, all.size());
    }

    @Test
    void getAllBuilders_returnsCopy() {
        List<KnowledgeGraphBuilder> all = registry.getAllBuilders();
        all.clear();
        assertEquals(2, registry.getAllBuilders().size());
    }

    // ─── getBuildersByType ────────────────────────────────────────────

    @Test
    void getBuildersByType_llm() {
        List<KnowledgeGraphBuilder> llm = registry.getBuildersByType(GraphBuilderType.LLM);
        assertEquals(1, llm.size());
        assertEquals("llm-builder", llm.get(0).getId());
    }

    @Test
    void getBuildersByType_manual() {
        List<KnowledgeGraphBuilder> manual = registry.getBuildersByType(GraphBuilderType.MANUAL);
        assertEquals(1, manual.size());
        assertEquals("manual-builder", manual.get(0).getId());
    }

    @Test
    void getBuildersByType_noMatch_returnsEmpty() {
        List<KnowledgeGraphBuilder> result = registry.getBuildersByType(GraphBuilderType.PATTERN);
        assertTrue(result.isEmpty());
    }

    // ─── getDefaultBuilder ────────────────────────────────────────────

    @Test
    void getDefaultBuilder_prefersLlm() {
        Optional<KnowledgeGraphBuilder> def = registry.getDefaultBuilder();
        assertTrue(def.isPresent());
        assertEquals("llm-builder", def.get().getId());
    }

    @Test
    void getDefaultBuilder_noLlm_fallsToFirst() {
        GraphBuilderRegistry manualOnly = new GraphBuilderRegistry(List.of(manualBuilder));
        manualOnly.init();

        Optional<KnowledgeGraphBuilder> def = manualOnly.getDefaultBuilder();
        assertTrue(def.isPresent());
        assertEquals("manual-builder", def.get().getId());
    }

    @Test
    void getDefaultBuilder_empty_returnsEmpty() {
        GraphBuilderRegistry empty = new GraphBuilderRegistry(List.of());
        empty.init();

        assertTrue(empty.getDefaultBuilder().isEmpty());
    }

    // ─── getBuilderByTypeString ───────────────────────────────────────

    @Test
    void getBuilderByTypeString_byExactId() {
        Optional<KnowledgeGraphBuilder> result = registry.getBuilderByTypeString("manual-builder");
        assertTrue(result.isPresent());
        assertEquals("manual-builder", result.get().getId());
    }

    @Test
    void getBuilderByTypeString_byTypeName() {
        Optional<KnowledgeGraphBuilder> result = registry.getBuilderByTypeString("LLM");
        assertTrue(result.isPresent());
        assertEquals("llm-builder", result.get().getId());
    }

    @Test
    void getBuilderByTypeString_caseInsensitive() {
        Optional<KnowledgeGraphBuilder> result = registry.getBuilderByTypeString("manual");
        assertTrue(result.isPresent());
        assertEquals("manual-builder", result.get().getId());
    }

    @Test
    void getBuilderByTypeString_partialMatch() {
        Optional<KnowledgeGraphBuilder> result = registry.getBuilderByTypeString("llm");
        assertTrue(result.isPresent());
    }

    @Test
    void getBuilderByTypeString_null_returnsDefault() {
        Optional<KnowledgeGraphBuilder> result = registry.getBuilderByTypeString(null);
        assertTrue(result.isPresent());
        assertEquals("llm-builder", result.get().getId()); // default is LLM
    }

    @Test
    void getBuilderByTypeString_empty_returnsDefault() {
        Optional<KnowledgeGraphBuilder> result = registry.getBuilderByTypeString("");
        assertTrue(result.isPresent());
    }

    @Test
    void getBuilderByTypeString_noMatch_returnsEmpty() {
        Optional<KnowledgeGraphBuilder> result = registry.getBuilderByTypeString("zzz-nonexistent-zzz");
        assertTrue(result.isEmpty());
    }

    // ─── hasBuilder / getBuilderCount / getBuilderIds ─────────────────

    @Test
    void hasBuilder_true() {
        assertTrue(registry.hasBuilder("llm-builder"));
    }

    @Test
    void hasBuilder_false() {
        assertFalse(registry.hasBuilder("nonexistent"));
    }

    @Test
    void getBuilderCount() {
        assertEquals(2, registry.getBuilderCount());
    }

    @Test
    void getBuilderIds() {
        Set<String> ids = registry.getBuilderIds();
        assertEquals(2, ids.size());
        assertTrue(ids.contains("llm-builder"));
        assertTrue(ids.contains("manual-builder"));
    }

    // ─── getBuilderInfos ──────────────────────────────────────────────

    @Test
    void getBuilderInfos_returnsInfoForAll() {
        List<GraphBuilderInfo> infos = registry.getBuilderInfos();
        assertEquals(2, infos.size());
    }

    @Test
    void getBuilderInfo_byId() {
        Optional<GraphBuilderInfo> info = registry.getBuilderInfo("llm-builder");
        assertTrue(info.isPresent());
        assertEquals("llm-builder", info.get().id());
    }

    @Test
    void getBuilderInfo_unknown_returnsEmpty() {
        assertTrue(registry.getBuilderInfo("nonexistent").isEmpty());
    }

    // ─── Duplicate ID handling ────────────────────────────────────────

    @Test
    void duplicateId_skipped() {
        KnowledgeGraphBuilder dup = stubBuilder("llm-builder", "Duplicate LLM", GraphBuilderType.LLM);
        GraphBuilderRegistry reg = new GraphBuilderRegistry(List.of(llmBuilder, dup));
        reg.init();

        assertEquals(1, reg.getBuilderCount());
        assertEquals("LLM Builder", reg.getBuilder("llm-builder").get().getDisplayName());
    }

    // ─── Null input ───────────────────────────────────────────────────

    @Test
    void nullBuildersList_handledGracefully() {
        GraphBuilderRegistry reg = new GraphBuilderRegistry(null);
        reg.init();

        assertEquals(0, reg.getBuilderCount());
        assertTrue(reg.getDefaultBuilder().isEmpty());
    }
}
