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
package ai.kompile.app.services.agent;

import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.unified.UnifiedGraphArtifactContributor;
import ai.kompile.knowledgegraph.unified.UnifiedGraphArtifactImporter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Bundles the in-JVM reasoning trace buffer ({@link ReasoningTraceStore}) inside a
 * {@code .kgraph} artifact so that traces produced during an agent turn travel with the
 * graph on snapshot/export.
 *
 * <h3>Export (contribute)</h3>
 * <p>Takes a non-destructive {@link ReasoningTraceStore#snapshot()} of all retained trace DTOs
 * and serialises them as a JSON array stored under the {@value #ARTIFACT_NAME} artifact key.
 * The buffer is <em>not</em> cleared — the normal {@link ReasoningTraceStore#drainSince} call
 * path used by {@code AgentChatService} after each turn is unaffected.</p>
 *
 * <h3>Import (restore)</h3>
 * <p>Deserialises the JSON array and pushes each DTO back into the
 * {@link ReasoningTraceStore} buffer via {@link ReasoningTraceStore#storeTrace(Map)}.  This
 * makes the traces available to the SSE/reasoning-trace endpoint on the importing instance.
 * Idempotent in the sense that the buffer is bounded (200 entries by default)
 * and oldest entries are evicted automatically.</p>
 *
 * <h3>Dependency direction</h3>
 * <p>This class lives in {@code kompile-app-agent} which already depends on
 * {@code kompile-knowledge-graph} (for {@link UnifiedGraphArtifactContributor}/
 * {@link UnifiedGraphArtifactImporter} and the bridge SPI).  No cyclic dependency is
 * introduced.</p>
 */
@Component
@Slf4j
public class ReasoningTraceArtifactAdapter
        implements UnifiedGraphArtifactContributor, UnifiedGraphArtifactImporter {

    /** Artifact entry name used inside the {@code .kgraph} ZIP. */
    public static final String ARTIFACT_NAME = "reasoning/traces.json";

    private static final TypeReference<List<Map<String, Object>>> TRACE_LIST_TYPE =
            new TypeReference<>() {};

    private final ReasoningTraceStore traceStore;
    private final ObjectMapper objectMapper;

    @Autowired
    public ReasoningTraceArtifactAdapter(ReasoningTraceStore traceStore, ObjectMapper objectMapper) {
        this.traceStore  = traceStore;
        this.objectMapper = objectMapper;
    }

    // ── Export ───────────────────────────────────────────────────────────────────

    @Override
    public void contribute(Long factSheetId, UnifiedGraph graph) {
        if (graph == null || traceStore == null) {
            return;
        }
        // Take a non-destructive snapshot of all retained entries; the normal
        // drainSince() path used by AgentChatService is unaffected.
        List<Map<String, Object>> traces = traceStore.snapshot();
        if (traces.isEmpty()) {
            return;
        }
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(traces);
            graph.putArtifact(ARTIFACT_NAME, bytes);
            log.debug("ReasoningTraceArtifactAdapter: bundled {} trace(s) for factSheet={} ({} bytes)",
                    traces.size(), factSheetId, bytes.length);
        } catch (Exception e) {
            log.warn("ReasoningTraceArtifactAdapter: could not serialise traces for factSheet={}: {}",
                    factSheetId, e.getMessage());
        }
    }

    // ── Import ───────────────────────────────────────────────────────────────────

    @Override
    public int importArtifacts(Long factSheetId, UnifiedGraph graph) {
        if (graph == null || traceStore == null) {
            return 0;
        }
        byte[] artifact = graph.artifact(ARTIFACT_NAME);
        if (artifact == null || artifact.length == 0) {
            return 0;
        }
        try {
            List<Map<String, Object>> traces = objectMapper.readValue(
                    new String(artifact, StandardCharsets.UTF_8), TRACE_LIST_TYPE);
            for (Map<String, Object> dto : traces) {
                if (dto != null && !dto.isEmpty()) {
                    traceStore.storeTrace(dto);
                }
            }
            log.debug("ReasoningTraceArtifactAdapter: restored {} trace(s) for factSheet={}",
                    traces.size(), factSheetId);
            return traces.size();
        } catch (Exception e) {
            log.warn("ReasoningTraceArtifactAdapter: could not deserialise traces for factSheet={}: {}",
                    factSheetId, e.getMessage());
            return 0;
        }
    }
}
