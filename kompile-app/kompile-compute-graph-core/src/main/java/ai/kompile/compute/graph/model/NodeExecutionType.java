package ai.kompile.compute.graph.model;

/**
 * Defines the execution strategy for a compute node.
 */
public enum NodeExecutionType {

    /**
     * Execute JavaScript via GraalVM polyglot engine.
     */
    JAVASCRIPT,

    /**
     * Execute Python via Python4J (embedded CPython via JavaCPP).
     * Supports full CPython including native libraries (numpy, pandas, etc.).
     */
    PYTHON,

    /**
     * Execute using Drools rule engine — single rule or rule group.
     */
    DROOLS_RULE,

    /**
     * Execute using Drools with full inference chaining.
     * All rules fire based on working memory state.
     */
    DROOLS_INFERENCE,

    /**
     * Simple expression evaluation (SpEL or MVEL).
     * For lightweight calculations without full scripting overhead.
     */
    EXPRESSION,

    /**
     * No-op passthrough — just forwards inputs as outputs.
     * Useful for aggregation/fan-in nodes.
     */
    PASSTHROUGH,

    /**
     * Execute a Xircuits workflow (Python-based visual workflow engine).
     * The node script contains a .xircuits workflow definition (JSON).
     * Executed via the xircuits CLI as a subprocess.
     */
    XIRCUITS,

    /**
     * Execute an n8n workflow (JavaScript-based workflow automation).
     * The node script contains an n8n workflow definition (JSON).
     * Executed via the n8n CLI as a subprocess.
     */
    N8N,

    /**
     * Execute an Excel spreadsheet as code.
     * The node's script contains the SpreadsheetGraph JSON (formula cells + dependencies).
     * An LLM converts the formulas to JavaScript, which is then executed via GraalVM.
     * Node parameters provide input cell values to override.
     */
    EXCEL,

    /**
     * Execute an Apache Camel route definition.
     * The node script contains a Camel route in XML DSL or YAML DSL.
     * Inputs are sent as exchange headers/body; outputs are collected from the result exchange.
     * Supports Enterprise Integration Patterns (EIP): routing, transformation, mediation.
     */
    CAMEL_ROUTE,

    /**
     * Execute using Drools decision tables (XLS/CSV spreadsheet-based rules).
     * The node script contains Base64-encoded spreadsheet content or a path to the table.
     * Parameters can override cell values before compilation.
     */
    DROOLS_DECISION_TABLE
}
