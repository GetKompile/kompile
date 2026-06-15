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

import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.loader.excel.graph.CellNode;
import ai.kompile.loader.excel.graph.FormulaDependency;
import ai.kompile.loader.excel.graph.SpreadsheetGraph;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Converts an Excel {@link SpreadsheetGraph} (cell formulas + dependencies) into
 * executable JavaScript or Python code using an LLM.
 *
 * <p>The converter builds a detailed prompt containing all formula cells, their
 * dependencies, named ranges, and sample values, then asks the LLM to produce
 * a self-contained function that accepts input cell values and returns all
 * computed output cells.</p>
 */
@Slf4j
public class ExcelFormulaConverter {

    private final LLMChat llmChat;

    public ExcelFormulaConverter(LLMChat llmChat) {
        this.llmChat = llmChat;
    }

    /**
     * Convert all formulas in the spreadsheet graph to executable code.
     *
     * @param graph    the spreadsheet formula graph
     * @param language "javascript" or "python"
     * @return the generated code as a string
     */
    public ExcelConversionResult convert(SpreadsheetGraph graph, String language) {
        String lang = language != null ? language.toLowerCase() : "javascript";
        if (!lang.equals("javascript") && !lang.equals("python")) {
            throw new IllegalArgumentException("Unsupported target language: " + language
                    + ". Supported: javascript, python");
        }

        String prompt = buildConversionPrompt(graph, lang);
        log.debug("Excel formula conversion prompt ({} chars) for {} formula cells",
                prompt.length(), graph.getFormulaCells().size());

        String systemMessage = buildSystemMessage(lang);

        String generatedCode = llmChat.prompt()
                .system(systemMessage)
                .user(prompt)
                .call()
                .content();

        // Extract code from markdown fences if present
        String cleanCode = extractCodeBlock(generatedCode, lang);

        // Build list of input cell names and output cell names for documentation
        Set<String> inputCells = new LinkedHashSet<>();
        Set<String> outputCells = new LinkedHashSet<>();
        for (CellNode cell : graph.getFormulaCells()) {
            outputCells.add(sanitizeCellRef(cell.getCellReference()));
        }
        for (CellNode cell : graph.getCells().values()) {
            if (!"FORMULA".equals(cell.getCellType()) && !"BLANK".equals(cell.getCellType())) {
                inputCells.add(sanitizeCellRef(cell.getCellReference()));
            }
        }

        return ExcelConversionResult.builder()
                .code(cleanCode)
                .language(lang)
                .workbookName(graph.getWorkbookName())
                .inputCells(new ArrayList<>(inputCells))
                .outputCells(new ArrayList<>(outputCells))
                .formulaCount(graph.getFormulaCells().size())
                .dependencyCount(graph.getDependencies().size())
                .build();
    }

    private String buildSystemMessage(String language) {
        String langName = language.equals("javascript") ? "JavaScript" : "Python";
        return "You are a precise code generator that converts Excel spreadsheet formulas to " + langName + " code. "
                + "You MUST output ONLY the raw code — no markdown fences, no explanations, no comments outside the code. "
                + "The code must be a single self-contained function that:\n"
                + "1. Accepts a single object/dict parameter called 'cells' containing input cell values keyed by sanitized cell references\n"
                + "2. Computes all formula cells in dependency order\n"
                + "3. Returns an object/dict with all computed cell values keyed by sanitized cell references\n"
                + "Cell references are sanitized: 'Sheet1!A1' becomes 'Sheet1_A1' (replace ! and : with _).\n"
                + "Handle Excel functions: SUM, AVERAGE, COUNT, IF, VLOOKUP, INDEX/MATCH, MIN, MAX, ROUND, CONCATENATE, etc.\n"
                + "For range operations (e.g., SUM(A1:A10)), iterate over the individual cells in the range.\n"
                + "If a cell value is not provided in the input, use the default display value from the spreadsheet.";
    }

