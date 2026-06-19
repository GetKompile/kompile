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

package ai.kompile.process.workflow;

import ai.kompile.process.ontology.ProvenanceCitation;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * A single step in a process definition.
 * Generalizes: AUTO, AUTO+HITL (APPROVE), HUMAN, and CONTROL gate types.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProcessStep {

    /** Hierarchical step ID, e.g., "1.1", "2.3". */
    private String id;
    private String name;
    private String description;
    private StepType stepType;
    private StepTrigger trigger;
    /** Keys of data items consumed by this step. */
    private List<String> inputKeys;
    /** Keys of data items produced by this step. */
    private List<String> outputKeys;
    /** ID of the agent spec that executes this step; null for HUMAN steps. */
    private String agentSpecId;
    /** Defines who approves and how — required for APPROVE type steps. */
    private ApprovalPolicy approvalPolicy;
    private TimeoutPolicy timeoutPolicy;
    private ErrorPolicy errorPolicy;
    /**
     * SpEL expression map evaluated by AUTO/SCRIPT steps to produce outputs.
     * Keys are output variable names, values are SpEL expressions evaluated against runData.
     * Example: {"totalAmount": "#amount * #quantity", "isHighValue": "#amount > 10000"}
     */
    private Map<String, String> executionExpressions;

    // ---- TOOL_CALL configuration ----
    /** Name of the registered MCP/Spring AI tool to invoke (e.g. "rag_query", "read_file"). */
    private String toolName;
    /**
     * Map of tool argument names → SpEL expressions that resolve against runData.
     * Example: {"query": "#searchTerm", "maxResults": "5"}
     */
    private Map<String, String> toolArguments;

    // ---- HTTP_CALL configuration ----
    /** HTTP method (GET, POST, PUT, DELETE). */
    private String httpMethod;
    /** URL to call. May contain SpEL expressions in ${...} placeholders. */
    private String httpUrl;
    /** HTTP request headers. Values may be SpEL expressions. */
    private Map<String, String> httpHeaders;
    /** SpEL expression that evaluates to the request body (for POST/PUT). */
    private String httpBody;
    /** Key in runData where the response body is stored (default: "httpResponse"). */
    private String httpResponseKey;

    // ---- SCRIPT configuration ----
    /** Script language: "javascript" or "python". Defaults to "javascript" if not set. */
    private String scriptLanguage;
    /** Script source code for SCRIPT steps. Executed via GraalVM Polyglot (JavaScript or Python).
     *  The script has access to all runData variables and can return results. */
    private String scriptBody;
    /** Key in runData where the script result is stored (default: "scriptResult"). */
    private String scriptOutputKey;

    // ---- EXCEL_COMPUTE configuration ----
    /** SpreadsheetGraph JSON from kompile-loader-excel. Contains all cells, formulas, and dependencies. */
    private String excelGraphJson;
    /** Input cell value overrides. Keys are sanitized cell refs (e.g., "Sheet1_A1"), values are the cell values. */
    private Map<String, Object> excelCellOverrides;
    /** Target language for LLM-generated code: "javascript" (default) or "python". */
    private String excelTargetLanguage;
    /** Key in runData where the computed cell outputs are stored. */
    private String excelOutputKey;
    /**
     * User-supplied or previously-generated code to execute instead of calling the LLM.
     * When set, the LLM conversion step is skipped entirely — this code runs as-is.
     * This supports the review/edit workflow: convert once, review the code, edit if
     * needed, then save the edited version here for re-execution.
     */
    private String excelGeneratedCode;

    // ---- PIPELINE configuration ----
    /** Serialized pipeline definition JSON (SequencePipeline or GraphPipeline).
     *  Deserialized and executed via the kompile pipelines framework. */
    private String pipelineDefinitionJson;
    /**
     * Maps runData keys to pipeline input Data keys.
     * Keys are runData variable names, values are the Data key names the pipeline expects.
     * Example: {"customerName": "name", "orderAmount": "amount"}
     */
    private Map<String, String> pipelineInputMapping;
    /** Key in runData where the pipeline output Data map is stored (default: "pipelineResult"). */
    private String pipelineOutputKey;

    // ---- CAMEL_ROUTE configuration ----
    /** Saved route ID from the Camel route registry. Optional when camelInlineScript is set. */
    private String camelRouteId;
    /** Inline Camel route definition in XML DSL, YAML DSL, or Camel Simple expression form. */
    private String camelInlineScript;
    /** Key in runData where the full Camel route output map is stored. */
    private String camelOutputKey;

    // ---- DROOLS_* configuration ----
    /** Drools DRL source for DROOLS_RULE and DROOLS_INFERENCE steps. */
    private String droolsRuleDrl;
    /** Optional agenda group used by DROOLS_RULE steps for targeted rule firing. */
    private String droolsAgendaGroup;
    /** Optional maximum rule firings for this step. */
    private Integer droolsMaxFirings;
    /** Decision table content for DROOLS_DECISION_TABLE steps. CSV text or Base64-encoded XLS/XLSX. */
    private String droolsDecisionTable;
    /** Optional decision table input type: CSV, XLS, or XLSX. */
    private String droolsInputType;
    /** Optional worksheet name for XLS/XLSX decision tables. */
    private String droolsWorksheetName;
    /** Key in runData where the full rules output map is stored. */
    private String droolsOutputKey;
    /** Output key that contains the branch decision. Defaults to "decision" when omitted. */
    private String droolsDecisionKey;

    // ---- WORKFLOW configuration ----
    /** Workflow engine type such as "xircuits" or "n8n". */
    private String workflowEngineType;
    /** Saved workflow name from the workflow store. Optional when workflowInlineContent is set. */
    private String workflowName;
    /** Inline workflow JSON content. Optional when workflowName is set. */
    private String workflowInlineContent;
    /** Optional execution timeout for workflow subprocesses. */
    private Integer workflowTimeoutSeconds;
    /**
     * Maps runData keys to workflow input keys. If omitted, all runData is passed.
     * Example: {"customerName": "name", "orderAmount": "amount"}
     */
    private Map<String, String> workflowInputMapping;
    /** Key in runData where the full workflow output map is stored. */
    private String workflowOutputKey;

    /** IDs of controls that fire after this step completes. */
    private List<String> controlIds;
    /** Step IDs that must complete before this step can start. */
    private List<String> dependsOn;
    /**
     * Optional SpEL boolean expression evaluated against runData before the step
     * executes. If it evaluates false, the step is marked SKIPPED.
     */
    private String conditionExpression;
    /** Human-readable branch label that produced conditionExpression, e.g. "Approved". */
    private String conditionLabel;
    /** At least one of these roles must be present in runData actor roles before the step can execute. */
    private List<String> requiredRoles;
    /** All of these permissions must be present in runData actor permissions before the step can execute. */
    private List<String> requiredPermissions;
    private double confidence;
    private List<ProvenanceCitation> provenance;
    /** Knowledge graph node IDs that this step is defined to operate on (definition-time binding). */
    private List<String> graphNodeIds;
    private Map<String, Object> metadata;
}
