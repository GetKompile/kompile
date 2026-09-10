/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.process.discovery;

import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.process.discovery.mining.trace.ProcessReasoningTraceStore;
import ai.kompile.process.service.ProcessEngineService;
import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;

class ProcessUnifiedGraphArtifactsTest {

    @TempDir
    Path tempDir;

    @Test
    @SuppressWarnings("unchecked")
    void contributionDoesNotInventDanglingTraceReferences() {
        ProcessSuggestionStore store = mock(ProcessSuggestionStore.class);
        ProcessSuggestion stored = ProcessSuggestion.builder()
                .id("suggestion-1").factSheetId(42L).name("Review").build();
        when(store.listByFactSheet(42L)).thenReturn(List.of(stored));

        ObjectProvider<ProcessSuggestionStore> stores = mock(ObjectProvider.class);
        ObjectProvider<ProcessReasoningTraceStore> traces = mock(ObjectProvider.class);
        ObjectProvider<ProcessEngineService> engines = mock(ObjectProvider.class);
        when(stores.getIfAvailable()).thenReturn(store);
        when(traces.getIfAvailable()).thenReturn(null);
        when(engines.getIfAvailable()).thenReturn(null);
        ProcessUnifiedGraphArtifacts artifacts =
                new ProcessUnifiedGraphArtifacts(stores, traces, engines);
        UnifiedGraph graph = new UnifiedGraph();

        artifacts.contribute(42L, graph);

        assertNull(stored.getReasoningTraceId());
        assertNull(stored.getReasoningTraceArtifactName());
        String portable = graph.artifactText(ProcessUnifiedGraphArtifacts.SUGGESTIONS_JSON);
        assertFalse(portable.contains(ProcessUnifiedGraphArtifacts.traceArtifactName("suggestion-1")), portable);
    }

    @Test
    @SuppressWarnings("unchecked")
    void processTraceRoundTripsThroughVersionedJsonArtifact() {
        ProcessSuggestionStore sourceSuggestions = mock(ProcessSuggestionStore.class);
        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .id("suggestion-1").factSheetId(42L).name("Review").build();
        when(sourceSuggestions.listByFactSheet(42L)).thenReturn(List.of(suggestion));
        ProcessReasoningTraceStore sourceTraces =
                new ProcessReasoningTraceStore(tempDir.resolve("source-traces"));
        ReasoningTrace trace = ReasoningTrace.of(
                ReasoningTrace.Step.fact("observed", 0.9, "source"));
        sourceTraces.save("suggestion-1", trace);
        ObjectProvider<ProcessSuggestionStore> sourceStores = mock(ObjectProvider.class);
        ObjectProvider<ProcessReasoningTraceStore> sourceTraceProvider = mock(ObjectProvider.class);
        ObjectProvider<ProcessEngineService> noEngine = mock(ObjectProvider.class);
        when(sourceStores.getIfAvailable()).thenReturn(sourceSuggestions);
        when(sourceTraceProvider.getIfAvailable()).thenReturn(sourceTraces);
        when(noEngine.getIfAvailable()).thenReturn(null);
        UnifiedGraph graph = new UnifiedGraph();
        new ProcessUnifiedGraphArtifacts(sourceStores, sourceTraceProvider, noEngine)
                .contribute(42L, graph);

        String artifactName = ProcessUnifiedGraphArtifacts.traceArtifactName("suggestion-1");
        assertTrue(graph.artifactText(artifactName).contains("\"formatVersion\":1"));
        ProcessReasoningTraceStore targetTraces =
                new ProcessReasoningTraceStore(tempDir.resolve("target-traces"));
        ObjectProvider<ProcessSuggestionStore> noSuggestions = mock(ObjectProvider.class);
        ObjectProvider<ProcessReasoningTraceStore> targetTraceProvider = mock(ObjectProvider.class);
        when(noSuggestions.getIfAvailable()).thenReturn(null);
        when(targetTraceProvider.getIfAvailable()).thenReturn(targetTraces);
        ProcessUnifiedGraphArtifacts importer =
                new ProcessUnifiedGraphArtifacts(noSuggestions, targetTraceProvider, noEngine);

        importer.validateArtifacts(42L, graph);
        assertEquals(1, importer.importArtifacts(42L, graph));
        assertEquals(trace.steps(), targetTraces.get("suggestion-1").orElseThrow().steps());
    }

