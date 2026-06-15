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

package ai.kompile.compute.graph.excel;

import ai.kompile.compute.graph.engine.ExecutionContext;
import ai.kompile.compute.graph.engine.NodeExecutor;
import ai.kompile.compute.graph.model.*;
import ai.kompile.loader.excel.graph.CellNode;
import ai.kompile.loader.excel.graph.SpreadsheetGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * NodeExecutor that handles {@link NodeExecutionType#EXCEL} nodes.
 *
 * <p>Operates in two modes:</p>
 * <ol>
 *   <li><b>Convert + Execute:</b> If no {@code _generatedCode} parameter exists,
 *       the node's {@code script} (SpreadsheetGraph JSON) is sent to the LLM
 *       for conversion. The generated code is included in outputs as {@code _generatedCode}
 *       so it can be reviewed, edited, and reused.</li>
 *   <li><b>Execute pre-supplied code:</b> If {@code _generatedCode} is set in
 *       the node parameters, the LLM is skipped entirely — the user's code runs as-is.
 *       This supports the edit-then-re-execute workflow.</li>
 * </ol>
 *
 * <p>Outputs always include {@code _generatedCode} and {@code _generatedLanguage}
 * so the caller can persist both the original graph and the code as side-by-side artifacts.</p>
 */
@Slf4j
public class ExcelNodeExecutor implements NodeExecutor {

    private final ExcelFormulaConverter converter;
    private final NodeExecutor scriptExecutor;
    private final ObjectMapper objectMapper;

    public ExcelNodeExecutor(ExcelFormulaConverter converter,
                             NodeExecutor scriptExecutor,
                             ObjectMapper objectMapper) {
        this.converter = converter;
        this.scriptExecutor = scriptExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    public ExecutionResult execute(ComputeNode node, Map<String, Object> inputs, ExecutionContext context) {
        Instant startedAt = Instant.now();
        String execId = context.getExecutionId();

        try {
            // 1. Parse SpreadsheetGraph from node script
            SpreadsheetGraph graph = parseGraph(node);

            // 2. Determine target language (default: javascript)
            String language = "javascript";
            if (node.getParameters() != null && node.getParameters().containsKey("_language")) {
                language = String.valueOf(node.getParameters().get("_language")).toLowerCase();
            }

            // 3. Check for user-supplied code (edited artifact) vs. fresh LLM conversion
            String code;
            boolean userSupplied;
            if (node.getParameters() != null && node.getParameters().containsKey("_generatedCode")) {
                code = String.valueOf(node.getParameters().get("_generatedCode"));
                userSupplied = true;
                log.info("Using user-supplied code for Excel node '{}' ({} chars)",
                        node.getName(), code.length());
            } else {
                log.info("Converting {} formula cells from '{}' to {} via LLM",
                        graph.getFormulaCells().size(), graph.getWorkbookName(), language);
                ExcelConversionResult conversion = converter.convert(graph, language);
                code = conversion.getCode();
                userSupplied = false;
                log.info("Generated {} code: {} chars, {} input cells, {} output cells",
                        language, code.length(),
                        conversion.getInputCells().size(), conversion.getOutputCells().size());
            }

            // 4. Build cell inputs: merge default values from graph + parameters + upstream inputs
            Map<String, Object> cellInputs = buildCellInputs(graph, node.getParameters(), inputs);

            // 5. Build a transient JAVASCRIPT/PYTHON node with the code
            ComputeNode scriptNode = ComputeNode.builder()
                    .id(node.getId())
                    .name(node.getName() + "-" + (userSupplied ? "user" : "generated") + "-" + language)
                    .executionType(language.equals("python")
                            ? NodeExecutionType.PYTHON : NodeExecutionType.JAVASCRIPT)
                    .script(code)
                    .limits(node.getLimits())
                    .build();

            // 6. Build the cells object as a JSON literal prepended to the script.
            //    GraalVM's polyglot proxy for Java Maps isn't enumerable in JS
            //    (Object.assign / JSON.stringify see an empty object), so we
            //    serialize the cells map to a JS object literal that the script
            //    can use natively.
            String cellsJson;
            try {
                cellsJson = objectMapper.writeValueAsString(cellInputs);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to serialize cell inputs: " + e.getMessage(), e);
            }
            String wrappedScript = "var cells = " + cellsJson + ";\n" + code;

            // Update the script node with the wrapped script
            scriptNode = ComputeNode.builder()
                    .id(node.getId())
                    .name(scriptNode.getName())
                    .executionType(scriptNode.getExecutionType())
                    .script(wrappedScript)
                    .limits(node.getLimits())
                    .build();

            Map<String, Object> scriptInputs = new HashMap<>();
            if (inputs != null) {
                scriptInputs.putAll(inputs);
            }

            // 7. Execute via scripting executor
            ExecutionResult scriptResult = scriptExecutor.execute(scriptNode, scriptInputs, context);

            // 8. Build outputs — always include the code as an artifact
            Instant completedAt = Instant.now();
            Map<String, Object> outputs = scriptResult.getOutputs() != null
                    ? new LinkedHashMap<>(scriptResult.getOutputs()) : new LinkedHashMap<>();
            outputs.put("_excelWorkbook", graph.getWorkbookName());
            outputs.put("_formulaCount", graph.getFormulaCells().size());
            outputs.put("_generatedCode", code);
            outputs.put("_generatedLanguage", language);
            outputs.put("_codeSource", userSupplied ? "user" : "llm");

            return ExecutionResult.builder()
                    .nodeId(node.getId())
                    .executionId(execId)
                    .status(scriptResult.getStatus())
                    .outputs(outputs)
                    .error(scriptResult.getError())
                    .stackTrace(scriptResult.getStackTrace())
                    .consoleOutput(scriptResult.getConsoleOutput())
                    .startedAt(startedAt)
                    .completedAt(completedAt)
                    .duration(Duration.between(startedAt, completedAt))
                    .build();

        } catch (Exception e) {
            log.error("Excel node execution failed for '{}': {}", node.getName(), e.getMessage(), e);
            Instant completedAt = Instant.now();
            return ExecutionResult.builder()
                    .nodeId(node.getId())
                    .executionId(execId)
                    .status(ExecutionStatus.FAILED)
                    .error(e.getMessage())
                    .stackTrace(Arrays.toString(e.getStackTrace()))
                    .startedAt(startedAt)
                    .completedAt(completedAt)
                    .duration(Duration.between(startedAt, completedAt))
                    .build();
        }
    }

    /**
     * Convert-only mode — returns the generated code without executing it.
     * Used by the REST API for the review/edit workflow.
     */
    public ExcelConversionResult convertOnly(String spreadsheetGraphJson, String language) {
        try {
            SpreadsheetGraph graph = objectMapper.readValue(spreadsheetGraphJson, SpreadsheetGraph.class);
            String lang = language != null ? language.toLowerCase() : "javascript";
            return converter.convert(graph, lang);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to convert spreadsheet: " + e.getMessage(), e);
        }
    }

    @Override
    public Set<NodeExecutionType> supportedTypes() {
        return Set.of(NodeExecutionType.EXCEL);
    }

    @Override
    public String validate(ComputeNode node) {
        if (node.getScript() == null || node.getScript().isBlank()) {
            return "Excel node requires a SpreadsheetGraph JSON in the script field";
        }
        try {
            parseGraph(node);
            return null;
        } catch (Exception e) {
            return "Invalid SpreadsheetGraph JSON: " + e.getMessage();
        }
    }

    private SpreadsheetGraph parseGraph(ComputeNode node) {
        try {
            return objectMapper.readValue(node.getScript(), SpreadsheetGraph.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to parse SpreadsheetGraph from node script: " + e.getMessage(), e);
        }
    }

    /**
     * Merge default display values from the graph, node parameters, and upstream inputs
     * into a flat cells map keyed by sanitized cell references.
     */
    private Map<String, Object> buildCellInputs(SpreadsheetGraph graph,
                                                  Map<String, Object> parameters,
                                                  Map<String, Object> upstreamInputs) {
        Map<String, Object> cells = new LinkedHashMap<>();

        // Start with default values from the spreadsheet
        for (CellNode cell : graph.getCells().values()) {
            if (!"FORMULA".equals(cell.getCellType()) && !"BLANK".equals(cell.getCellType())) {
                String key = ExcelFormulaConverter.sanitizeCellRef(cell.getCellReference());
                cells.put(key, parseDisplayValue(cell));
            }
        }

        // Override with node parameters (e.g., {"Sheet1_A1": 100})
        if (parameters != null) {
            for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                if (!entry.getKey().startsWith("_")) { // skip meta params like _language
                    cells.put(entry.getKey(), entry.getValue());
                }
            }
        }

        // Override with upstream inputs named as cell refs
        if (upstreamInputs != null) {
            for (Map.Entry<String, Object> entry : upstreamInputs.entrySet()) {
                if (entry.getKey().contains("_") && !entry.getKey().startsWith("_")) {
                    cells.put(entry.getKey(), entry.getValue());
                }
            }
        }

        return cells;
    }

    private Object parseDisplayValue(CellNode cell) {
        String val = cell.getDisplayValue();
        if (val == null || val.isEmpty()) return null;
        switch (cell.getCellType()) {
            case "NUMERIC":
                try {
                    if (val.contains(".")) return Double.parseDouble(val);
                    return Long.parseLong(val);
                } catch (NumberFormatException e) {
                    return val;
                }
            case "BOOLEAN":
                return Boolean.parseBoolean(val);
            default:
                return val;
        }
    }
}
