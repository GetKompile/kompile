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
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.unified.UnifiedGraphArtifactContributor;
import ai.kompile.knowledgegraph.unified.UnifiedGraphArtifactImporter;
import ai.kompile.process.discovery.mining.trace.ProcessReasoningTraceStore;
import ai.kompile.process.service.ProcessEngineService;
import ai.kompile.process.workflow.ProcessDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
    public static final String TRACE_MODEL_PREFIX = "trace:process:";

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
        return TRACE_MODEL_PREFIX + suggestionId;
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
            graph.putModel(traceArtifactName(suggestionId), trace);
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
            for (ProcessSuggestion suggestion : suggestions) {
                if (suggestion.getId() != null) {
                    suggestion.setReasoningTraceId(ProcessReasoningTraceStore.traceId(suggestion.getId()));
                    suggestion.setReasoningTraceArtifactName(traceArtifactName(suggestion.getId()));
                }
            }
            putJsonArtifact(graph, SUGGESTIONS_JSON, suggestions);
            contributeTraces(graph, suggestions);
        }

        List<ProcessDefinition> definitions = definitionsForFactSheet(factSheetId);
        if (!definitions.isEmpty()) {
            putJsonArtifact(graph, DEFINITIONS_JSON, definitions);
        }
    }

    @Override
    public int importArtifacts(Long factSheetId, UnifiedGraph graph) {
        int restored = 0;
        restored += importSuggestions(factSheetId, graph);
        restored += importDefinitions(factSheetId, graph);
        restored += importTraces(graph);
        return restored;
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

    private void contributeTraces(UnifiedGraph graph, List<ProcessSuggestion> suggestions) {
        if (traceStore == null) {
            return;
        }
        for (ProcessSuggestion suggestion : suggestions) {
            if (suggestion.getId() == null) {
                continue;
            }
            traceStore.get(suggestion.getId())
                    .ifPresent(trace -> graph.putModel(traceArtifactName(suggestion.getId()), trace));
        }
    }

    private void putJsonArtifact(UnifiedGraph graph, String name, Object value) {
        putJsonArtifactStatic(graph, name, value);
    }

    private static void putJsonArtifactStatic(UnifiedGraph graph, String name, Object value) {
        try {
            graph.putArtifactText(name, MAPPER.writeValueAsString(value));
        } catch (Exception e) {
            log.warn("Could not write process artifact {} into .kgraph: {}", name, e.getMessage());
        }
    }

    private int importSuggestions(Long factSheetId, UnifiedGraph graph) {
        if (suggestionStore == null) {
            return 0;
        }
        String json = graph.artifactText(SUGGESTIONS_JSON);
        if (json == null || json.isBlank()) {
            return 0;
        }
        try {
            List<ProcessSuggestion> suggestions = MAPPER.readValue(json, SUGGESTION_LIST);
            for (ProcessSuggestion suggestion : suggestions) {
                if (factSheetId != null) {
                    suggestion.setFactSheetId(factSheetId);
                }
                if (suggestion.getId() != null) {
                    suggestion.setReasoningTraceId(ProcessReasoningTraceStore.traceId(suggestion.getId()));
                    suggestion.setReasoningTraceArtifactName(traceArtifactName(suggestion.getId()));
                }
                suggestionStore.save(suggestion);
            }
            return suggestions.size();
        } catch (Exception e) {
            log.warn("Could not restore process suggestions from .kgraph: {}", e.getMessage());
            return 0;
        }
    }

    private int importDefinitions(Long factSheetId, UnifiedGraph graph) {
        if (processEngineService == null) {
            return 0;
        }
        String json = graph.artifactText(DEFINITIONS_JSON);
        if (json == null || json.isBlank()) {
            return 0;
        }
        try {
            List<ProcessDefinition> definitions = MAPPER.readValue(json, DEFINITION_LIST);
            for (ProcessDefinition definition : definitions) {
                if (factSheetId != null) {
                    definition.setFactSheetId(factSheetId);
                }
                processEngineService.restoreProcessDefinition(definition);
            }
            return definitions.size();
        } catch (Exception e) {
            log.warn("Could not restore process definitions from .kgraph: {}", e.getMessage());
            return 0;
        }
    }

    private int importTraces(UnifiedGraph graph) {
        if (traceStore == null) {
            return 0;
        }
        int restored = 0;
        Set<String> traceNames = new LinkedHashSet<>(graph.artifacts().keySet()).stream()
                .filter(name -> name.startsWith(TRACE_MODEL_PREFIX))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (String name : traceNames) {
            try {
                ReasoningTrace trace = graph.model(name);
                String suggestionId = name.substring(TRACE_MODEL_PREFIX.length());
                if (trace != null && !suggestionId.isBlank()) {
                    traceStore.save(suggestionId, trace);
                    restored++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not restore process reasoning trace {} from .kgraph: {}",
                        name, e.getMessage());
            }
        }
        return restored;
    }
}