    @Test
    @SuppressWarnings("unchecked")
    void preflightAndStoreRejectPathEscapingSuggestionIds() {
        ProcessSuggestion unsafe = ProcessSuggestion.builder()
                .id("../escape").factSheetId(42L).name("Unsafe").build();
        UnifiedGraph graph = new UnifiedGraph();
        ProcessUnifiedGraphArtifacts.putSuggestions(graph, List.of(unsafe));

        ObjectProvider<ProcessSuggestionStore> stores = mock(ObjectProvider.class);
        ObjectProvider<ProcessReasoningTraceStore> traces = mock(ObjectProvider.class);
        ObjectProvider<ProcessEngineService> engines = mock(ObjectProvider.class);
        when(stores.getIfAvailable()).thenReturn(null);
        when(traces.getIfAvailable()).thenReturn(null);
        when(engines.getIfAvailable()).thenReturn(null);
        ProcessUnifiedGraphArtifacts artifacts =
                new ProcessUnifiedGraphArtifacts(stores, traces, engines);

        assertThrows(IllegalArgumentException.class,
                () -> artifacts.validateArtifacts(42L, graph));
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessSuggestionStore(tempDir).save(unsafe));
    }

    @Test
    @SuppressWarnings("unchecked")
    void importedProcessDefinitionsCannotConferLiveExecutionApproval() {
        ProcessEngineService engine = mock(ProcessEngineService.class);
        when(engine.supportsProcessDefinitionSnapshotRemoval()).thenReturn(true);
        ObjectProvider<ProcessSuggestionStore> stores = mock(ObjectProvider.class);
        ObjectProvider<ProcessReasoningTraceStore> traces = mock(ObjectProvider.class);
        ObjectProvider<ProcessEngineService> engines = mock(ObjectProvider.class);
        when(stores.getIfAvailable()).thenReturn(null);
        when(traces.getIfAvailable()).thenReturn(null);
        when(engines.getIfAvailable()).thenReturn(engine);
        ProcessUnifiedGraphArtifacts artifacts =
                new ProcessUnifiedGraphArtifacts(stores, traces, engines);
        ProcessDefinition live = ProcessDefinition.builder()
                .id("portable-process").version(3).status(ProcessStatus.LIVE)
                .approvedBy("archive").build();
        UnifiedGraph graph = new UnifiedGraph();
        ProcessUnifiedGraphArtifacts.putDefinitions(graph, List.of(live));

        artifacts.validateArtifacts(42L, graph);
        artifacts.importArtifacts(42L, graph);

        verify(engine).restoreProcessDefinition(argThat(restored ->
                restored.getStatus() == ProcessStatus.DRAFT
                        && restored.getApprovedBy() == null
                        && restored.getApprovedAt() == null
                        && Long.valueOf(42L).equals(restored.getFactSheetId())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void exactExistingApprovedDefinitionIsNotDowngradedOnReimport() {
        ProcessEngineService engine = mock(ProcessEngineService.class);
        ProcessDefinition approved = ProcessDefinition.builder()
                .id("existing-process").factSheetId(42L).version(2)
                .name("Existing").status(ProcessStatus.APPROVED).approvedBy("admin").build();
        when(engine.getProcess("existing-process", 2)).thenReturn(approved);
        ObjectProvider<ProcessSuggestionStore> stores = mock(ObjectProvider.class);
        ObjectProvider<ProcessReasoningTraceStore> traces = mock(ObjectProvider.class);
        ObjectProvider<ProcessEngineService> engines = mock(ObjectProvider.class);
        when(stores.getIfAvailable()).thenReturn(null);
        when(traces.getIfAvailable()).thenReturn(null);
        when(engines.getIfAvailable()).thenReturn(engine);
        ProcessUnifiedGraphArtifacts artifacts =
                new ProcessUnifiedGraphArtifacts(stores, traces, engines);
        UnifiedGraph graph = new UnifiedGraph();
        ProcessUnifiedGraphArtifacts.putDefinitions(graph, List.of(approved));

        artifacts.validateArtifacts(42L, graph);
        artifacts.importArtifacts(42L, graph);

        verify(engine, never()).restoreProcessDefinition(org.mockito.ArgumentMatchers.any());
        assertEquals(ProcessStatus.APPROVED, approved.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void preflightRejectsCrossFactSheetProcessIdentityCollisions() {
        ProcessSuggestionStore store = mock(ProcessSuggestionStore.class);
        ProcessSuggestion incoming = ProcessSuggestion.builder()
                .id("shared-id").factSheetId(42L).name("Incoming").build();
        ProcessSuggestion existing = ProcessSuggestion.builder()
                .id("shared-id").factSheetId(7L).name("Existing").build();
        when(store.get("shared-id")).thenReturn(java.util.Optional.of(existing));
        ObjectProvider<ProcessSuggestionStore> stores = mock(ObjectProvider.class);
        ObjectProvider<ProcessReasoningTraceStore> traces = mock(ObjectProvider.class);
        ObjectProvider<ProcessEngineService> engines = mock(ObjectProvider.class);
        when(stores.getIfAvailable()).thenReturn(store);
        when(traces.getIfAvailable()).thenReturn(null);
        when(engines.getIfAvailable()).thenReturn(null);
        ProcessUnifiedGraphArtifacts artifacts =
                new ProcessUnifiedGraphArtifacts(stores, traces, engines);
        UnifiedGraph graph = new UnifiedGraph();
        ProcessUnifiedGraphArtifacts.putSuggestions(graph, List.of(incoming));

        assertThrows(IllegalArgumentException.class,
                () -> artifacts.validateArtifacts(42L, graph));
    }

    @Test
    @SuppressWarnings("unchecked")
    void preflightRejectsOrphanTraceArtifacts() {
        UnifiedGraph graph = new UnifiedGraph();
        ProcessUnifiedGraphArtifacts.putTrace(graph, "victim", ReasoningTrace.of(
                ReasoningTrace.Step.fact("orphan", 1.0, "source")));
        ObjectProvider<ProcessSuggestionStore> stores = mock(ObjectProvider.class);
        ObjectProvider<ProcessReasoningTraceStore> traces = mock(ObjectProvider.class);
        ObjectProvider<ProcessEngineService> engines = mock(ObjectProvider.class);
        when(stores.getIfAvailable()).thenReturn(null);
        when(traces.getIfAvailable()).thenReturn(
                new ProcessReasoningTraceStore(tempDir.resolve("orphan-target")));
        when(engines.getIfAvailable()).thenReturn(null);
        ProcessUnifiedGraphArtifacts artifacts =
                new ProcessUnifiedGraphArtifacts(stores, traces, engines);

        assertThrows(IllegalArgumentException.class,
                () -> artifacts.validateArtifacts(42L, graph));
    }

    @Test
    @SuppressWarnings("unchecked")
    void preparedImportRollsBackNewProcessArtifactsIdempotently() {
        ProcessSuggestionStore suggestions =
                new ProcessSuggestionStore(tempDir.resolve("rollback-suggestions"));
        ProcessReasoningTraceStore tracesStore =
                new ProcessReasoningTraceStore(tempDir.resolve("rollback-traces"));
        ProcessEngineService engine = mock(ProcessEngineService.class);
        when(engine.supportsProcessDefinitionSnapshotRemoval()).thenReturn(true);
        doThrow(new IllegalStateException("first rollback failure"))
                .doNothing().when(engine).removeProcessDefinitionSnapshot("rollback-process", 1);
        ObjectProvider<ProcessSuggestionStore> stores = mock(ObjectProvider.class);
        ObjectProvider<ProcessReasoningTraceStore> traces = mock(ObjectProvider.class);
        ObjectProvider<ProcessEngineService> engines = mock(ObjectProvider.class);
        when(stores.getIfAvailable()).thenReturn(suggestions);
        when(traces.getIfAvailable()).thenReturn(tracesStore);
        when(engines.getIfAvailable()).thenReturn(engine);
        ProcessUnifiedGraphArtifacts artifacts =
                new ProcessUnifiedGraphArtifacts(stores, traces, engines);
        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .id("rollback-suggestion").factSheetId(42L).name("Rollback").build();
        ProcessDefinition definition = ProcessDefinition.builder()
                .id("rollback-process").factSheetId(42L).version(1)
                .name("Rollback").status(ProcessStatus.DRAFT).build();
        UnifiedGraph incoming = new UnifiedGraph();
        ProcessUnifiedGraphArtifacts.putTrace(incoming, suggestion.getId(), ReasoningTrace.of(
                ReasoningTrace.Step.fact("rollback", 1.0, "source")));
        ProcessUnifiedGraphArtifacts.putSuggestions(incoming, List.of(suggestion));
        ProcessUnifiedGraphArtifacts.putDefinitions(incoming, List.of(definition));

        var prepared = artifacts.prepareArtifacts(42L, incoming, new UnifiedGraph());
        assertTrue(suggestions.get(suggestion.getId()).isEmpty());
        assertTrue(tracesStore.get(suggestion.getId()).isEmpty());
        assertEquals(3, prepared.commit());
        assertTrue(suggestions.get(suggestion.getId()).isPresent());
        assertTrue(tracesStore.get(suggestion.getId()).isPresent());

        assertThrows(IllegalStateException.class, prepared::rollback);
        assertTrue(suggestions.get(suggestion.getId()).isEmpty());
        assertTrue(tracesStore.get(suggestion.getId()).isEmpty());
        prepared.rollback();
        assertTrue(suggestions.get(suggestion.getId()).isEmpty());
        assertTrue(tracesStore.get(suggestion.getId()).isEmpty());
        verify(engine, times(2)).removeProcessDefinitionSnapshot("rollback-process", 1);
    }
}
