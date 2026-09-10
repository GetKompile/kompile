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

package ai.kompile.process.discovery;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.graph.reasoning.explain.ReasoningTraceJsonCodec;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.unified.UnifiedGraphArtifactContributor;
import ai.kompile.knowledgegraph.unified.UnifiedGraphArtifactImporter;
import ai.kompile.process.discovery.mining.trace.ProcessReasoningTraceStore;
import ai.kompile.process.service.ProcessEngineService;
import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Makes process-mining state portable through {@code .kgraph}.
 */
@Component
public class ProcessUnifiedGraphArtifacts
        implements UnifiedGraphArtifactContributor, UnifiedGraphArtifactImporter {

    private static final Logger log = LoggerFactory.getLogger(ProcessUnifiedGraphArtifacts.class);
    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public static final String SUGGESTIONS_JSON = "process/suggestions.json";
    public static final String DEFINITIONS_JSON = "process/definitions.json";
    public static final String TRACE_JSON_PREFIX = "process/reasoning-traces/v1/";
    public static final String TRACE_JSON_SUFFIX = ".json";
    public static final String LEGACY_TRACE_MODEL_PREFIX = "trace:process:";
    private static final int MAX_TRACE_ARTIFACTS = 256;

    private static final TypeReference<List<ProcessSuggestion>> SUGGESTION_LIST =
            new TypeReference<>() { };
    private static final TypeReference<List<ProcessDefinition>> DEFINITION_LIST =
            new TypeReference<>() { };

    private final ProcessSuggestionStore suggestionStore;
    private final ProcessReasoningTraceStore traceStore;
    private final ProcessEngineService processEngineService;

    public ProcessUnifiedGraphArtifacts(ObjectProvider<ProcessSuggestionStore> suggestionStore,
                                        ObjectProvider<ProcessReasoningTraceStore> traceStore,
                                        ObjectProvider<ProcessEngineService> processEngineService) {
        this.suggestionStore = suggestionStore == null ? null : suggestionStore.getIfAvailable();
        this.traceStore = traceStore == null ? null : traceStore.getIfAvailable();
        this.processEngineService = processEngineService == null ? null : processEngineService.getIfAvailable();
    }

    public static String traceArtifactName(String suggestionId) {
        return TRACE_JSON_PREFIX + suggestionId + TRACE_JSON_SUFFIX;
    }

    public static void putSuggestions(UnifiedGraph graph, List<ProcessSuggestion> suggestions) {
        if (graph != null && suggestions != null && !suggestions.isEmpty()) {
            putJsonArtifactStatic(graph, SUGGESTIONS_JSON, suggestions);
        }
    }

    public static void putDefinitions(UnifiedGraph graph, List<ProcessDefinition> definitions) {
        if (graph != null && definitions != null && !definitions.isEmpty()) {
            putJsonArtifactStatic(graph, DEFINITIONS_JSON, definitions);
        }
    }

    public static void putTrace(UnifiedGraph graph, String suggestionId, ReasoningTrace trace) {
        if (graph != null && suggestionId != null && !suggestionId.isBlank() && trace != null) {
            graph.putArtifactText(traceArtifactName(suggestionId), ReasoningTraceJsonCodec.encode(
                    ProcessReasoningTraceStore.traceId(suggestionId), suggestionId, trace));
        }
    }

    public static void putArtifacts(UnifiedGraph graph,
                                    List<ProcessSuggestion> suggestions,
                                    List<ProcessDefinition> definitions) {
        putSuggestions(graph, suggestions);
        putDefinitions(graph, definitions);
    }

    @Override
    public void contribute(Long factSheetId, UnifiedGraph graph) {
        List<ProcessSuggestion> suggestions = suggestionsForFactSheet(factSheetId);
        if (!suggestions.isEmpty()) {
            List<ProcessSuggestion> portableSuggestions = suggestions.stream()
                    .map(ProcessUnifiedGraphArtifacts::copySuggestion)
                    .toList();
            for (ProcessSuggestion suggestion : portableSuggestions) {
                suggestion.setReasoningTraceId(null);
                suggestion.setReasoningTraceArtifactName(null);
                if (suggestion.getId() != null && traceStore != null) {
                    traceStore.get(suggestion.getId()).ifPresent(trace -> {
                        putTrace(graph, suggestion.getId(), trace);
                        suggestion.setReasoningTraceId(ProcessReasoningTraceStore.traceId(suggestion.getId()));
                        suggestion.setReasoningTraceArtifactName(traceArtifactName(suggestion.getId()));
                    });
                }
            }
            putJsonArtifact(graph, SUGGESTIONS_JSON, portableSuggestions);
        }

        List<ProcessDefinition> definitions = definitionsForFactSheet(factSheetId);
        if (!definitions.isEmpty()) {
            putJsonArtifact(graph, DEFINITIONS_JSON, definitions);
        }
    }

    @Override
    public void validateArtifacts(Long factSheetId, UnifiedGraph graph) {
        List<ProcessSuggestion> suggestions = decodeSuggestions(graph);
        List<ProcessDefinition> definitions = decodeDefinitions(graph);
        validateTraceArtifacts(graph, suggestions);
        validateDestinationOwnership(factSheetId, suggestions, definitions);
    }

    @Override
    public PreparedImport prepareArtifacts(Long factSheetId, UnifiedGraph incoming, UnifiedGraph previous) {
        validateArtifacts(factSheetId, incoming);
        List<ProcessSuggestion> suggestions = decodeSuggestions(incoming);
        suggestions.forEach(suggestion -> suggestion.setFactSheetId(factSheetId));
        List<ProcessDefinition> definitions = decodeDefinitions(incoming);
        definitions.forEach(definition -> definition.setFactSheetId(factSheetId));
        Set<String> traceIds = incoming.artifacts().keySet().stream()
                .filter(ProcessUnifiedGraphArtifacts::isJsonTraceArtifact)
                .map(ProcessUnifiedGraphArtifacts::suggestionIdFromTraceName)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Map<String, ProcessSuggestion> priorSuggestions = new LinkedHashMap<>();
        if (suggestionStore != null) {
            for (ProcessSuggestion suggestion : suggestions) {
                suggestionStore.get(suggestion.getId())
                        .ifPresent(prior -> priorSuggestions.put(suggestion.getId(), copySuggestion(prior)));
            }
        }
        Map<String, ReasoningTrace> priorTraces = new LinkedHashMap<>();
        if (traceStore != null) {
            for (String traceId : traceIds) {
                traceStore.get(traceId).ifPresent(trace -> priorTraces.put(traceId, trace));
            }
        }
        Map<String, ProcessDefinition> priorDefinitions = new LinkedHashMap<>();
        if (processEngineService != null) {
            for (ProcessDefinition definition : definitions) {
                ProcessDefinition prior = existingDefinition(definition);
                if (prior != null) {
                    priorDefinitions.put(definitionKey(definition),
                            MAPPER.convertValue(prior, ProcessDefinition.class));
                }
            }
            boolean createsDefinition = definitions.stream()
                    .anyMatch(definition -> !priorDefinitions.containsKey(definitionKey(definition)));
            if (createsDefinition && !processEngineService.supportsProcessDefinitionSnapshotRemoval()) {
                throw new IllegalStateException(
                        "Process engine does not support exact snapshot rollback");
            }
        }
        AtomicBoolean rolledBack = new AtomicBoolean();
        return new PreparedImport() {
            @Override
            public int commit() {
                return importArtifacts(factSheetId, incoming);
            }

            @Override
            public synchronized void rollback() {
                if (rolledBack.get()) return;
                List<RuntimeException> failures = new java.util.ArrayList<>();
                rollbackDefinitions(definitions, priorDefinitions, failures);
                rollbackSuggestions(suggestions, priorSuggestions, failures);
                rollbackTraces(traceIds, priorTraces, failures);
                if (!failures.isEmpty()) {
                    IllegalStateException failure =
                            new IllegalStateException("Process artifact rollback was incomplete");
                    failures.forEach(failure::addSuppressed);
                    throw failure;
                }
                rolledBack.set(true);
            }
        };
    }

    @Override
    public int importArtifacts(Long factSheetId, UnifiedGraph graph) {
        int restored = 0;
        restored += importTraces(graph);
        restored += importSuggestions(factSheetId, graph);
        restored += importDefinitions(factSheetId, graph);
        return restored;
    }

    @Override
    public boolean supportsExactRollback() {
        return true;
    }

    @Override
    public Set<String> managedArtifactPrefixes() {
        return Set.of(SUGGESTIONS_JSON, DEFINITIONS_JSON, TRACE_JSON_PREFIX, LEGACY_TRACE_MODEL_PREFIX);
    }

    private List<ProcessSuggestion> suggestionsForFactSheet(Long factSheetId) {
        if (suggestionStore == null) {
            return List.of();
        }
        return factSheetId == null ? suggestionStore.listAll() : suggestionStore.listByFactSheet(factSheetId);
    }

    private List<ProcessDefinition> definitionsForFactSheet(Long factSheetId) {
        if (processEngineService == null) {
            return List.of();
        }
        return processEngineService.listProcessDefinitions().stream()
                .filter(Objects::nonNull)
                .filter(d -> factSheetId == null || Objects.equals(factSheetId, d.getFactSheetId()))
                .toList();
    }

    private void putJsonArtifact(UnifiedGraph graph, String name, Object value) {
        putJsonArtifactStatic(graph, name, value);
    }

    private static void putJsonArtifactStatic(UnifiedGraph graph, String name, Object value) {
        try {
            graph.putArtifactText(name, MAPPER.writeValueAsString(value));
        } catch (Exception e) {
            throw new IllegalStateException("Could not write process artifact " + name + " into .kgraph", e);
        }
    }

    private static ProcessSuggestion copySuggestion(ProcessSuggestion suggestion) {
        return MAPPER.convertValue(suggestion, ProcessSuggestion.class);
    }

    private int importSuggestions(Long factSheetId, UnifiedGraph graph) {
        if (suggestionStore == null) {
            return 0;
        }
        List<ProcessSuggestion> suggestions = decodeSuggestions(graph);
        for (ProcessSuggestion suggestion : suggestions) {
            if (factSheetId != null) {
                suggestion.setFactSheetId(factSheetId);
            }
            ProcessSuggestion existing = suggestionStore.get(suggestion.getId()).orElse(null);
            if (existing != null && existing.equals(suggestion)) continue;
            String traceName = traceArtifactName(suggestion.getId());
            if (traceStore != null && graph.artifact(traceName) != null) {
                suggestion.setReasoningTraceId(ProcessReasoningTraceStore.traceId(suggestion.getId()));
                suggestion.setReasoningTraceArtifactName(traceName);
            } else {
                suggestion.setReasoningTraceId(null);
                suggestion.setReasoningTraceArtifactName(null);
            }
            suggestionStore.save(suggestion);
        }
        return suggestions.size();
    }

    private int importDefinitions(Long factSheetId, UnifiedGraph graph) {
        if (processEngineService == null) {
            return 0;
        }
        List<ProcessDefinition> definitions = decodeDefinitions(graph);
        for (ProcessDefinition definition : definitions) {
            if (factSheetId != null) {
                definition.setFactSheetId(factSheetId);
            }
            ProcessDefinition existing = existingDefinition(definition);
            if (existing != null && existing.equals(definition)) continue;
            // Portable archives never confer execution approval in the destination environment.
            definition.setStatus(ProcessStatus.DRAFT);
            definition.setApprovedBy(null);
            definition.setApprovedAt(null);
            processEngineService.restoreProcessDefinition(definition);
        }
        return definitions.size();
    }

    private int importTraces(UnifiedGraph graph) {
        if (traceStore == null) return 0;
        Set<String> traceNames = new LinkedHashSet<>(graph.artifacts().keySet()).stream()
                .filter(ProcessUnifiedGraphArtifacts::isJsonTraceArtifact)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        int restored = 0;
        for (String name : traceNames) {
            String suggestionId = suggestionIdFromTraceName(name);
            ReasoningTrace trace = ReasoningTraceJsonCodec.decode(graph.artifactText(name), suggestionId);
            traceStore.save(suggestionId, trace);
            restored++;
        }
        return restored;
    }

    private static List<ProcessSuggestion> decodeSuggestions(UnifiedGraph graph) {
        String json = graph == null ? null : graph.artifactText(SUGGESTIONS_JSON);
        if (json == null || json.isBlank()) return List.of();
        try {
            List<ProcessSuggestion> suggestions = MAPPER.readValue(json, SUGGESTION_LIST);
            if (suggestions == null || suggestions.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Process suggestions artifact contains null entries");
            }
            suggestions.forEach(suggestion -> validatePortableId(suggestion.getId(), "suggestion"));
            return suggestions;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not decode process suggestions artifact", e);
        }
    }

    private static List<ProcessDefinition> decodeDefinitions(UnifiedGraph graph) {
        String json = graph == null ? null : graph.artifactText(DEFINITIONS_JSON);
        if (json == null || json.isBlank()) return List.of();
        try {
            List<ProcessDefinition> definitions = MAPPER.readValue(json, DEFINITION_LIST);
            if (definitions == null || definitions.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Process definitions artifact contains null entries");
            }
            definitions.forEach(definition -> validatePortableId(definition.getId(), "definition"));
            return definitions;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not decode process definitions artifact", e);
        }
    }

    private static void validateTraceArtifacts(UnifiedGraph graph, List<ProcessSuggestion> suggestions) {
        if (graph == null) return;
        List<String> jsonTraces = graph.artifacts().keySet().stream()
                .filter(ProcessUnifiedGraphArtifacts::isJsonTraceArtifact).sorted().toList();
        if (jsonTraces.size() > MAX_TRACE_ARTIFACTS) {
            throw new IllegalArgumentException("Too many process trace artifacts");
        }
        Set<String> suggestionIds = suggestions.stream().map(ProcessSuggestion::getId)
                .collect(java.util.stream.Collectors.toSet());
        for (String name : jsonTraces) {
            String suggestionId = suggestionIdFromTraceName(name);
            validatePortableId(suggestionId, "trace suggestion");
            if (!suggestionIds.contains(suggestionId)) {
                throw new IllegalArgumentException("Orphan process trace artifact: " + name);
            }
            byte[] bytes = graph.artifact(name);
            if (bytes == null || bytes.length > ReasoningTraceJsonCodec.MAX_BYTES) {
                throw new IllegalArgumentException("Invalid process trace artifact size: " + name);
            }
            ReasoningTraceJsonCodec.decode(graph.artifactText(name), suggestionId);
        }
        for (ProcessSuggestion suggestion : suggestions) {
            String expectedName = traceArtifactName(suggestion.getId());
            if (suggestion.getReasoningTraceArtifactName() != null
                    && !expectedName.equals(suggestion.getReasoningTraceArtifactName())) {
                throw new IllegalArgumentException("Process suggestion trace artifact identity mismatch: "
                        + suggestion.getId());
            }
            String expectedId = ProcessReasoningTraceStore.traceId(suggestion.getId());
            if (suggestion.getReasoningTraceId() != null
                    && !expectedId.equals(suggestion.getReasoningTraceId())) {
                throw new IllegalArgumentException("Process suggestion trace ID mismatch: "
                        + suggestion.getId());
            }
        }
        graph.artifacts().keySet().stream()
                .filter(name -> name.startsWith(LEGACY_TRACE_MODEL_PREFIX))
                .forEach(name -> validatePortableId(
                        name.substring(LEGACY_TRACE_MODEL_PREFIX.length()), "legacy trace suggestion"));
    }

    private static void validatePortableId(String id, String kind) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}") || id.contains("..")) {
            throw new IllegalArgumentException("Invalid process " + kind + " ID");
        }
    }

    private static boolean isJsonTraceArtifact(String name) {
        return name.startsWith(TRACE_JSON_PREFIX) && name.endsWith(TRACE_JSON_SUFFIX);
    }

    private static String suggestionIdFromTraceName(String name) {
        return name.substring(TRACE_JSON_PREFIX.length(), name.length() - TRACE_JSON_SUFFIX.length());
    }

    private void validateDestinationOwnership(Long factSheetId,
                                              List<ProcessSuggestion> suggestions,
                                              List<ProcessDefinition> definitions) {
        if (factSheetId == null) return;
        if (suggestionStore != null) {
            for (ProcessSuggestion suggestion : suggestions) {
                suggestionStore.get(suggestion.getId()).ifPresent(existing -> {
                    if (!Objects.equals(existing.getFactSheetId(), factSheetId)) {
                        throw new IllegalArgumentException("Process suggestion ID belongs to another fact sheet: "
                                + suggestion.getId());
                    }
                    ProcessSuggestion incoming = copySuggestion(suggestion);
                    incoming.setFactSheetId(factSheetId);
                    if (!existing.equals(incoming)) {
                        throw new IllegalArgumentException("Conflicting process suggestion snapshot: "
                                + suggestion.getId());
                    }
                });
            }
        }
        if (processEngineService != null) {
            for (ProcessDefinition definition : definitions) {
                ProcessDefinition incoming = MAPPER.convertValue(definition, ProcessDefinition.class);
                incoming.setFactSheetId(factSheetId);
                ProcessDefinition existing = existingDefinition(incoming);
                if (existing != null && !Objects.equals(existing.getFactSheetId(), factSheetId)) {
                    throw new IllegalArgumentException("Process definition ID/version belongs to another fact sheet: "
                            + definition.getId() + " v" + Math.max(1, definition.getVersion()));
                }
                if (existing != null && !existing.equals(incoming)) {
                    throw new IllegalArgumentException("Conflicting process definition snapshot: "
                            + definition.getId() + " v" + definition.getVersion());
                }
            }
        }
    }

    private ProcessDefinition existingDefinition(ProcessDefinition definition) {
        if (processEngineService == null) return null;
        try {
            return processEngineService.getProcess(
                    definition.getId(), Math.max(1, definition.getVersion()));
        } catch (IllegalArgumentException notFound) {
            return null;
        }
    }

    private void rollbackDefinitions(List<ProcessDefinition> definitions,
                                     Map<String, ProcessDefinition> priorDefinitions,
                                     List<RuntimeException> failures) {
        if (processEngineService == null) return;
        for (int i = definitions.size() - 1; i >= 0; i--) {
            ProcessDefinition incoming = definitions.get(i);
            ProcessDefinition prior = priorDefinitions.get(definitionKey(incoming));
            try {
                if (prior != null) processEngineService.restoreProcessDefinition(prior);
                else processEngineService.removeProcessDefinitionSnapshot(
                        incoming.getId(), Math.max(1, incoming.getVersion()));
            } catch (RuntimeException failure) {
                failures.add(failure);
            }
        }
    }

    private void rollbackSuggestions(List<ProcessSuggestion> suggestions,
                                     Map<String, ProcessSuggestion> priorSuggestions,
                                     List<RuntimeException> failures) {
        if (suggestionStore == null) return;
        for (int i = suggestions.size() - 1; i >= 0; i--) {
            ProcessSuggestion incoming = suggestions.get(i);
            ProcessSuggestion prior = priorSuggestions.get(incoming.getId());
            try {
                if (prior != null) suggestionStore.save(prior);
                else suggestionStore.delete(incoming.getId());
            } catch (RuntimeException failure) {
                failures.add(failure);
            }
        }
    }

    private void rollbackTraces(Set<String> traceIds, Map<String, ReasoningTrace> priorTraces,
                                List<RuntimeException> failures) {
        if (traceStore == null) return;
        List<String> ids = new java.util.ArrayList<>(traceIds);
        for (int i = ids.size() - 1; i >= 0; i--) {
            String id = ids.get(i);
            ReasoningTrace prior = priorTraces.get(id);
            try {
                if (prior != null) traceStore.save(id, prior);
                else traceStore.delete(id);
            } catch (RuntimeException failure) {
                failures.add(failure);
            }
        }
    }

    private static String definitionKey(ProcessDefinition definition) {
        return definition.getId() + "_v" + Math.max(1, definition.getVersion());
    }
}
