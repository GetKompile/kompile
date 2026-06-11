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

package ai.kompile.process.service;

import java.util.List;
import java.util.Map;

/**
 * Dispatches step execution to external integrations (MCP tools, HTTP endpoints).
 * Implemented in kompile-app-main where the tool beans and HTTP clients are available.
 * The process engine delegates to this interface during TOOL_CALL and HTTP_CALL steps.
 */
public interface StepExecutionDispatcher {

    /**
     * Invokes a registered MCP/Spring AI tool by name.
     *
     * @param toolName  the tool name (e.g. "rag_query", "read_file")
     * @param arguments the tool arguments as a key-value map
     * @return the tool result as a map (serialized tool output)
     * @throws IllegalArgumentException if the tool is not found
     */
    Map<String, Object> invokeTool(String toolName, Map<String, Object> arguments);

    /**
     * Makes an HTTP request to an external service.
     *
     * @param method  HTTP method (GET, POST, PUT, DELETE)
     * @param url     the target URL
     * @param headers request headers (may be null)
     * @param body    request body (may be null, for POST/PUT)
     * @return the response as a map with keys: "statusCode", "body", "headers"
     */
    Map<String, Object> executeHttpCall(String method, String url,
                                         Map<String, String> headers, Object body);

    /**
     * Executes a script (JavaScript or Python) via GraalVM Polyglot engine.
     * The script receives runData as bindings and can return computed outputs.
     *
     * @param language   "javascript" or "python"
     * @param scriptBody the source code to execute
     * @param runData    current workflow run data, available as variable bindings in the script
     * @return map of output key → value produced by the script
     * @throws IllegalArgumentException if the language is not supported
     */
    Map<String, Object> executeScript(String language, String scriptBody, Map<String, Object> runData);

    /**
     * Converts an Excel spreadsheet's formulas to code via LLM without executing.
     * Returns the generated code as an artifact for review/editing.
     *
     * @param spreadsheetGraphJson  the SpreadsheetGraph JSON from kompile-loader-excel
     * @param targetLanguage        "javascript" (default) or "python"
     * @return map containing "code", "language", "inputCells", "outputCells", and metadata
     * @throws IllegalStateException if the Excel compute engine is not available
     */
    Map<String, Object> convertExcel(String spreadsheetGraphJson, String targetLanguage);

    /**
     * Executes Excel spreadsheet computation. If {@code generatedCode} is provided,
     * it is used directly (user-edited artifact). Otherwise, formulas are converted
     * via LLM first. The result always includes {@code _generatedCode} so the caller
     * can persist it as an artifact.
     *
     * @param spreadsheetGraphJson  the SpreadsheetGraph JSON from kompile-loader-excel
     * @param cellOverrides         input cell value overrides (sanitized refs → values)
     * @param targetLanguage        "javascript" (default) or "python"
     * @param generatedCode         user-supplied code to execute (skip LLM if non-null)
     * @return map of computed cell outputs plus {@code _generatedCode} artifact
     * @throws IllegalStateException if the Excel compute engine is not available
     */
    Map<String, Object> executeExcel(String spreadsheetGraphJson,
                                      Map<String, Object> cellOverrides,
                                      String targetLanguage,
                                      String generatedCode);

    /**
     * Executes an Apache Camel route as a workflow step. The route can reference
     * a saved route ID from the Camel registry or provide an inline route script.
     *
     * @param routeId      saved Camel route ID, optional when inlineScript is present
     * @param inlineScript inline XML/YAML/Simple route definition, optional when routeId is present
     * @param inputs       exchange body/header values derived from run data
     * @return map of route outputs
     */
    default Map<String, Object> executeCamelRoute(String routeId,
                                                  String inlineScript,
                                                  Map<String, Object> inputs) {
        throw new UnsupportedOperationException("Camel route execution is not supported by this dispatcher");
    }

    /**
     * Executes Drools rules or full forward-chaining inference as a workflow step.
     *
     * @param drl          Drools DRL source
     * @param facts        named facts derived from run data
     * @param agendaGroup  optional agenda group for targeted rule execution
     * @param maxFirings   optional per-step rule firing limit
     * @param inference    true to fire all rules as an inference step
     * @return map of rule outputs
     */
    default Map<String, Object> executeDroolsRules(String drl,
                                                   Map<String, Object> facts,
                                                   String agendaGroup,
                                                   Integer maxFirings,
                                                   boolean inference) {
        throw new UnsupportedOperationException("Drools rule execution is not supported by this dispatcher");
    }

