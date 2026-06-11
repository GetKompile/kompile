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

import ai.kompile.core.graphbuilder.GraphBuildCompletedEvent;
import ai.kompile.process.workflow.ProcessDefinition;

import java.util.List;
import java.util.Map;

/**
 * Analyzes knowledge graph data to discover and suggest process definitions.
 * Examines email flows, Excel formula graphs, and document relationships
 * to identify repeatable patterns that could be automated as workflows.
 */
public interface ProcessDiscoveryService {

    /**
     * Converts a process suggestion into a concrete ProcessDefinition
     * ready to be submitted to the process engine.
     *
     * @param suggestion the discovered suggestion
     * @return a ProcessDefinition in DRAFT status
     */
    ProcessDefinition acceptSuggestion(ProcessSuggestion suggestion);

    /**
     * Analyzes a set of KG node IDs and discovers potential process patterns.
     *
     * @param graphNodeIds list of KG node IDs to analyze (e.g., from a fact sheet)
     * @param options      analysis options (e.g., "minConfidence", "maxSteps", "includeExcel")
     * @return list of discovered process suggestions
     */
    List<ProcessSuggestion> discoverProcesses(List<String> graphNodeIds, Map<String, Object> options);

    /**
     * Analyzes email flow patterns in the KG to find recurring workflows.
     * Looks for patterns like: person A sends to person B with attachment → B processes → B replies.
     *
     * @param graphNodeIds scope of analysis (may be null for all)
     * @return email flow patterns
     */
    List<FlowPattern> analyzeEmailFlows(List<String> graphNodeIds);

    /**
     * Analyzes Excel formula dependency graphs to find computation workflows.
     * Identifies input cells, computation chains, and output cells that form
     * natural process steps.
     *
     * @param graphNodeIds scope of analysis
     * @return Excel computation patterns
     */
    List<FlowPattern> analyzeExcelFlows(List<String> graphNodeIds);

    /**
     * Analyzes document flows (PDF, Office, Tika) in the KG to find authoring,
     * review, and publishing workflows. Looks for patterns like:
     * <ul>
     *   <li>Same author produces multiple documents (authoring pipeline)</li>
     *   <li>Documents share keywords/topics forming topic clusters</li>
     *   <li>Version chains indicating review/approval cycles</li>
     *   <li>Documents with form fields indicating data collection workflows</li>
     * </ul>
     *
     * @param graphNodeIds scope of analysis (may be null for all)
     * @return document flow patterns
     */
    List<FlowPattern> analyzeDocumentFlows(List<String> graphNodeIds);

    /**
     * Analyzes cross-document references to discover hierarchical process patterns.
     * Detects when one document (e.g., an email) references or instructs the usage
     * of another document (e.g., a spreadsheet), creating parent/child process
     * relationships. For example:
     * <ul>
     *   <li>An email describes how to fill out a spreadsheet → parent email process
     *       with child spreadsheet computation process</li>
     *   <li>A procedure document references multiple sub-procedures → parent with
     *       multiple child processes</li>
     *   <li>An email thread with attachments → orchestration process with
     *       sub-processes per attachment type</li>
     * </ul>
     *
     * @param graphNodeIds scope of analysis (may be null for all)
     * @return cross-document flow patterns with child patterns populated
     */
    List<FlowPattern> analyzeCrossDocumentFlows(List<String> graphNodeIds);

    /**
     * Uses an LLM to analyze knowledge graph data and discover business processes
     * that are described or implied in the graph content. Unlike the pattern-based
     * methods above, this can identify processes described in natural language
     * (e.g., an email that describes a multi-step approval workflow, or a document
     * that outlines an onboarding procedure).
     *
     * @param graphNodeIds scope of analysis (may be null for all)
     * @param options      options: "llmProvider" (String), "minConfidence" (Double)
     * @return LLM-discovered process suggestions
     */
    List<ProcessSuggestion> discoverProcessesWithLlm(List<String> graphNodeIds, Map<String, Object> options);

    /**
     * Handles graph build completion events to trigger automatic process discovery.
     */
    void onGraphBuildCompleted(GraphBuildCompletedEvent event);

    // ── Fact-Sheet-Scoped Discovery ───────────────────────────────────────

    /**
     * Discovers all business processes from a fact sheet's knowledge graph.
     * Runs pattern-based and (optionally) LLM-based analysis scoped to the
     * nodes belonging to the given fact sheet.
     *
     * @param factSheetId the fact sheet whose KG to analyze
     * @param options     analysis options (e.g., "minConfidence", "useLlm")
     * @return list of discovered process suggestions with factSheetId set
     */
    List<ProcessSuggestion> discoverProcessesForFactSheet(Long factSheetId, Map<String, Object> options);

    /**
     * Analyzes email flow patterns within a fact sheet's knowledge graph.
     *
     * @param factSheetId the fact sheet whose KG to analyze
     * @return email flow patterns scoped to this fact sheet
     */
    List<FlowPattern> analyzeEmailFlowsForFactSheet(Long factSheetId);

    /**
     * Analyzes Excel computation flows within a fact sheet's knowledge graph.
     *
     * @param factSheetId the fact sheet whose KG to analyze
     * @return Excel computation patterns scoped to this fact sheet
     */
    List<FlowPattern> analyzeExcelFlowsForFactSheet(Long factSheetId);

    /**
     * Analyzes document flows within a fact sheet's knowledge graph.
     *
     * @param factSheetId the fact sheet whose KG to analyze
     * @return document flow patterns scoped to this fact sheet
     */
    List<FlowPattern> analyzeDocumentFlowsForFactSheet(Long factSheetId);

    /**
     * Analyzes cross-document references within a fact sheet's knowledge graph.
     *
     * @param factSheetId the fact sheet whose KG to analyze
     * @return cross-document flow patterns scoped to this fact sheet
     */
    List<FlowPattern> analyzeCrossDocumentFlowsForFactSheet(Long factSheetId);

    /**
     * Uses an LLM to discover processes from a fact sheet's knowledge graph.
     *
     * @param factSheetId the fact sheet whose KG to analyze
     * @param options     options including "llmProvider", "minConfidence"
     * @return LLM-discovered process suggestions scoped to this fact sheet
     */
    List<ProcessSuggestion> discoverProcessesWithLlmForFactSheet(Long factSheetId, Map<String, Object> options);

    /**
     * Accepts a process suggestion and converts it to a ProcessDefinition,
     * preserving the factSheetId binding.
     *
     * @param suggestion the suggestion (with factSheetId set)
     * @return a ProcessDefinition in DRAFT status with factSheetId
     */
    default ProcessDefinition acceptSuggestionForFactSheet(ProcessSuggestion suggestion) {
        return acceptSuggestion(suggestion);
    }
}
