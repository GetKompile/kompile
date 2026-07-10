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

import ai.kompile.process.discovery.mining.ProcessCalibrationService;
import ai.kompile.process.discovery.mining.trace.ProcessReasoningTraceStore;
import ai.kompile.process.workflow.ProcessDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST endpoints for discovering process patterns from knowledge graph data.
 */
@RestController
@RequestMapping("/api/process/discovery")
@ConditionalOnBean(ProcessDiscoveryService.class)
public class ProcessDiscoveryController {

    private final ProcessDiscoveryService discoveryService;
    private ProcessSuggestionStore suggestionStore;
    private ProcessReasoningTraceStore reasoningTraceStore;

    @Autowired
    public ProcessDiscoveryController(ProcessDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @Autowired(required = false)
    public void setSuggestionStore(ProcessSuggestionStore suggestionStore) {
        this.suggestionStore = suggestionStore;
    }

    @Autowired(required = false)
    public void setReasoningTraceStore(ProcessReasoningTraceStore reasoningTraceStore) {
        this.reasoningTraceStore = reasoningTraceStore;
    }

    /** Optional: accept/dismiss decisions on mined suggestions (re)fit the miner's calibrator. */
    private ProcessCalibrationService calibrationService;

    @Autowired(required = false)
    public void setCalibrationService(
            ProcessCalibrationService calibrationService) {
        this.calibrationService = calibrationService;
    }

    /**
     * Discover process suggestions from knowledge graph data.
     */
    @PostMapping("/suggest")
    public ResponseEntity<Map<String, Object>> suggestProcesses(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<String> graphNodeIds = request.get("graphNodeIds") instanceof List l ? l : null;
        @SuppressWarnings("unchecked")
        Map<String, Object> options = request.get("options") instanceof Map m ? m : Map.of();

        List<ProcessSuggestion> suggestions = discoveryService.discoverProcesses(graphNodeIds, options);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", suggestions.size());
        response.put("suggestions", suggestions);
        return ResponseEntity.ok(response);
    }

    /**
     * Analyze email flow patterns.
     */
    @PostMapping("/email-flows")
    public ResponseEntity<Map<String, Object>> analyzeEmailFlows(@RequestBody(required = false) Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<String> graphNodeIds = request != null && request.get("graphNodeIds") instanceof List l ? l : null;

        List<FlowPattern> patterns = discoveryService.analyzeEmailFlows(graphNodeIds);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", patterns.size());
        response.put("patterns", patterns);
        return ResponseEntity.ok(response);
    }

    /**
     * Analyze Excel computation flows.
     */
    @PostMapping("/excel-flows")
    public ResponseEntity<Map<String, Object>> analyzeExcelFlows(@RequestBody(required = false) Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<String> graphNodeIds = request != null && request.get("graphNodeIds") instanceof List l ? l : null;

        List<FlowPattern> patterns = discoveryService.analyzeExcelFlows(graphNodeIds);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", patterns.size());
        response.put("patterns", patterns);
        return ResponseEntity.ok(response);
    }

    /**
     * Analyze document flow patterns (author pipelines, version chains,
     * topic clusters, form collections).
     */
    @PostMapping("/document-flows")
    public ResponseEntity<Map<String, Object>> analyzeDocumentFlows(@RequestBody(required = false) Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<String> graphNodeIds = request != null && request.get("graphNodeIds") instanceof List l ? l : null;

        List<FlowPattern> patterns = discoveryService.analyzeDocumentFlows(graphNodeIds);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", patterns.size());
        response.put("patterns", patterns);
        return ResponseEntity.ok(response);
    }

    /**
     * Analyze cross-document flow patterns. Detects when one document (e.g., email)
     * references or describes procedures involving another document (e.g., spreadsheet),
     * producing hierarchical process patterns with parent/child relationships.
     */
    @PostMapping("/cross-document-flows")
    public ResponseEntity<Map<String, Object>> analyzeCrossDocumentFlows(@RequestBody(required = false) Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<String> graphNodeIds = request != null && request.get("graphNodeIds") instanceof List l ? l : null;

        List<FlowPattern> patterns = discoveryService.analyzeCrossDocumentFlows(graphNodeIds);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", patterns.size());
        response.put("patterns", patterns);
        return ResponseEntity.ok(response);
    }

    /**
     * Use an LLM to discover processes from knowledge graph data.
     * The LLM analyzes node descriptions, document content, and relationships
     * to identify business processes described in natural language.
     */
    @PostMapping("/llm-discover")
    public ResponseEntity<Map<String, Object>> discoverProcessesWithLlm(@RequestBody(required = false) Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<String> graphNodeIds = request != null && request.get("graphNodeIds") instanceof List l ? l : null;
        @SuppressWarnings("unchecked")
        Map<String, Object> options = request != null && request.get("options") instanceof Map m ? m : Map.of();

        List<ProcessSuggestion> suggestions = discoveryService.discoverProcessesWithLlm(graphNodeIds, options);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", suggestions.size());
        response.put("suggestions", suggestions);
        return ResponseEntity.ok(response);
    }

    /**
     * Accept a process suggestion and convert it into a ProcessDefinition.
     * Returns a DRAFT ProcessDefinition that can be submitted to the process engine.
     * If the suggestion has childSuggestions, child ProcessDefinitions are also
     * created and linked via parentProcessId/childProcessIds.
     */
    @PostMapping("/accept")
    public ResponseEntity<ProcessDefinition> acceptSuggestion(@RequestBody ProcessSuggestion suggestion) {
        ProcessDefinition definition = discoveryService.acceptSuggestion(suggestion);
        return ResponseEntity.ok(definition);
    }

    // ── Fact-Sheet-Scoped Endpoints ───────────────────────────────────────

    /**
     * Discover all business processes from a fact sheet's knowledge graph.
     * Combines pattern-based and LLM-based analysis scoped to the fact sheet.
     */
    @PostMapping("/fact-sheet/{factSheetId}")
    public ResponseEntity<Map<String, Object>> discoverProcessesForFactSheet(
            @PathVariable Long factSheetId,
            @RequestBody(required = false) Map<String, Object> options) {
        List<ProcessSuggestion> suggestions = discoveryService.discoverProcessesForFactSheet(
                factSheetId, options != null ? options : Map.of());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("factSheetId", factSheetId);
        response.put("count", suggestions.size());
        response.put("suggestions", suggestions);
        return ResponseEntity.ok(response);
    }

    /**
     * Analyze email flow patterns within a fact sheet's knowledge graph.
     */
    @PostMapping("/fact-sheet/{factSheetId}/email-flows")
    public ResponseEntity<Map<String, Object>> analyzeEmailFlowsForFactSheet(@PathVariable Long factSheetId) {
        List<FlowPattern> patterns = discoveryService.analyzeEmailFlowsForFactSheet(factSheetId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("factSheetId", factSheetId);
        response.put("count", patterns.size());
        response.put("patterns", patterns);
        return ResponseEntity.ok(response);
    }

    /**
     * Analyze Excel computation flows within a fact sheet's knowledge graph.
     */
    @PostMapping("/fact-sheet/{factSheetId}/excel-flows")
    public ResponseEntity<Map<String, Object>> analyzeExcelFlowsForFactSheet(@PathVariable Long factSheetId) {
        List<FlowPattern> patterns = discoveryService.analyzeExcelFlowsForFactSheet(factSheetId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("factSheetId", factSheetId);
        response.put("count", patterns.size());
        response.put("patterns", patterns);
        return ResponseEntity.ok(response);
    }

    /**
     * Analyze document flows within a fact sheet's knowledge graph.
     */
    @PostMapping("/fact-sheet/{factSheetId}/document-flows")
    public ResponseEntity<Map<String, Object>> analyzeDocumentFlowsForFactSheet(@PathVariable Long factSheetId) {
        List<FlowPattern> patterns = discoveryService.analyzeDocumentFlowsForFactSheet(factSheetId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("factSheetId", factSheetId);
        response.put("count", patterns.size());
        response.put("patterns", patterns);
        return ResponseEntity.ok(response);
    }

    /**
     * Analyze cross-document references within a fact sheet's knowledge graph.
     */
    @PostMapping("/fact-sheet/{factSheetId}/cross-document-flows")
    public ResponseEntity<Map<String, Object>> analyzeCrossDocumentFlowsForFactSheet(@PathVariable Long factSheetId) {
        List<FlowPattern> patterns = discoveryService.analyzeCrossDocumentFlowsForFactSheet(factSheetId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("factSheetId", factSheetId);
        response.put("count", patterns.size());
        response.put("patterns", patterns);
        return ResponseEntity.ok(response);
    }

    /**
     * Use an LLM to discover processes from a fact sheet's knowledge graph.
     */
    @PostMapping("/fact-sheet/{factSheetId}/llm-discover")
    public ResponseEntity<Map<String, Object>> discoverProcessesWithLlmForFactSheet(
            @PathVariable Long factSheetId,
            @RequestBody(required = false) Map<String, Object> options) {
        List<ProcessSuggestion> suggestions = discoveryService.discoverProcessesWithLlmForFactSheet(
                factSheetId, options != null ? options : Map.of());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("factSheetId", factSheetId);
        response.put("count", suggestions.size());
        response.put("suggestions", suggestions);
        return ResponseEntity.ok(response);
    }

    // ── Suggestion Store Endpoints ───────────────────────────────────────

    /**
     * List all stored process suggestions.
     */
    @GetMapping("/suggestions")
    public ResponseEntity<Map<String, Object>> listSuggestions(
            @RequestParam(required = false) Long factSheetId,
            @RequestParam(required = false, defaultValue = "false") boolean pendingOnly,
            @RequestParam(required = false, defaultValue = "false") boolean includeSuperseded) {
        if (suggestionStore == null) {
            return ResponseEntity.ok(Map.of("count", 0, "suggestions", List.of()));
        }

        List<ProcessSuggestion> results;
        if (factSheetId != null) {
            results = suggestionStore.listByFactSheet(factSheetId);
        } else if (pendingOnly) {
            results = suggestionStore.listPending();
        } else {
            results = suggestionStore.listAll();
        }
        // Superseded generations stay stored (lineage — mark, never delete) but only the heads
        // list by default; pass includeSuperseded=true to walk a process's history.
        if (!includeSuperseded) {
            results = results.stream().filter(s -> s.getSupersededAt() == null).toList();
        }

        // Best-first: the learned acceptance likelihood (when the re-ranker has trained on the
        // user's accept/dismiss history) outranks the fused confidence.
        List<ProcessSuggestion> ranked = new ArrayList<>(results);
        ranked.sort(Comparator
                .comparingInt((ProcessSuggestion s) -> s.getReasoningRank() != null
                        ? s.getReasoningRank() : Integer.MAX_VALUE)
                .thenComparing(Comparator.comparingDouble((ProcessSuggestion s) ->
                        s.getLearnedScore() != null ? s.getLearnedScore() : s.getConfidence()).reversed()));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", ranked.size());
        response.put("suggestions", ranked);
        return ResponseEntity.ok(response);
    }

    /**
     * Get a specific stored suggestion by ID.
     */
    @GetMapping("/suggestions/{id}")
    public ResponseEntity<ProcessSuggestion> getSuggestion(@PathVariable String id) {
        if (suggestionStore == null) {
            return ResponseEntity.notFound().build();
        }

        Optional<ProcessSuggestion> suggestion = suggestionStore.get(id);
        return suggestion.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Get the walkable reasoning trace that explains a stored mined suggestion.
     */
    @GetMapping(value = "/suggestions/{id}/trace", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getSuggestionTrace(@PathVariable String id) {
        if (reasoningTraceStore == null) {
            return ResponseEntity.notFound().build();
        }
        return reasoningTraceStore.get(id)
                .map(trace -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(trace.toJson()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Accept a stored suggestion by ID, converting it to a ProcessDefinition.
     */
    @PostMapping("/suggestions/{id}/accept")
    public ResponseEntity<ProcessDefinition> acceptStoredSuggestion(@PathVariable String id) {
        if (suggestionStore == null) {
            return ResponseEntity.notFound().build();
        }

        Optional<ProcessSuggestion> suggestion = suggestionStore.get(id);
        if (suggestion.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        ProcessDefinition definition = discoveryService.acceptSuggestion(suggestion.get());
        suggestionStore.markAccepted(id, definition.getId());
        // An accepted mined suggestion is a positive calibration label.
        if (calibrationService != null) {
            calibrationService.recordOutcome(suggestion.get(), true);
        }
        return ResponseEntity.ok(definition);
    }

    /**
     * Delete a stored suggestion by ID.
     */
    @DeleteMapping("/suggestions/{id}")
    public ResponseEntity<Void> deleteSuggestion(@PathVariable String id) {
        if (suggestionStore == null) {
            return ResponseEntity.notFound().build();
        }

        // Dismissing a still-pending mined suggestion is a negative calibration label
        // (deleting an already-accepted one is cleanup, not feedback).
        if (calibrationService != null) {
            suggestionStore.get(id)
                    .filter(s -> !Boolean.TRUE.equals(s.getAccepted()))
                    .ifPresent(s -> calibrationService.recordOutcome(s, false));
        }

        suggestionStore.delete(id);
        return ResponseEntity.noContent().build();
    }
}