    /**
     * Executes a Drools decision table as a workflow step.
     *
     * @param decisionTable CSV decision table content or Base64-encoded XLS/XLSX
     * @param inputType     optional input type, CSV or XLS/XLSX
     * @param facts         named facts derived from run data
     * @param worksheetName optional worksheet name for spreadsheet tables
     * @return map of decision table outputs
     */
    default Map<String, Object> executeDroolsDecisionTable(String decisionTable,
                                                           String inputType,
                                                           Map<String, Object> facts,
                                                           String worksheetName) {
        throw new UnsupportedOperationException("Drools decision table execution is not supported by this dispatcher");
    }

    /**
     * Executes a saved or inline workflow definition using a workflow engine such
     * as Xircuits or n8n.
     *
     * @param engineType      workflow engine type, for example "xircuits" or "n8n"
     * @param workflowName    saved workflow name, optional when inlineContent is present
     * @param inlineContent   inline workflow JSON, optional when workflowName is present
     * @param inputs          input values derived from run data
     * @param timeoutSeconds  optional execution timeout
     * @return map of workflow outputs
     */
    default Map<String, Object> executeWorkflow(String engineType,
                                                String workflowName,
                                                String inlineContent,
                                                Map<String, Object> inputs,
                                                Integer timeoutSeconds) {
        throw new UnsupportedOperationException("Workflow execution is not supported by this dispatcher");
    }

    /**
     * Lists all available tool integrations that can be used in TOOL_CALL steps.
     *
     * @return list of tool descriptors with name, description, category, and input schema
     */
    List<Map<String, Object>> listAvailableTools();

    /**
     * Resolves a table/spreadsheet graph JSON from knowledge graph node IDs.
     * Searches the given graph node IDs for a node whose metadata contains
     * a serialized graph — either a SpreadsheetGraph (Excel) or a
     * TableCellGraphBuilder graph (HTML, PDF, Word, Google Sheets tables).
     * Checks both "formulaGraph" and "tableGraph" metadata keys, and
     * recognizes both entity_subtype="sheet" and entity_subtype="table" on
     * TABLE-level nodes.
     *
     * @param graphNodeIds  list of KG node IDs attached to the step
     * @return the graph JSON string, or null if not found
     */
    default String resolveExcelGraphJson(List<String> graphNodeIds) {
        return null;
    }

    /**
     * Executes an Excel computation and returns a DispatchResult that includes
     * both the outputs and any graph node IDs discovered during resolution.
     * Default implementation delegates to {@link #executeExcel} with no discovered IDs.
     */
    default DispatchResult executeExcelWithResult(String spreadsheetGraphJson,
                                                    Map<String, Object> cellOverrides,
                                                    String targetLanguage,
                                                    String generatedCode) {
        return DispatchResult.of(executeExcel(spreadsheetGraphJson, cellOverrides, targetLanguage, generatedCode));
    }

    /**
     * Invokes a tool and returns a DispatchResult that may include graph node IDs
     * discovered by the tool during execution.
     * Default implementation delegates to {@link #invokeTool} with no discovered IDs.
     */
    default DispatchResult invokeToolWithResult(String toolName, Map<String, Object> arguments) {
        return DispatchResult.of(invokeTool(toolName, arguments));
    }

    /**
     * Executes a kompile pipeline from its serialized JSON definition.
     * The pipeline is deserialized, an executor is created, and the pipeline runs
     * with the provided inputs mapped into a {@code Data} object.
     *
     * @param pipelineDefinitionJson  serialized Pipeline JSON (SequencePipeline, GraphPipeline, etc.)
     * @param inputs                  input key-value pairs to pass into the pipeline's Data
     * @return map of output key → value produced by the pipeline execution
     * @throws IllegalStateException if the pipeline framework is not available
     * @throws IllegalArgumentException if the pipeline JSON is invalid
     */
    default Map<String, Object> executePipeline(String pipelineDefinitionJson, Map<String, Object> inputs) {
        throw new UnsupportedOperationException("Pipeline execution is not supported by this dispatcher");
    }
}
