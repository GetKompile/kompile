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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A fact-sheet export carries only the reasoning traces of that fact sheet; the global graph
 * carries every trace. Compact grounding reads this artifact, so a trace leaking across fact
 * sheets would reach a model as evidence about the wrong graph.
 */
class ReasoningTraceArtifactAdapterTest {

    private static final List<String> ALL = List.of("seven-a", "eight", "global", "zero", "seven-b");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aFactSheetSnapshotHoldsOnlyTheTracesStoredForIt() {
        ReasoningTraceStore store = storeWithTracesOfSeveralFactSheets();

        assertEquals(List.of("seven-a", "seven-b"), targets(store.snapshot(7L)));
        assertEquals(List.of("eight"), targets(store.snapshot(8L)));
        assertEquals(List.of(), store.snapshot(9L));
    }

    @Test
    void theGlobalGraphSeesEveryTraceAndANonPositiveIdIsTheGlobalGraph() {
        ReasoningTraceStore store = storeWithTracesOfSeveralFactSheets();

        assertEquals(ALL, targets(store.snapshot()));
        assertEquals(ALL, targets(store.snapshot(null)));
        assertEquals(ALL, targets(store.snapshot(0L)));
        assertEquals(ALL, targets(store.snapshot(-3L)));
    }

    @Test
    void aFactSheetExportBundlesOnlyThatFactSheetsTraces() throws Exception {
        ReasoningTraceStore store = storeWithTracesOfSeveralFactSheets();
        ReasoningTraceArtifactAdapter adapter = new ReasoningTraceArtifactAdapter(store, objectMapper);

        UnifiedGraph seven = new UnifiedGraph().factSheetId(7L);
        adapter.contribute(7L, seven);
        assertEquals(List.of("seven-a", "seven-b"), targets(bundled(seven)));

        UnifiedGraph global = new UnifiedGraph();
        adapter.contribute(null, global);
        assertEquals(ALL, targets(bundled(global)));

        UnifiedGraph nine = new UnifiedGraph().factSheetId(9L);
        adapter.contribute(9L, nine);
        assertNull(nine.artifact(ReasoningTraceArtifactAdapter.ARTIFACT_NAME),
                "a fact sheet without traces gets no trace artifact");

        assertEquals(Set.of(ReasoningTraceArtifactAdapter.ARTIFACT_NAME), adapter.managedArtifactPrefixes(),
                "a bounded artifact read finds the adapter by the artifact it owns");
        assertEquals(ALL.size(), store.drainSince(0L).size(), "exporting leaves the buffer to the turn drain");
    }

    private static ReasoningTraceStore storeWithTracesOfSeveralFactSheets() {
        ReasoningTraceStore store = new ReasoningTraceStore();
        store.storeTrace(7L, trace("seven-a"));
        store.storeTrace(8L, trace("eight"));
        store.storeTrace(trace("global"));
        store.storeTrace(0L, trace("zero"));
        store.storeTrace(7L, trace("seven-b"));
        return store;
    }

    private static Map<String, Object> trace(String targetId) {
        return Map.of("targetId", targetId);
    }

    private static List<String> targets(List<Map<String, Object>> traces) {
        return traces.stream().map(trace -> (String) trace.get("targetId")).toList();
    }

    private List<Map<String, Object>> bundled(UnifiedGraph graph) throws Exception {
        return objectMapper.readValue(graph.artifact(ReasoningTraceArtifactAdapter.ARTIFACT_NAME),
                new TypeReference<List<Map<String, Object>>>() {});
    }
}
