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

        // Create sheet entities as composite containers
        Map<String, Entity> sheetEntities = new LinkedHashMap<>();
        for (String sheetName : getSheetNames()) {
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
            sheetEntity.setMetadata(sheetMeta);
            graph.getEntities().add(sheetEntity);
            sheetEntities.put(sheetName, sheetEntity);
        }

        // Create cell entities
        for (CellNode cell : cells.values()) {
            Entity cellEntity = new Entity();
            cellEntity.setId(ns + "cell:" + cell.getCellReference());
            cellEntity.setTitle(cell.getCellReference());

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
