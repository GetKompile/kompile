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

package ai.kompile.loader.email.inbox;

import ai.kompile.loader.excel.graph.CellNode;
import ai.kompile.loader.excel.graph.SpreadsheetGraph;

import java.util.*;

/**
 * Maps extracted email values to Excel spreadsheet input cells.
 * Uses field name similarity and type compatibility to suggest
 * which extracted values should populate which cells.
 *
 * <p>The mapping is best-effort and heuristic-based. Each mapping
 * includes a confidence score so callers can filter by threshold
 * or present suggestions for human review.</p>
 */
public class EmailValueCellMapper {

    /**
     * Attempts to map extracted email values to input cells in a spreadsheet.
     * Input cells are non-formula, non-blank cells that serve as data inputs.
     *
     * @param extractedValues values from {@link EmailBodyValueExtractor}
     * @param graph           the target spreadsheet graph
     * @return list of suggested mappings, sorted by confidence descending
     */
    public List<CellMapping> mapValues(List<EmailBodyValueExtractor.ExtractedValue> extractedValues,
                                        SpreadsheetGraph graph) {
        List<CellMapping> mappings = new ArrayList<>();

        // Collect input cells: non-formula, non-blank cells
        List<CellNode> inputCells = new ArrayList<>();
        for (CellNode cell : graph.getCells().values()) {
            if (!"FORMULA".equals(cell.getCellType()) && !"BLANK".equals(cell.getCellType())) {
                inputCells.add(cell);
            }
        }

        for (EmailBodyValueExtractor.ExtractedValue value : extractedValues) {
            // Skip cell references themselves — they're metadata, not values
            if ("CELL_REFERENCE".equals(value.type)) {
                // If the reference points to a cell in the graph, note that
                String ref = value.parsedValue instanceof String s ? s : null;
                if (ref != null) {
                    CellNode target = findCellByRef(graph, ref);
                    if (target != null) {
                        mappings.add(new CellMapping(value, target.getCellReference(),
                                "Direct cell reference in email body", 0.95));
                    }
                }
                continue;
            }

            for (CellNode cell : inputCells) {
                double score = computeMappingScore(value, cell);
                if (score > 0.3) {
                    String reason = buildReason(value, cell, score);
                    mappings.add(new CellMapping(value, cell.getCellReference(), reason, score));
                }
            }
        }

        // Sort by confidence descending, deduplicate (keep highest score per cell)
        mappings.sort(Comparator.comparingDouble((CellMapping m) -> m.confidence).reversed());
        Map<String, CellMapping> bestPerCell = new LinkedHashMap<>();
        for (CellMapping m : mappings) {
            bestPerCell.merge(m.cellReference, m,
                    (existing, candidate) -> candidate.confidence > existing.confidence ? candidate : existing);
        }

        return new ArrayList<>(bestPerCell.values());
    }

    /**
     * Computes a mapping score between an extracted value and a candidate cell.
     */
    private double computeMappingScore(EmailBodyValueExtractor.ExtractedValue value, CellNode cell) {
        double score = 0.0;

        // Type compatibility
        boolean typeMatch = isTypeCompatible(value.type, cell.getCellType());
        if (!typeMatch) return 0.0;
        score += 0.3;

        // Field name to cell label/column header proximity
        if (value.fieldName != null) {
            double nameScore = computeNameSimilarity(value.fieldName, cell);
            score += nameScore * 0.5;
        }

        // Value range plausibility — if cell has a display value, check if extracted value
        // is in a reasonable range
        if (cell.getDisplayValue() != null && value.parsedValue instanceof Number numValue) {
            try {
                double cellValue = Double.parseDouble(cell.getDisplayValue());
                double ratio = cellValue != 0 ? numValue.doubleValue() / cellValue : 0;
                // Values within 10x of each other are plausible replacements
                if (ratio > 0.1 && ratio < 10.0) {
                    score += 0.15;
                }
            } catch (NumberFormatException ignored) { }
        }

        return Math.min(score, 1.0);
    }

    private boolean isTypeCompatible(String valueType, String cellType) {
        return switch (valueType) {
            case "CURRENCY", "NUMERIC" -> "NUMERIC".equals(cellType) || "STRING".equals(cellType);
            case "PERCENTAGE" -> "NUMERIC".equals(cellType);
            case "DATE" -> "STRING".equals(cellType) || "NUMERIC".equals(cellType);
            default -> true;
        };
    }

    private double computeNameSimilarity(String fieldName, CellNode cell) {
        String cellLabel = cell.getCellReference().toLowerCase();
        String namedRange = cell.isNamedRange() ? cell.getNamedRangeName() : null;

        // Exact match with named range
        if (namedRange != null && namedRange.toLowerCase().contains(fieldName)) {
            return 1.0;
        }

        // Check if cell's display value or nearby context suggests the field
        // This is a simplified heuristic — a real implementation would use column headers
        String displayVal = cell.getDisplayValue() != null ? cell.getDisplayValue().toLowerCase() : "";
        if (displayVal.contains(fieldName)) {
            return 0.8;
        }

        return 0.0;
    }

    private CellNode findCellByRef(SpreadsheetGraph graph, String ref) {
        // Try exact match first
        CellNode node = graph.getCells().get(ref);
        if (node != null) return node;

        // Try with sheet prefix variations
        for (Map.Entry<String, CellNode> entry : graph.getCells().entrySet()) {
            if (entry.getKey().endsWith("!" + ref) || entry.getKey().equals(ref)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String buildReason(EmailBodyValueExtractor.ExtractedValue value, CellNode cell, double score) {
        StringBuilder sb = new StringBuilder();
        sb.append(value.type.toLowerCase()).append(" value '").append(value.rawText).append("'");
        if (value.fieldName != null) {
            sb.append(" (field: ").append(value.fieldName).append(")");
        }
        sb.append(" → cell ").append(cell.getCellReference());
        if (cell.getDisplayValue() != null) {
            sb.append(" (current: ").append(cell.getDisplayValue()).append(")");
        }
        return sb.toString();
    }

    /**
     * A suggested mapping between an extracted email value and a spreadsheet cell.
     */
    public static class CellMapping {
        public final EmailBodyValueExtractor.ExtractedValue extractedValue;
        public final String cellReference;
        public final String reason;
        public final double confidence;

        public CellMapping(EmailBodyValueExtractor.ExtractedValue extractedValue,
                           String cellReference, String reason, double confidence) {
            this.extractedValue = extractedValue;
            this.cellReference = cellReference;
            this.reason = reason;
            this.confidence = confidence;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("cellReference", cellReference);
            map.put("confidence", confidence);
            map.put("reason", reason);
            map.put("extractedValue", extractedValue.toMap());
            return map;
        }
    }
}
