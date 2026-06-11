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

import ai.kompile.process.workflow.ProcessDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for discovering process patterns from knowledge graph data.
 */
@RestController
@RequestMapping("/api/process/discovery")
@ConditionalOnBean(ProcessDiscoveryService.class)
public class ProcessDiscoveryController {

    private final ProcessDiscoveryService discoveryService;

    @Autowired
    public ProcessDiscoveryController(ProcessDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
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
}
