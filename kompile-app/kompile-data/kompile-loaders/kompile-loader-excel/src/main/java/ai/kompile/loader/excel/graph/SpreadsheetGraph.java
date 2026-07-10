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

package ai.kompile.loader.excel.graph;

import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import lombok.Data;

import java.util.*;
import java.util.stream.Collectors;

import static ai.kompile.core.graphrag.GraphConstants.*;

/**
 * Represents the complete formula dependency graph for a spreadsheet.
 * Contains all cell nodes and their dependency edges, and provides
 * conversion to the core {@link Graph} model for integration with
 * the knowledge graph system.
 */
@Data
public class SpreadsheetGraph {

    private String workbookName;
    private final Map<String, CellNode> cells = new LinkedHashMap<>();
    private final List<FormulaDependency> dependencies = new ArrayList<>();
    private final Map<String, String> namedRanges = new LinkedHashMap<>();
    private final List<StructuredTable> structuredTables = new ArrayList<>();
    private final List<DataValidationRule> dataValidations = new ArrayList<>();

    /**
     * Represents an Excel structured table (ListObject/XSSFTable).
     */
    @Data
    public static class StructuredTable {
        private final String name;
        private final String sheetName;
        private final String displayName;
        private final int startRow;
        private final int endRow;
        private final int startCol;
        private final int endCol;
        private final List<String> columnNames;
    }

    /**
     * Represents a data validation rule applied to a cell range.
     */
    @Data
    public static class DataValidationRule {
        private final String sheetName;
        private final String cellRange;
        private final String validationType;
        private final String formula1;
        private final String formula2;
        private final String errorTitle;
        private final String errorMessage;
        private final String promptTitle;
        private final String promptMessage;
    }

    /**
     * Add a cell node to the graph.
     */
    public void addCell(CellNode cell) {
        cells.put(cell.getCellReference(), cell);
    }

    /**
     * Add a formula dependency edge.
     */
    public void addDependency(FormulaDependency dep) {
        dependencies.add(dep);
    }

    /**
     * Register a named range mapping.
     */
    public void addNamedRange(String name, String reference) {
        namedRanges.put(name, reference);
    }

    /**
     * Register a structured table (ListObject/XSSFTable).
     */
    public void addStructuredTable(StructuredTable table) {
        structuredTables.add(table);
    }

    /**
     * Register a data validation rule.
     */
    public void addDataValidation(DataValidationRule rule) {
        dataValidations.add(rule);
    }

    /**
     * Get all formula cells (cells that contain formulas).
     */
    public List<CellNode> getFormulaCells() {
        return cells.values().stream()
                .filter(c -> "FORMULA".equals(c.getCellType()))
                .collect(Collectors.toList());
    }

