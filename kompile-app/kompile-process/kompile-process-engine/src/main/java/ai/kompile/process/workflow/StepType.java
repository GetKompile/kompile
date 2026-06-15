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

/**
 * Execution mode of a {@link ProcessStep}.
 */
public enum StepType {
    /** Fully automated — no human gate. Evaluates SpEL expressions. */
    AUTO,
    /** Automated with a human approval gate before output is committed. */
    APPROVE,
    /** Entirely manual — a human performs the work. */
    HUMAN,
    /** A SOX/compliance control gate that must pass before the workflow proceeds. */
    CONTROL_GATE,
    /** Invokes a registered MCP/Spring AI tool by name with arguments from runData. */
    TOOL_CALL,
    /** Makes an HTTP request to an external service and captures the response. */
    HTTP_CALL,
    /** Executes a JavaScript or Python script via GraalVM Polyglot with full access to runData. */
    SCRIPT,
    /** Converts Excel spreadsheet formulas to code via LLM and executes the generated code.
     *  Requires a SpreadsheetGraph JSON (from kompile-loader-excel) in the step's excelGraphJson field. */
    EXCEL_COMPUTE,
    /** Executes a kompile pipeline (SequencePipeline / GraphPipeline) as a workflow step.
     *  The pipeline definition is provided as JSON; inputs are mapped from runData and outputs merged back. */
    PIPELINE,
    /** Executes an Apache Camel route using a saved route ID or inline XML/YAML/Simple route definition. */
    CAMEL_ROUTE,
    /** Executes a Drools DRL rule set against facts derived from runData. */
    DROOLS_RULE,
    /** Executes Drools forward-chaining inference against facts derived from runData. */
    DROOLS_INFERENCE,
    /** Executes a Drools spreadsheet decision table against facts derived from runData. */
    DROOLS_DECISION_TABLE,
    /** Executes a saved or inline workflow through a workflow engine such as Xircuits or n8n. */
    WORKFLOW
}