    private String buildConversionPrompt(SpreadsheetGraph graph, String language) {
        StringBuilder sb = new StringBuilder();
        sb.append("Convert the following Excel spreadsheet formulas to a ")
                .append(language.equals("javascript") ? "JavaScript" : "Python")
                .append(" function.\n\n");

        // Section 1: Input cells (non-formula cells with values)
        sb.append("## Input Cells (non-formula cells with values)\n");
        for (CellNode cell : graph.getCells().values()) {
            if (!"FORMULA".equals(cell.getCellType()) && !"BLANK".equals(cell.getCellType())) {
                sb.append("  ").append(sanitizeCellRef(cell.getCellReference()))
                        .append(" = ").append(formatCellValue(cell))
                        .append("  // type: ").append(cell.getCellType())
                        .append("\n");
            }
        }

        // Section 2: Named ranges
        if (!graph.getNamedRanges().isEmpty()) {
            sb.append("\n## Named Ranges\n");
            for (Map.Entry<String, String> nr : graph.getNamedRanges().entrySet()) {
                sb.append("  ").append(nr.getKey()).append(" -> ").append(nr.getValue()).append("\n");
            }
        }

        // Section 3: Formula cells in topological order
        sb.append("\n## Formula Cells (compute these in order)\n");
        List<CellNode> ordered = topologicalSort(graph);
        for (CellNode cell : ordered) {
            sb.append("  ").append(sanitizeCellRef(cell.getCellReference()))
                    .append(" = ").append(cell.getFormula());
            if (cell.getDisplayValue() != null && !cell.getDisplayValue().isEmpty()) {
                sb.append("  // expected: ").append(cell.getDisplayValue());
            }
            sb.append("\n");

            // Show dependencies
            List<FormulaDependency> deps = graph.getDependencies().stream()
                    .filter(d -> d.getFormulaCell().equals(cell.getCellReference()))
                    .collect(Collectors.toList());
            if (!deps.isEmpty()) {
                sb.append("    depends on: ");
                sb.append(deps.stream()
                        .map(d -> sanitizeCellRef(d.getReferencedCell()))
                        .distinct()
                        .collect(Collectors.joining(", ")));
                sb.append("\n");
            }
        }

        // Section 4: Function signature
        sb.append("\n## Required function signature\n");
        if (language.equals("javascript")) {
            sb.append("function computeSpreadsheet(cells) {\n");
            sb.append("  // cells is an object: { Sheet1_A1: value, Sheet1_B1: value, ... }\n");
            sb.append("  // Return: { Sheet1_C1: computed, Sheet1_D1: computed, ... }\n");
            sb.append("}\n");
            sb.append("// Call it and return the result:\n");
            sb.append("computeSpreadsheet(cells)\n");
        } else {
            sb.append("def compute_spreadsheet(cells):\n");
            sb.append("    # cells is a dict: { 'Sheet1_A1': value, 'Sheet1_B1': value, ... }\n");
            sb.append("    # Return: { 'Sheet1_C1': computed, 'Sheet1_D1': computed, ... }\n");
            sb.append("    pass\n");
            sb.append("# Call it and assign to _output:\n");
            sb.append("_output = compute_spreadsheet(cells)\n");
        }

        return sb.toString();
    }

    /**
     * Topologically sort formula cells so that each cell is computed after its dependencies.
     */
    private List<CellNode> topologicalSort(SpreadsheetGraph graph) {
        List<CellNode> formulaCells = graph.getFormulaCells();
        Map<String, Set<String>> deps = new HashMap<>();

        for (FormulaDependency dep : graph.getDependencies()) {
            deps.computeIfAbsent(dep.getFormulaCell(), k -> new HashSet<>())
                    .add(dep.getReferencedCell());
        }

        // Kahn's algorithm
        Map<String, Integer> inDegree = new HashMap<>();
        Set<String> formulaRefs = formulaCells.stream()
                .map(CellNode::getCellReference)
                .collect(Collectors.toSet());

        for (CellNode cell : formulaCells) {
            String ref = cell.getCellReference();
            int degree = 0;
            Set<String> cellDeps = deps.getOrDefault(ref, Set.of());
            for (String dep : cellDeps) {
                if (formulaRefs.contains(dep)) {
                    degree++;
                }
            }
            inDegree.put(ref, degree);
        }

        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<String> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            sorted.add(current);
            // Find formula cells that depend on current
            for (CellNode cell : formulaCells) {
                String ref = cell.getCellReference();
                Set<String> cellDeps = deps.getOrDefault(ref, Set.of());
                if (cellDeps.contains(current) && inDegree.containsKey(ref)) {
                    int newDegree = inDegree.get(ref) - 1;
                    inDegree.put(ref, newDegree);
                    if (newDegree == 0) {
                        queue.add(ref);
                    }
                }
            }
        }

        // Build result from sorted order, append any remaining (circular deps) at end
        Map<String, CellNode> cellMap = formulaCells.stream()
                .collect(Collectors.toMap(CellNode::getCellReference, c -> c));
        List<CellNode> result = new ArrayList<>();
        for (String ref : sorted) {
            result.add(cellMap.get(ref));
        }
        // Add any cells not in the sorted output (circular dependencies)
        for (CellNode cell : formulaCells) {
            if (!sorted.contains(cell.getCellReference())) {
                result.add(cell);
            }
        }
        return result;
    }

    static String sanitizeCellRef(String cellRef) {
        return cellRef.replace("!", "_").replace(":", "_");
    }

    private String formatCellValue(CellNode cell) {
        if (cell.getDisplayValue() == null) return "null";
        switch (cell.getCellType()) {
            case "NUMERIC":
            case "DATE":
                return cell.getDisplayValue();
            case "BOOLEAN":
                return cell.getDisplayValue().toLowerCase();
            case "STRING":
            default:
                return "\"" + cell.getDisplayValue().replace("\"", "\\\"") + "\"";
        }
    }

    /**
     * Extract code from markdown fenced code blocks if present.
     */
    static String extractCodeBlock(String response, String language) {
        if (response == null) return "";
        String trimmed = response.trim();

        // Try to find fenced code block
        String fenceStart = "```" + (language.equals("javascript") ? "javascript" : "python");
        String fenceStartAlt = "```" + (language.equals("javascript") ? "js" : "py");
        String fenceGeneric = "```";

        for (String start : List.of(fenceStart, fenceStartAlt, fenceGeneric)) {
            int startIdx = trimmed.indexOf(start);
            if (startIdx >= 0) {
                int codeStart = trimmed.indexOf('\n', startIdx);
                if (codeStart >= 0) {
                    int endIdx = trimmed.indexOf("```", codeStart);
                    if (endIdx >= 0) {
                        return trimmed.substring(codeStart + 1, endIdx).trim();
                    }
                    return trimmed.substring(codeStart + 1).trim();
                }
            }
        }

        return trimmed;
    }
}