    /**
     * Get all cells that the given formula cell depends on.
     */
    public List<CellNode> getDependenciesOf(String cellReference) {
        return dependencies.stream()
                .filter(d -> d.getFormulaCell().equals(cellReference))
                .map(d -> cells.get(d.getReferencedCell()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Get all formula cells that depend on the given cell (reverse dependencies).
     */
    public List<CellNode> getDependents(String cellReference) {
        return dependencies.stream()
                .filter(d -> d.getReferencedCell().equals(cellReference))
                .map(d -> cells.get(d.getFormulaCell()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Get cross-sheet dependencies only.
     */
    public List<FormulaDependency> getCrossSheetDependencies() {
        return dependencies.stream()
                .filter(FormulaDependency::isCrossSheet)
                .collect(Collectors.toList());
    }

    /**
     * Get the set of distinct sheet names referenced in the graph.
     */
    public Set<String> getSheetNames() {
        return cells.values().stream()
                .map(CellNode::getSheetName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Convert this spreadsheet graph to the core {@link Graph} model
     * for integration with the knowledge graph system.
     *
     * <p>Mapping:
     * <ul>
     *   <li>Each cell becomes an {@link Entity} with type CELL, FORMULA_CELL, or NAMED_RANGE</li>
     *   <li>Each sheet becomes a composite {@link Entity} with type SHEET</li>
     *   <li>Each dependency becomes a {@link Relationship} with type DEPENDS_ON, RANGE_INPUT, etc.</li>
     *   <li>Sheet containment becomes CONTAINS relationships</li>
     * </ul>
     */
    public Graph toGraph() {
        Graph graph = new Graph();
        // Namespace prefix scopes all IDs to this workbook, preventing collisions
        // when multiple workbooks are ingested into the same knowledge graph.
        String wbKey = workbookName != null ? workbookName : UUID.randomUUID().toString();
        String ns = "wb:" + wbKey + "/";
        graph.setId("spreadsheet-" + wbKey);
        graph.setEntities(new ArrayList<>());
        graph.setRelationships(new ArrayList<>());

        // Pre-group cells by sheet so we can build per-sheet markdown grids before
        // creating sheet entities (sheet entities must carry fullTableContent so the
        // TABLE node that GraphPersistenceHelper promotes them to actually has a grid).
        Map<String, List<CellNode>> cellsBySheet = new LinkedHashMap<>();
        for (CellNode cell : cells.values()) {
            cellsBySheet.computeIfAbsent(cell.getSheetName(), k -> new ArrayList<>()).add(cell);
        }
        Map<String, String> sheetMarkdowns = new LinkedHashMap<>();
        for (Map.Entry<String, List<CellNode>> entry : cellsBySheet.entrySet()) {
            sheetMarkdowns.put(entry.getKey(), buildSheetMarkdown(entry.getValue()));
        }

        // Create sheet entities as composite containers
        Map<String, Entity> sheetEntities = new LinkedHashMap<>();
        for (String sheetName : getSheetNames()) {
            String sheetMarkdown = sheetMarkdowns.getOrDefault(sheetName, "");
            if (sheetMarkdown.isEmpty()) {
                // No non-empty cells → skip this sheet; a content-less TABLE node is noise.
                continue;
            }
            Entity sheetEntity = new Entity();
            sheetEntity.setId(ns + "sheet:" + sheetName);
            sheetEntity.setTitle(sheetName);
            sheetEntity.setType(ENTITY_SHEET);
            sheetEntity.setDescription("Worksheet: " + sheetName);
            sheetEntity.setConfidence(1.0);
            Map<String, Object> sheetMeta = new LinkedHashMap<>();
            sheetMeta.put("isComposite", true);
            sheetMeta.put("workbook", workbookName != null ? workbookName : "unknown");
            sheetMeta.put(PROP_ENTITY_SOURCE, SOURCE_EXCEL_LOADER);
            // Attach the reconstructed markdown grid so the TABLE node the persistence
            // layer promotes this SHEET to actually renders in the index-browser Tables tab.
            sheetMeta.put("fullTableContent", sheetMarkdown);
            List<CellNode> sheetCellList = cellsBySheet.getOrDefault(sheetName, List.of());
            int maxRow = sheetCellList.stream()
                    .filter(c -> c.getDisplayValue() != null && !c.getDisplayValue().isBlank())
                    .mapToInt(CellNode::getRow).max().orElse(0);
            int maxColIdx = sheetCellList.stream()
                    .filter(c -> c.getDisplayValue() != null && !c.getDisplayValue().isBlank())
                    .mapToInt(c -> columnLetterToIndex(c.getColumn()) + 1).max().orElse(0);
            sheetMeta.put("rowCount", Math.min(maxRow, MAX_SHEET_TABLE_ROWS));
            sheetMeta.put("columnCount", Math.min(maxColIdx, MAX_SHEET_TABLE_COLS));
            sheetEntity.setMetadata(sheetMeta);
            graph.getEntities().add(sheetEntity);
            sheetEntities.put(sheetName, sheetEntity);
        }

        // Infer generic row/column semantic context before creating cell entities. This uses
        // spreadsheet structure only; domain ontologies may refine the labels later.
        Map<String, CellSemanticContext> semanticContexts = new LinkedHashMap<>();
        Map<String, NavigableMap<Integer, String>> loneTextRowsBySheet = new LinkedHashMap<>();
        for (Map.Entry<String, List<CellNode>> entry : cellsBySheet.entrySet()) {
            NavigableMap<Integer, String> loneTextRows = loneTextRows(entry.getValue());
            loneTextRowsBySheet.put(entry.getKey(), loneTextRows);
            for (CellNode cell : entry.getValue()) {
                semanticContexts.put(cell.getCellReference(),
                        inferSemanticContext(cell, entry.getValue(), loneTextRows));
            }
        }

        // Create cell entities
        for (CellNode cell : cells.values()) {
            // Skip genuinely empty cells — no value, no formula, not a named range. Empty grid cells
            // are spreadsheet sparsity (noise), not knowledge; don't materialise them as graph nodes.
            String cellVal = cell.getDisplayValue();
            boolean cellHasValue = cellVal != null && !cellVal.isBlank();
            boolean cellHasFormula = cell.getFormula() != null && !cell.getFormula().isBlank();
            if (!cellHasValue && !cellHasFormula && !cell.isNamedRange()) {
                continue;
            }
            Entity cellEntity = new Entity();
            cellEntity.setId(ns + "cell:" + cell.getCellReference());
            // Title by the cell's VALUE (or named-range name), not the opaque coordinate — so process
            // maps, the visualizer, and LLM discovery show "Net revenue" instead of "AU!E10"/"CELL".
            // The coordinate stays on the id, description, and cell_reference metadata.
            String dispVal = cell.getDisplayValue();
            CellSemanticContext semantic = semanticContexts.get(cell.getCellReference());
            String cellLabel;
            if (cell.isNamedRange() && cell.getNamedRangeName() != null && !cell.getNamedRangeName().isBlank()) {
                cellLabel = cell.getNamedRangeName();
            } else if (!"STRING".equals(cell.getCellType())
                    && semantic != null && !semantic.label().isBlank()) {
                cellLabel = semantic.label();
            } else if ("STRING".equals(cell.getCellType())
                    && dispVal != null && !dispVal.isBlank()) {
                cellLabel = dispVal.length() > 60 ? dispVal.substring(0, 57) + "…" : dispVal;
            } else {
                cellLabel = cell.getCellReference();
            }
            cellEntity.setTitle(cellLabel);

            if (cell.isNamedRange()) {
                cellEntity.setType(ENTITY_NAMED_RANGE);
                cellEntity.setDescription(String.format("Named range '%s' at %s = %s",
                        cell.getNamedRangeName(), cell.getCellReference(), cell.getDisplayValue()));
            } else if ("FORMULA".equals(cell.getCellType())) {
                cellEntity.setType(ENTITY_FORMULA_CELL);
                cellEntity.setDescription(String.format("Formula cell %s: =%s (value: %s)",
                        cell.getCellReference(), cell.getFormula(), cell.getDisplayValue()));
            } else {
                cellEntity.setType(ENTITY_CELL);
                cellEntity.setDescription(String.format("Cell %s: %s",
                        cell.getCellReference(), cell.getDisplayValue()));
            }

            cellEntity.setConfidence(1.0);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("parentEntityId", ns + "sheet:" + cell.getSheetName());
            meta.put(META_SHEET_NAME, cell.getSheetName());
            meta.put("column", cell.getColumn());
            meta.put("row", cell.getRow());
            meta.put("cellType", cell.getCellType());
            meta.put(PROP_ENTITY_SOURCE, SOURCE_EXCEL_LOADER);
            if (cell.getFormula() != null) {
                meta.put("formula", cell.getFormula());
            }
            if (cell.getDisplayValue() != null) {
                meta.put("displayValue", cell.getDisplayValue());
            }
            if (cell.getRawValue() != null) {
                meta.put("rawValue", cell.getRawValue());
            }
            if (cell.getEvaluatedCellType() != null) {
                meta.put("evaluatedCellType", cell.getEvaluatedCellType());
            }
            if (cell.getEvaluatedNumericValue() != null) {
                meta.put("evaluatedNumericValue", cell.getEvaluatedNumericValue());
            }
            if (cell.getNumberFormat() != null) {
                meta.put("numberFormat", cell.getNumberFormat());
            }
            if (cell.getDataFormatIndex() != null) {
                meta.put("dataFormatIndex", cell.getDataFormatIndex());
            }
            if (cell.getFormulaError() != null) {
                meta.put("formulaError", cell.getFormulaError());
            }
            if (semantic != null) {
                if (semantic.rowLabel() != null) {
                    meta.put("rowLabel", semantic.rowLabel());
                }
                if (!semantic.rowLabels().isEmpty()) {
                    meta.put("rowLabels", semantic.rowLabels());
                }
                if (!semantic.rowDimensions().isEmpty()) {
                    meta.put("rowDimensions", semantic.rowDimensions());
                }
                if (semantic.columnLabel() != null) {
                    meta.put("columnLabel", semantic.columnLabel());
                }
                if (semantic.sectionLabel() != null) {
                    meta.put("sectionLabel", semantic.sectionLabel());
                }
                if (semantic.sheetTitle() != null) {
                    meta.put("sheetTitle", semantic.sheetTitle());
                }
                if (!semantic.label().isBlank()) {
                    meta.put("semanticLabel", semantic.label());
                }
                Map<String, String> dimensions = new LinkedHashMap<>();
                dimensions.put("sheet", cell.getSheetName());
                if (workbookName != null) {
                    dimensions.put("workbook", workbookName);
                }
                if (semantic.rowLabel() != null) {
                    dimensions.put("row", semantic.rowLabel());
                }
                if (semantic.columnLabel() != null) {
                    dimensions.put("column", semantic.columnLabel());
                }
                for (Map.Entry<String, String> rowDimension
                        : semantic.rowDimensions().entrySet()) {
                    String key = dimensionKey(rowDimension.getKey());
                    if (key != null) {
                        dimensions.putIfAbsent(key, rowDimension.getValue());
                    }
                }
                meta.put("dimensions", dimensions);
            }
            if (cell.isNamedRange()) {
                meta.put("namedRangeName", cell.getNamedRangeName());
            }
            if (cell.getComment() != null) {
                meta.put("comment", cell.getComment());
            }
            if (cell.getCommentAuthor() != null) {
                meta.put("commentAuthor", cell.getCommentAuthor());
            }
            cellEntity.setMetadata(meta);

            graph.getEntities().add(cellEntity);

            // Create CELL_COMMENT entity if cell has a comment
            if (cell.getComment() != null && !cell.getComment().isBlank()) {
                Entity commentEntity = new Entity();
                commentEntity.setId(ns + "comment:" + cell.getCellReference());
                commentEntity.setTitle("Comment on " + cell.getCellReference());
                commentEntity.setType(ENTITY_CELL_COMMENT);
                String commentDesc = cell.getComment().length() > 200
                        ? cell.getComment().substring(0, 197) + "..."
                        : cell.getComment();
                commentEntity.setDescription(commentDesc);
                commentEntity.setConfidence(1.0);
                Map<String, Object> commentMeta = new LinkedHashMap<>();
                commentMeta.put("cellReference", cell.getCellReference());
                commentMeta.put("text", cell.getComment());
                if (cell.getCommentAuthor() != null) {
                    commentMeta.put("author", cell.getCommentAuthor());
                }
                commentMeta.put(PROP_ENTITY_SOURCE, SOURCE_EXCEL_LOADER);
                commentEntity.setMetadata(commentMeta);
                graph.getEntities().add(commentEntity);

                Relationship commentRel = new Relationship();
                commentRel.setSource(ns + "cell:" + cell.getCellReference());
                commentRel.setTarget(ns + "comment:" + cell.getCellReference());
                commentRel.setType(REL_HAS_COMMENT);
                commentRel.setDescription(String.format("Cell %s has comment: %s",
                        cell.getCellReference(), commentDesc));
                commentRel.setWeight(1.0);
                commentRel.setConfidence(1.0);
                commentRel.setMetadata(Map.of("provenance", PROVENANCE_EXTRACTED));
                graph.getRelationships().add(commentRel);
            }

            // Create HYPERLINK entity if cell has a hyperlink
            if (cell.getHyperlink() != null && !cell.getHyperlink().isBlank()) {
                Entity hyperlinkEntity = new Entity();
                hyperlinkEntity.setId(ns + "hyperlink:" + cell.getCellReference());
                hyperlinkEntity.setTitle(cell.getHyperlink());
                hyperlinkEntity.setType(ENTITY_EXTERNAL_RESOURCE);
                hyperlinkEntity.setDescription("Hyperlink in cell " + cell.getCellReference()
                        + ": " + cell.getHyperlink());
                hyperlinkEntity.setConfidence(0.9);
                Map<String, Object> hlMeta = new LinkedHashMap<>();
                hlMeta.put("url", cell.getHyperlink());
                hlMeta.put("cellReference", cell.getCellReference());
                if (cell.getHyperlinkLabel() != null) {
                    hlMeta.put("label", cell.getHyperlinkLabel());
                }
                hlMeta.put(PROP_ENTITY_SOURCE, SOURCE_EXCEL_LOADER);
                hyperlinkEntity.setMetadata(hlMeta);
                graph.getEntities().add(hyperlinkEntity);

                Relationship hlRel = new Relationship();
                hlRel.setSource(ns + "cell:" + cell.getCellReference());
                hlRel.setTarget(ns + "hyperlink:" + cell.getCellReference());
                hlRel.setType(REL_HAS_HYPERLINK);
                hlRel.setDescription(String.format("Cell %s links to %s",
                        cell.getCellReference(), cell.getHyperlink()));
                hlRel.setWeight(0.9);
                hlRel.setConfidence(0.9);
                hlRel.setMetadata(Map.of("provenance", PROVENANCE_EXTRACTED));
                graph.getRelationships().add(hlRel);
            }

            // Create CONTAINS relationship from sheet to cell
            Relationship containsRel = new Relationship();
            containsRel.setSource(ns + "sheet:" + cell.getSheetName());
            containsRel.setTarget(ns + "cell:" + cell.getCellReference());
            containsRel.setType(REL_CONTAINS);
            containsRel.setDescription(String.format("Sheet '%s' contains cell %s",
                    cell.getSheetName(), cell.getCellReference()));
            containsRel.setWeight(1.0);
            containsRel.setConfidence(1.0);
            containsRel.setMetadata(Map.of("provenance", PROVENANCE_EXTRACTED));
            graph.getRelationships().add(containsRel);
        }

        // Create dependency relationships
        for (FormulaDependency dep : dependencies) {
            Relationship rel = new Relationship();
            rel.setSource(ns + "cell:" + dep.getFormulaCell());
            rel.setTarget(ns + "cell:" + dep.getReferencedCell());

            switch (dep.getDependencyType()) {
                case CELL_REFERENCE:
                    rel.setType(REL_DEPENDS_ON);
                    rel.setDescription(String.format("%s references %s in formula =%s",
                            dep.getFormulaCell(), dep.getReferencedCell(), dep.getFormula()));
                    break;
                case RANGE_REFERENCE:
                    rel.setType(REL_RANGE_INPUT);
                    rel.setDescription(String.format("%s uses range %s in formula =%s",
                            dep.getFormulaCell(), dep.getRangeReference(), dep.getFormula()));
                    break;
                case CROSS_SHEET_REFERENCE:
                    rel.setType(REL_CROSS_SHEET_DEPENDS_ON);
                    rel.setDescription(String.format("%s references %s across sheets in formula =%s",
                            dep.getFormulaCell(), dep.getReferencedCell(), dep.getFormula()));
                    break;
                case NAMED_RANGE_REFERENCE:
                    rel.setType(REL_NAMED_RANGE_INPUT);
                    rel.setDescription(String.format("%s uses named range referencing %s in formula =%s",
                            dep.getFormulaCell(), dep.getReferencedCell(), dep.getFormula()));
                    break;
            }

            rel.setWeight(dep.isCrossSheet() ? 0.8 : 1.0);
            rel.setConfidence(1.0);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("provenance", PROVENANCE_EXTRACTED);
            meta.put("formula", dep.getFormula());
            meta.put("crossSheet", dep.isCrossSheet());
            meta.put("dependencyType", dep.getDependencyType().name());
            if (dep.getRangeReference() != null) {
                meta.put("rangeReference", dep.getRangeReference());
            }
            rel.setMetadata(meta);

            graph.getRelationships().add(rel);
        }

        // Create top-level NAMED_RANGE entities with DEFINES relationships
        for (Map.Entry<String, String> nr : namedRanges.entrySet()) {
            String rangeName = nr.getKey();
            String rangeRef = nr.getValue();
            String rangeEntityId = ns + "namedrange:" + rangeName;

            Entity rangeEntity = new Entity();
            rangeEntity.setId(rangeEntityId);
            rangeEntity.setTitle(rangeName);
            rangeEntity.setType(ENTITY_NAMED_RANGE);
            rangeEntity.setDescription(String.format("Named range '%s' → %s", rangeName, rangeRef));
            rangeEntity.setConfidence(1.0);
            Map<String, Object> rangeMeta = new LinkedHashMap<>();
            rangeMeta.put("rangeName", rangeName);
            rangeMeta.put("refersTo", rangeRef);
            rangeMeta.put("isComposite", true);
            rangeMeta.put(PROP_ENTITY_SOURCE, SOURCE_EXCEL_LOADER);
            rangeEntity.setMetadata(rangeMeta);
            graph.getEntities().add(rangeEntity);

            // Link named range to target cell(s)
            CellNode target = cells.get(rangeRef);
            if (target != null) {
                Relationship definesRel = new Relationship();
                definesRel.setSource(rangeEntityId);
                definesRel.setTarget(ns + "cell:" + target.getCellReference());
                definesRel.setType(REL_DEFINES);
                definesRel.setDescription(String.format("Named range '%s' defines cell %s",
                        rangeName, target.getCellReference()));
                definesRel.setWeight(1.0);
                definesRel.setConfidence(1.0);
                definesRel.setMetadata(Map.of("provenance", PROVENANCE_EXTRACTED));
                graph.getRelationships().add(definesRel);
            }

            // Link named range to its containing sheet if we can parse the sheet name
            String sheetName = parseSheetFromRef(rangeRef);
            if (sheetName != null && sheetEntities.containsKey(sheetName)) {
                Relationship containsRangeRel = new Relationship();
                containsRangeRel.setSource(ns + "sheet:" + sheetName);
                containsRangeRel.setTarget(rangeEntityId);
                containsRangeRel.setType(REL_CONTAINS);
                containsRangeRel.setDescription(String.format("Sheet '%s' contains named range '%s'",
                        sheetName, rangeName));
                containsRangeRel.setWeight(1.0);
                containsRangeRel.setConfidence(1.0);
                containsRangeRel.setMetadata(Map.of("provenance", PROVENANCE_EXTRACTED));
                graph.getRelationships().add(containsRangeRel);
            }
        }

        // Create STRUCTURED_TABLE entities for Excel ListObjects/XSSFTables
        for (StructuredTable table : structuredTables) {
            String tableEntityId = ns + "table:" + table.getSheetName() + ":" + table.getName();

            Entity tableEntity = new Entity();
            tableEntity.setId(tableEntityId);
            tableEntity.setTitle(table.getDisplayName() != null ? table.getDisplayName() : table.getName());
            tableEntity.setType(ENTITY_TABLE);
            tableEntity.setDescription(String.format("Structured table '%s' on sheet '%s' (%d columns)",
                    table.getName(), table.getSheetName(),
                    table.getColumnNames() != null ? table.getColumnNames().size() : 0));
            tableEntity.setConfidence(1.0);

            Map<String, Object> tableMeta = new LinkedHashMap<>();
            tableMeta.put("tableName", table.getName());
            tableMeta.put("isComposite", true);
            tableMeta.put(META_SHEET_NAME, table.getSheetName());
            tableMeta.put("startRow", table.getStartRow());
            tableMeta.put("endRow", table.getEndRow());
            tableMeta.put("startCol", table.getStartCol());
            tableMeta.put("endCol", table.getEndCol());
            if (table.getColumnNames() != null && !table.getColumnNames().isEmpty()) {
                tableMeta.put(PROP_HEADERS, String.join(", ", table.getColumnNames()));
                tableMeta.put(PROP_COLUMN_COUNT, table.getColumnNames().size());
            }
            tableMeta.put(PROP_ENTITY_SOURCE, SOURCE_EXCEL_LOADER);
            tableEntity.setMetadata(tableMeta);
            graph.getEntities().add(tableEntity);

            // Link table to its containing sheet
            if (sheetEntities.containsKey(table.getSheetName())) {
                Relationship containsTblRel = new Relationship();
                containsTblRel.setSource(ns + "sheet:" + table.getSheetName());
                containsTblRel.setTarget(tableEntityId);
                containsTblRel.setType(REL_CONTAINS);
                containsTblRel.setDescription(String.format("Sheet '%s' contains table '%s'",
                        table.getSheetName(), table.getName()));
                containsTblRel.setWeight(1.0);
                containsTblRel.setConfidence(1.0);
                containsTblRel.setMetadata(Map.of("provenance", PROVENANCE_EXTRACTED));
                graph.getRelationships().add(containsTblRel);
            }
        }

        // Create DATA_VALIDATION entities for Excel validation rules
        for (int dvIdx = 0; dvIdx < dataValidations.size(); dvIdx++) {
            DataValidationRule dv = dataValidations.get(dvIdx);
            String dvEntityId = ns + "dv:" + dv.getSheetName() + ":" + dvIdx;

            Entity dvEntity = new Entity();
            dvEntity.setId(dvEntityId);
            dvEntity.setTitle(dv.getValidationType() + " validation on " + dv.getCellRange());
            dvEntity.setType(ENTITY_DATA_VALIDATION);
            dvEntity.setDescription(String.format("Data validation (%s) on %s!%s",
                    dv.getValidationType(), dv.getSheetName(), dv.getCellRange()));
            dvEntity.setConfidence(1.0);

            Map<String, Object> dvMeta = new LinkedHashMap<>();
            dvMeta.put("validationType", dv.getValidationType());
            dvMeta.put("cellRange", dv.getCellRange());
            dvMeta.put(META_SHEET_NAME, dv.getSheetName());
            if (dv.getFormula1() != null) dvMeta.put("formula1", dv.getFormula1());
            if (dv.getFormula2() != null) dvMeta.put("formula2", dv.getFormula2());
            if (dv.getErrorTitle() != null) dvMeta.put("errorTitle", dv.getErrorTitle());
            if (dv.getErrorMessage() != null) dvMeta.put("errorMessage", dv.getErrorMessage());
            if (dv.getPromptTitle() != null) dvMeta.put("promptTitle", dv.getPromptTitle());
            if (dv.getPromptMessage() != null) dvMeta.put("promptMessage", dv.getPromptMessage());
            dvMeta.put(PROP_ENTITY_SOURCE, SOURCE_EXCEL_LOADER);
            dvEntity.setMetadata(dvMeta);
            graph.getEntities().add(dvEntity);

            // Link validation to its containing sheet
            if (sheetEntities.containsKey(dv.getSheetName())) {
                Relationship dvRel = new Relationship();
                dvRel.setSource(ns + "sheet:" + dv.getSheetName());
                dvRel.setTarget(dvEntityId);
                dvRel.setType(REL_HAS_DATA_VALIDATION);
                dvRel.setDescription(String.format("Sheet '%s' has %s validation on %s",
                        dv.getSheetName(), dv.getValidationType(), dv.getCellRange()));
                dvRel.setWeight(0.9);
                dvRel.setConfidence(1.0);
                dvRel.setMetadata(Map.of("provenance", PROVENANCE_EXTRACTED));
                graph.getRelationships().add(dvRel);
            }
        }

        // Project plain grids into inferred TABLE entities with typed, keyed member entities.
        InferredTableProjector.project(
                ns, workbookName, cellsBySheet, loneTextRowsBySheet, sheetEntities, graph);

        // Create CROSS_SHEET_LINK relationships between sheets
        Set<String> linkedSheetPairs = new HashSet<>();
        for (FormulaDependency dep : getCrossSheetDependencies()) {
            CellNode formulaNode = cells.get(dep.getFormulaCell());
            CellNode refNode = cells.get(dep.getReferencedCell());
            if (formulaNode != null && refNode != null) {
                String pairKey = formulaNode.getSheetName() + "->" + refNode.getSheetName();
                if (linkedSheetPairs.add(pairKey)) {
                    Relationship sheetLink = new Relationship();
                    sheetLink.setSource(ns + "sheet:" + formulaNode.getSheetName());
                    sheetLink.setTarget(ns + "sheet:" + refNode.getSheetName());
                    sheetLink.setType(REL_CROSS_SHEET_LINK);
                    sheetLink.setDescription(String.format("Sheet '%s' has formulas referencing sheet '%s'",
                            formulaNode.getSheetName(), refNode.getSheetName()));
                    sheetLink.setWeight(0.9);
                    sheetLink.setConfidence(1.0);
                    sheetLink.setMetadata(Map.of("provenance", PROVENANCE_EXTRACTED));
                    graph.getRelationships().add(sheetLink);
                }
            }
        }

        return graph;
    }

    // ─── Sheet semantic-context helpers ────────────────────────────────────────

    /**
     * Rows whose only populated cell is a text label. These act as sheet titles (topmost occupied
     * row) or as section headers for the data rows beneath them ("Net revenue" above entity rows).
     */
    private static NavigableMap<Integer, String> loneTextRows(List<CellNode> sheetCells) {
        Map<Integer, List<CellNode>> byRow = new LinkedHashMap<>();
        for (CellNode cell : sheetCells) {
            byRow.computeIfAbsent(cell.getRow(), ignored -> new ArrayList<>()).add(cell);
        }
        TreeMap<Integer, String> result = new TreeMap<>();
        for (Map.Entry<Integer, List<CellNode>> entry : byRow.entrySet()) {
            if (entry.getValue().size() == 1 && isTextLabel(entry.getValue().get(0))) {
                result.put(entry.getKey(), entry.getValue().get(0).getDisplayValue().trim());
            }
        }
        return result;
    }

    private static CellSemanticContext inferSemanticContext(
            CellNode target, List<CellNode> sheetCells, NavigableMap<Integer, String> loneTextRows) {
        if (target == null || sheetCells == null || "STRING".equals(target.getCellType())) {
            return CellSemanticContext.empty();
        }
        int targetColumn = columnLetterToIndex(target.getColumn());
        List<CellNode> rowHeaders = sheetCells.stream()
                .filter(cell -> cell.getRow() == target.getRow())
                .filter(cell -> columnLetterToIndex(cell.getColumn()) < targetColumn)
                .filter(SpreadsheetGraph::isTextLabel)
                .sorted(Comparator.comparingInt(cell -> columnLetterToIndex(cell.getColumn())))
                .toList();

        Map<Integer, CellNode> nearestHeaders = new LinkedHashMap<>();
        for (CellNode cell : sheetCells) {
            if (!isTextLabel(cell) || cell.getRow() >= target.getRow()) {
                continue;
            }
            int column = columnLetterToIndex(cell.getColumn());
            CellNode current = nearestHeaders.get(column);
            if (current == null || current.getRow() < cell.getRow()) {
                nearestHeaders.put(column, cell);
            }
        }

        CellNode rowHeader = rowHeaders.isEmpty() ? null : rowHeaders.get(rowHeaders.size() - 1);
        CellNode columnHeader = nearestHeaders.get(targetColumn);
        Map<Integer, CellNode> tableHeaders = new LinkedHashMap<>();
        if (columnHeader != null) {
            int headerRow = columnHeader.getRow();
            for (CellNode cell : sheetCells) {
                if (cell.getRow() == headerRow && isTextLabel(cell)) {
                    tableHeaders.put(columnLetterToIndex(cell.getColumn()), cell);
                }
            }
        }
        List<String> rowLabels = rowHeaders.stream()
                .map(CellNode::getDisplayValue)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        Map<String, String> rowDimensions = new LinkedHashMap<>();
        for (CellNode headerValue : rowHeaders) {
            int headerColumn = columnLetterToIndex(headerValue.getColumn());
            CellNode headerName = tableHeaders.get(headerColumn);
            if (headerName == null) {
                headerName = nearestHeaders.get(headerColumn);
            }
            String name = headerName == null
                    ? "column_" + headerValue.getColumn().toLowerCase(Locale.ROOT)
                    : headerName.getDisplayValue().trim();
            rowDimensions.putIfAbsent(name, headerValue.getDisplayValue().trim());
        }

        String rowLabel = rowHeader == null ? null : rowHeader.getDisplayValue().trim();
        String columnLabel = columnHeader == null ? null : columnHeader.getDisplayValue().trim();
        List<String> parts = new ArrayList<>(rowLabels);
        if (columnLabel != null && !columnLabel.isBlank()
                && parts.stream().noneMatch(columnLabel::equalsIgnoreCase)) {
            parts.add(columnLabel);
        }
        String label = String.join(" / ", parts);
        if (label.length() > 240) {
            label = label.substring(0, 237) + "...";
        }

        // Sheet title = topmost occupied row when it is a lone text label. Section header = the
        // nearest lone text row above the target, excluding the title row itself.
        String sheetTitle = null;
        String sectionLabel = null;
        if (loneTextRows != null && !loneTextRows.isEmpty()) {
            int firstOccupiedRow = sheetCells.stream()
                    .mapToInt(CellNode::getRow).min().orElse(Integer.MAX_VALUE);
            Integer titleRow = loneTextRows.firstKey().equals(firstOccupiedRow)
                    ? loneTextRows.firstKey() : null;
            if (titleRow != null) {
                sheetTitle = loneTextRows.get(titleRow);
            }
            Map.Entry<Integer, String> section = loneTextRows.lowerEntry(target.getRow());
            if (section != null && (titleRow == null || !section.getKey().equals(titleRow))) {
                sectionLabel = section.getValue();
            }
        }
        return new CellSemanticContext(
                rowLabel, rowLabels, rowDimensions, columnLabel, sectionLabel, sheetTitle, label);
    }

    private static String dimensionKey(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.isBlank() ? null : normalized;
    }

    private static boolean isTextLabel(CellNode cell) {
        return cell != null && "STRING".equals(cell.getCellType())
                && cell.getDisplayValue() != null && !cell.getDisplayValue().isBlank();
    }

    private record CellSemanticContext(
            String rowLabel,
            List<String> rowLabels,
            Map<String, String> rowDimensions,
            String columnLabel,
            String sectionLabel,
            String sheetTitle,
            String label) {

        private CellSemanticContext {
            rowLabels = rowLabels == null ? List.of() : List.copyOf(rowLabels);
            rowDimensions = rowDimensions == null
                    ? Map.of() : Map.copyOf(new LinkedHashMap<>(rowDimensions));
        }

        private static CellSemanticContext empty() {
            return new CellSemanticContext(null, List.of(), Map.of(), null, null, null, "");
        }
    }

    // ─── Sheet → markdown grid helpers ────────────────────────────────────────

    /** Maximum rows included when reconstructing a sheet as a markdown table. */
    private static final int MAX_SHEET_TABLE_ROWS = 200;
    /** Maximum columns included when reconstructing a sheet as a markdown table. */
    private static final int MAX_SHEET_TABLE_COLS = 60;

    /**
     * Converts a column letter string (A, B, …, Z, AA, AB, …) to a zero-based column index.
     */
    private static int columnLetterToIndex(String column) {
        if (column == null || column.isBlank()) return 0;
        int result = 0;
        for (char c : column.toUpperCase().toCharArray()) {
            result = result * 26 + (c - 'A' + 1);
        }
        return result - 1;
    }

    /**
     * Sanitizes a cell value for embedding inside a GFM markdown table cell.
     */
    private static String sanitizeMdCell(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("\r", " ").replace("\n", " ").replace("|", "\\|").trim();
    }

    /**
     * Reconstructs a GFM markdown table from the non-empty cells of a single worksheet.
     * Row 0 (the topmost occupied row) becomes the header row; all subsequent rows are data rows.
     * Returns an empty string when the sheet has no non-empty cells (caller skips the entity).
     * Output is capped at {@link #MAX_SHEET_TABLE_ROWS} rows × {@link #MAX_SHEET_TABLE_COLS} columns.
     */
    private static String buildSheetMarkdown(List<CellNode> sheetCells) {
        List<CellNode> nonEmpty = sheetCells.stream()
                .filter(c -> c.getDisplayValue() != null && !c.getDisplayValue().isBlank())
                .collect(Collectors.toList());
        if (nonEmpty.isEmpty()) return "";

        int maxRow1Based = nonEmpty.stream().mapToInt(CellNode::getRow).max().orElse(1);
        int maxColIdx = nonEmpty.stream()
                .mapToInt(c -> columnLetterToIndex(c.getColumn())).max().orElse(0);

        int cappedRows = Math.min(maxRow1Based, MAX_SHEET_TABLE_ROWS);
        int cappedCols = Math.min(maxColIdx + 1, MAX_SHEET_TABLE_COLS);
        boolean truncated = maxRow1Based > MAX_SHEET_TABLE_ROWS || (maxColIdx + 1) > MAX_SHEET_TABLE_COLS;

        // Build sparse grid (rows are 1-based in CellNode → convert to 0-based index)
        String[][] grid = new String[cappedRows][cappedCols];
        for (CellNode cell : nonEmpty) {
            int r = cell.getRow() - 1;
            int c = columnLetterToIndex(cell.getColumn());
            if (r < cappedRows && c < cappedCols) {
                grid[r][c] = cell.getDisplayValue();
            }
        }

        // Trim fully-empty trailing rows and columns
        int lastRow = -1;
        int lastCol = -1;
        for (int r = 0; r < cappedRows; r++) {
            for (int c = 0; c < cappedCols; c++) {
                if (grid[r][c] != null && !grid[r][c].isBlank()) {
                    if (r > lastRow) lastRow = r;
                    if (c > lastCol) lastCol = c;
                }
            }
        }
        if (lastRow < 0) return "";

        int usedRows = lastRow + 1;
        int usedCols = lastCol + 1;

        StringBuilder sb = new StringBuilder();
        // Row 0 → header row
        sb.append('|');
        for (int c = 0; c < usedCols; c++) {
            sb.append(' ').append(sanitizeMdCell(grid[0][c])).append(" |");
        }
        sb.append('\n').append('|');
        for (int c = 0; c < usedCols; c++) sb.append(" --- |");
        sb.append('\n');
        // Data rows
        for (int r = 1; r < usedRows; r++) {
            sb.append('|');
            for (int c = 0; c < usedCols; c++) {
                sb.append(' ').append(sanitizeMdCell(grid[r][c])).append(" |");
            }
            sb.append('\n');
        }
        if (truncated) {
            sb.append("_(truncated to ").append(MAX_SHEET_TABLE_ROWS)
              .append(" rows × ").append(MAX_SHEET_TABLE_COLS).append(" cols)_\n");
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Parse sheet name from a cell reference like "Sheet1!A1" or "'Sheet Name'!A1:B10".
     * Returns null if no sheet qualifier is present.
     */
    private String parseSheetFromRef(String ref) {
        if (ref == null) return null;
        int bangIdx = ref.indexOf('!');
        if (bangIdx <= 0) return null;
        String sheetPart = ref.substring(0, bangIdx);
        // Remove surrounding quotes if present
        if (sheetPart.startsWith("'") && sheetPart.endsWith("'")) {
            sheetPart = sheetPart.substring(1, sheetPart.length() - 1);
        }
        return sheetPart;
    }

    /**
     * Produce a human-readable summary of the formula graph for embedding.
     */
    public String toSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Spreadsheet Formula Graph");
        if (workbookName != null) {
            sb.append(": ").append(workbookName);
        }
        sb.append("\n");

        List<CellNode> formulas = getFormulaCells();
        sb.append("Sheets: ").append(getSheetNames().size())
                .append(", Formula cells: ").append(formulas.size())
                .append(", Dependencies: ").append(dependencies.size())
                .append(", Cross-sheet refs: ").append(getCrossSheetDependencies().size())
                .append(", Named ranges: ").append(namedRanges.size())
                .append("\n\n");

        // Group formulas by sheet
        Map<String, List<CellNode>> formulasBySheet = formulas.stream()
                .collect(Collectors.groupingBy(CellNode::getSheetName, LinkedHashMap::new, Collectors.toList()));

        for (Map.Entry<String, List<CellNode>> entry : formulasBySheet.entrySet()) {
            sb.append("Sheet '").append(entry.getKey()).append("' formulas:\n");
            for (CellNode cell : entry.getValue()) {
                sb.append("  ").append(cell.getCellReference())
                        .append(" = ").append(cell.getFormula());
                if (cell.getDisplayValue() != null) {
                    sb.append(" [").append(cell.getDisplayValue()).append("]");
                }
                sb.append("\n");
            }
        }

        if (!namedRanges.isEmpty()) {
            sb.append("\nNamed ranges:\n");
            for (Map.Entry<String, String> nr : namedRanges.entrySet()) {
                sb.append("  ").append(nr.getKey()).append(" -> ").append(nr.getValue()).append("\n");
            }
        }

        return sb.toString();
    }
}
