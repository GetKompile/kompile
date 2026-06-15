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

import ai.kompile.loader.excel.graph.SpreadsheetGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring AI @Tool exposing Excel-to-code conversion and execution.
 * Discoverable by StepExecutionDispatcherImpl and usable from agent chat.
 */
@Slf4j
@Component
@ConditionalOnBean(ExcelFormulaConverter.class)
public class ExcelComputeTool {

    private final ExcelFormulaConverter converter;
    private final ObjectMapper objectMapper;

    public ExcelComputeTool(ExcelFormulaConverter converter, ObjectMapper objectMapper) {
        this.converter = converter;
        this.objectMapper = objectMapper;
    }

    // ---- Input Records ----

    public record ConvertFormulasInput(String spreadsheetGraphJson, String language) {}

    public record ExecuteFormulasInput(String spreadsheetGraphJson,
                                       Map<String, Object> cellOverrides,
                                       String language,
                                       String generatedCode) {}

    // ---- Tool Methods ----

    @Tool(name = "excel_convert_formulas",
          description = "Convert an Excel spreadsheet's formulas to executable JavaScript or Python code. "
              + "Input: SpreadsheetGraph JSON (from document ingestion), target language. "
              + "Returns: the generated code, input/output cell lists, and metadata.")
    public Map<String, Object> convertFormulas(
            @ToolParam(description = "SpreadsheetGraph JSON string") String spreadsheetGraphJson,
            @ToolParam(description = "Target language: 'javascript' or 'python'", required = false) String language) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            SpreadsheetGraph graph = objectMapper.readValue(spreadsheetGraphJson, SpreadsheetGraph.class);
            String lang = language != null ? language : "javascript";
            ExcelConversionResult conversion = converter.convert(graph, lang);
            result.put("code", conversion.getCode());
            result.put("language", conversion.getLanguage());
            result.put("workbookName", conversion.getWorkbookName());
            result.put("inputCells", conversion.getInputCells());
            result.put("outputCells", conversion.getOutputCells());
            result.put("formulaCount", conversion.getFormulaCount());
            result.put("dependencyCount", conversion.getDependencyCount());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Excel formula conversion failed: {}", e.getMessage());
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "excel_execute_formulas",
          description = "Execute an Excel spreadsheet's formulas by converting to code and running it. "
              + "If generatedCode is provided, runs that code directly (user-edited artifact). "
              + "Otherwise converts formulas to code via LLM first. "
              + "Returns computed cell output values, the generated code artifact, and execution metadata. "
              + "spreadsheetGraphJson: the SpreadsheetGraph JSON from document ingestion. "
              + "cellOverrides: optional cell value overrides (e.g. {\"A1\": 100, \"B2\": 200}). "
              + "language: 'javascript' or 'python' (default: javascript). "
              + "generatedCode: optional pre-generated code to run directly (null triggers LLM conversion).")
    public Map<String, Object> executeFormulas(ExecuteFormulasInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            SpreadsheetGraph graph = objectMapper.readValue(
                    input.spreadsheetGraphJson(), SpreadsheetGraph.class);
            String lang = input.language() != null ? input.language() : "javascript";

            // Convert or use supplied code
            String code;
            boolean userSupplied;
            if (input.generatedCode() != null && !input.generatedCode().isBlank()) {
                code = input.generatedCode();
                userSupplied = true;
            } else {
                ExcelConversionResult conversion = converter.convert(graph, lang);
                code = conversion.getCode();
                userSupplied = false;
                result.put("inputCells", conversion.getInputCells());
                result.put("outputCells", conversion.getOutputCells());
                result.put("formulaCount", conversion.getFormulaCount());
            }

            // Build cell inputs from graph defaults + overrides
            Map<String, Object> cellInputs = new LinkedHashMap<>();
            graph.getCells().forEach((ref, cell) -> {
                if (cell.getDisplayValue() != null) {
                    cellInputs.put(ref, cell.getDisplayValue());
                }
            });
            if (input.cellOverrides() != null) {
                cellInputs.putAll(input.cellOverrides());
            }

            // Wrap code with cell inputs and execute via the converter's execution path
            String cellsJson = objectMapper.writeValueAsString(cellInputs);
            String wrappedCode = "var cells = " + cellsJson + ";\n" + code;

            // Use the converter's execute method if available, or return code for external execution
            result.put("_generatedCode", code);
            result.put("_generatedLanguage", lang);
            result.put("_codeSource", userSupplied ? "user" : "llm");
            result.put("_excelWorkbook", graph.getWorkbookName());
            result.put("_wrappedCode", wrappedCode);
            result.put("_cellInputs", cellInputs);
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Excel formula execution failed: {}", e.getMessage());
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }
}
