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

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.ss.SpreadsheetVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts formula dependency graphs from Excel workbooks.
 *
 * <p>Walks every cell in the workbook, identifies formula cells, parses their
 * references (single cell, range, cross-sheet, named range), and builds a
 * {@link SpreadsheetGraph} containing all cell nodes and dependency edges.
 */
public class ExcelFormulaGraphExtractor {

    private static final Logger logger = LoggerFactory.getLogger(ExcelFormulaGraphExtractor.class);

    /**
     * Pattern to match cell references in formulas.
     * Handles: A1, $A$1, Sheet1!A1, 'Sheet Name'!A1, A1:B10, Sheet1!A1:B10
     */
    private static final Pattern CELL_REF_PATTERN = Pattern.compile(
            "(?:'([^']+)'|([A-Za-z_][A-Za-z0-9_.]*))?!?" +
            "(\\$?[A-Z]{1,3}\\$?\\d+)" +
            "(?::(\\$?[A-Z]{1,3}\\$?\\d+))?"
    );

    /**
     * Pattern to match standalone sheet-qualified references: Sheet1!A1 or 'Sheet Name'!A1
     */
    private static final Pattern SHEET_REF_PATTERN = Pattern.compile(
            "(?:'([^']+)'|([A-Za-z_][A-Za-z0-9_.]*))!(\\$?[A-Z]{1,3}\\$?\\d+(?::\\$?[A-Z]{1,3}\\$?\\d+)?)"
    );

    private final FormulaEvaluator evaluator;

    public ExcelFormulaGraphExtractor(Workbook workbook) {
        FormulaEvaluator eval = null;
        try {
            eval = workbook.getCreationHelper().createFormulaEvaluator();
        } catch (Exception e) {
            logger.debug("Could not create formula evaluator: {}", e.getMessage());
        }
        this.evaluator = eval;
    }

    /**
     * Extract the complete formula dependency graph from the workbook.
     */
    public SpreadsheetGraph extract(Workbook workbook, String workbookName) {
        SpreadsheetGraph graph = new SpreadsheetGraph();
        graph.setWorkbookName(workbookName);

        // Collect named ranges
        extractNamedRanges(workbook, graph);

        // Extract structured tables (ListObjects/XSSFTables)
        extractStructuredTables(workbook, graph);

        // Walk all sheets and cells
        for (int sheetIdx = 0; sheetIdx < workbook.getNumberOfSheets(); sheetIdx++) {
            Sheet sheet = workbook.getSheetAt(sheetIdx);
            String sheetName = sheet.getSheetName();

            for (Row row : sheet) {
                for (Cell cell : row) {
                    CellNode node = buildCellNode(cell, sheetName);
                    if (node != null) {
                        graph.addCell(node);

                        // If it's a formula cell, extract dependencies
                        if ("FORMULA".equals(node.getCellType())) {
                            extractDependencies(node, workbook, graph);
                        }
                    }
                }
            }
        }

        // Extract data validation rules per sheet
        for (int sheetIdx = 0; sheetIdx < workbook.getNumberOfSheets(); sheetIdx++) {
            Sheet sheet = workbook.getSheetAt(sheetIdx);
            String sheetName = sheet.getSheetName();
            try {
                List<? extends DataValidation> validations = sheet.getDataValidations();
                if (validations != null) {
                    for (DataValidation dv : validations) {
                        DataValidationConstraint constraint = dv.getValidationConstraint();
                        if (constraint == null) continue;
                        String cellRanges = "";
                        if (dv.getRegions() != null) {
                            org.apache.poi.ss.util.CellRangeAddress[] ranges = dv.getRegions().getCellRangeAddresses();
                            if (ranges != null && ranges.length > 0) {
                                StringJoiner sj = new StringJoiner(",");
                                for (org.apache.poi.ss.util.CellRangeAddress r : ranges) {
                                    sj.add(r.formatAsString());
                                }
                                cellRanges = sj.toString();
                            }
                        }
                        String valType = switch (constraint.getValidationType()) {
                            case DataValidationConstraint.ValidationType.LIST -> "LIST";
                            case DataValidationConstraint.ValidationType.INTEGER -> "INTEGER";
                            case DataValidationConstraint.ValidationType.DECIMAL -> "DECIMAL";
                            case DataValidationConstraint.ValidationType.DATE -> "DATE";
                            case DataValidationConstraint.ValidationType.TIME -> "TIME";
                            case DataValidationConstraint.ValidationType.TEXT_LENGTH -> "TEXT_LENGTH";
                            case DataValidationConstraint.ValidationType.FORMULA -> "FORMULA";
                            default -> "ANY";
                        };
                        graph.addDataValidation(new SpreadsheetGraph.DataValidationRule(
                                sheetName, cellRanges, valType,
                                constraint.getFormula1(), constraint.getFormula2(),
                                dv.getErrorBoxTitle(), dv.getErrorBoxText(),
                                dv.getPromptBoxTitle(), dv.getPromptBoxText()
                        ));
                    }
                }
            } catch (Exception dvEx) {
                logger.debug("Could not extract data validations from sheet '{}': {}", sheetName, dvEx.getMessage());
            }
        }

        // Mark named-range target cells
        for (Map.Entry<String, String> nr : graph.getNamedRanges().entrySet()) {
            CellNode target = graph.getCells().get(nr.getValue());
            if (target != null) {
                target.setNamedRange(true);
                target.setNamedRangeName(nr.getKey());
            }
        }

        logger.info("Extracted formula graph from '{}': {} cells, {} formula cells, {} dependencies, {} named ranges",
                workbookName,
                graph.getCells().size(),
                graph.getFormulaCells().size(),
                graph.getDependencies().size(),
                graph.getNamedRanges().size());

        return graph;
    }

    private void extractNamedRanges(Workbook workbook, SpreadsheetGraph graph) {
        for (Name name : workbook.getAllNames()) {
            if (!name.isFunctionName()) {
                try {
                    String ref = name.getRefersToFormula();
                    graph.addNamedRange(name.getNameName(), ref);
                } catch (Exception e) {
                    logger.debug("Skipping named range '{}': {}", name.getNameName(), e.getMessage());
                }
            }
        }
    }

    private void extractStructuredTables(Workbook workbook, SpreadsheetGraph graph) {
        if (workbook instanceof org.apache.poi.xssf.usermodel.XSSFWorkbook xssfWorkbook) {
            for (int i = 0; i < xssfWorkbook.getNumberOfSheets(); i++) {
                org.apache.poi.xssf.usermodel.XSSFSheet xssfSheet = xssfWorkbook.getSheetAt(i);
                String sheetName = xssfSheet.getSheetName();
                for (org.apache.poi.xssf.usermodel.XSSFTable table : xssfSheet.getTables()) {
                    try {
                        List<String> columns = new ArrayList<>();
                        if (table.getCTTable().getTableColumns() != null) {
                            for (org.openxmlformats.schemas.spreadsheetml.x2006.main.CTTableColumn col :
                                    table.getCTTable().getTableColumns().getTableColumnList()) {
                                columns.add(col.getName());
                            }
                        }
                        org.apache.poi.ss.util.AreaReference area = table.getArea();
                        SpreadsheetGraph.StructuredTable st = new SpreadsheetGraph.StructuredTable(
                                table.getName(),
                                sheetName,
                                table.getDisplayName(),
                                area != null ? area.getFirstCell().getRow() : 0,
                                area != null ? area.getLastCell().getRow() : 0,
                                area != null ? area.getFirstCell().getCol() : 0,
                                area != null ? area.getLastCell().getCol() : 0,
                                columns
                        );
                        graph.addStructuredTable(st);
                        logger.debug("Extracted structured table '{}' on sheet '{}' with {} columns",
                                table.getName(), sheetName, columns.size());
                    } catch (Exception e) {
                        logger.debug("Skipping table '{}': {}", table.getName(), e.getMessage());
                    }
                }
            }
        }
    }

    private CellNode buildCellNode(Cell cell, String sheetName) {
        String colLetter = CellReference.convertNumToColString(cell.getColumnIndex());
        int rowNum = cell.getRowIndex() + 1;
        String cellRef = sheetName + "!" + colLetter + rowNum;

        CellNode.CellNodeBuilder builder = CellNode.builder()
                .cellReference(cellRef)
                .sheetName(sheetName)
                .column(colLetter)
                .row(rowNum);

        switch (cell.getCellType()) {
            case FORMULA:
                builder.cellType("FORMULA");
                builder.formula(cell.getCellFormula());
                builder.displayValue(evaluateForDisplay(cell));
                break;
            case STRING:
                builder.cellType("STRING");
                builder.displayValue(cell.getStringCellValue());
                break;
            case NUMERIC:
                builder.cellType(DateUtil.isCellDateFormatted(cell) ? "DATE" : "NUMERIC");
                builder.displayValue(DateUtil.isCellDateFormatted(cell)
                        ? cell.getDateCellValue().toString()
                        : formatNumeric(cell.getNumericCellValue()));
                break;
            case BOOLEAN:
                builder.cellType("BOOLEAN");
                builder.displayValue(String.valueOf(cell.getBooleanCellValue()));
                break;
            case BLANK:
                // Skip blank cells unless they're referenced by a formula
                // (they'll be added lazily when encountered as dependencies)
                return null;
            default:
                builder.cellType("UNKNOWN");
                builder.displayValue("");
                break;
        }

        // Capture cell comment/note if present
        if (cell.getCellComment() != null) {
            org.apache.poi.ss.usermodel.Comment cellComment = cell.getCellComment();
            if (cellComment.getString() != null) {
                builder.comment(cellComment.getString().getString());
            }
            if (cellComment.getAuthor() != null) {
                builder.commentAuthor(cellComment.getAuthor());
            }
        }

        // Capture cell hyperlink if present
        org.apache.poi.ss.usermodel.Hyperlink cellHyperlink = cell.getHyperlink();
        if (cellHyperlink != null) {
            if (cellHyperlink.getAddress() != null && !cellHyperlink.getAddress().isBlank()) {
                builder.hyperlink(cellHyperlink.getAddress());
            }
            if (cellHyperlink.getLabel() != null && !cellHyperlink.getLabel().isBlank()) {
                builder.hyperlinkLabel(cellHyperlink.getLabel());
            }
        }

        return builder.build();
    }

    private String evaluateForDisplay(Cell cell) {
        if (evaluator == null) {
            return "(unevaluated)";
        }
        try {
            CellValue val = evaluator.evaluate(cell);
            if (val == null) return "(null)";
            switch (val.getCellType()) {
                case STRING: return val.getStringValue();
                case NUMERIC: return formatNumeric(val.getNumberValue());
                case BOOLEAN: return String.valueOf(val.getBooleanValue());
                case ERROR: return "#ERROR";
                default: return "";
            }
        } catch (Exception e) {
            return "(eval-error)";
        }
    }

    private String formatNumeric(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    /**
     * Parse a formula string and extract all cell/range references as dependencies.
     */
    private void extractDependencies(CellNode formulaNode, Workbook workbook, SpreadsheetGraph graph) {
        String formula = formulaNode.getFormula();
        if (formula == null || formula.isEmpty()) return;

        String formulaSheet = formulaNode.getSheetName();
        Set<String> processedRefs = new HashSet<>();

        // Check for named range references first
        for (Map.Entry<String, String> nr : graph.getNamedRanges().entrySet()) {
            if (formula.contains(nr.getKey())) {
                String targetRef = nr.getValue();
                // Named ranges can be ranges like "Sheet1!A1:A10" — resolve them
                addNamedRangeDependencies(formulaNode, nr.getKey(), targetRef, formulaSheet, graph, processedRefs);
            }
        }

        // Parse explicit cell and range references
        Matcher matcher = CELL_REF_PATTERN.matcher(formula);
        while (matcher.find()) {
            String quotedSheet = matcher.group(1);
            String unquotedSheet = matcher.group(2);
            String startCell = matcher.group(3);
            String endCell = matcher.group(4);

            // Determine the target sheet
            String refSheet = quotedSheet != null ? quotedSheet
                    : unquotedSheet != null ? unquotedSheet
                    : formulaSheet;

            // Skip if this looks like a function name (e.g., SUM, VLOOKUP)
            if (unquotedSheet != null && isExcelFunction(unquotedSheet)) {
                // The "sheet" part is actually a function name; the cell ref follows
                refSheet = formulaSheet;
            }

            boolean crossSheet = !refSheet.equals(formulaSheet);

            if (endCell != null) {
                // Range reference: A1:B10
                addRangeDependencies(formulaNode, refSheet, startCell, endCell,
                        crossSheet, formula, graph, processedRefs);
            } else {
                // Single cell reference
                String cleanRef = cleanCellRef(startCell);
                String fullRef = refSheet + "!" + cleanRef;

                if (processedRefs.add(fullRef)) {
                    ensureCellExists(fullRef, refSheet, cleanRef, graph);

                    FormulaDependency.DependencyType depType = crossSheet
                            ? FormulaDependency.DependencyType.CROSS_SHEET_REFERENCE
                            : FormulaDependency.DependencyType.CELL_REFERENCE;

                    graph.addDependency(FormulaDependency.builder()
                            .formulaCell(formulaNode.getCellReference())
                            .referencedCell(fullRef)
                            .dependencyType(depType)
                            .crossSheet(crossSheet)
                            .formula(formula)
                            .build());
                }
            }
        }
    }

    private void addRangeDependencies(CellNode formulaNode, String sheetName,
                                       String startCell, String endCell,
                                       boolean crossSheet, String formula,
                                       SpreadsheetGraph graph, Set<String> processedRefs) {
        String cleanStart = cleanCellRef(startCell);
        String cleanEnd = cleanCellRef(endCell);
        String rangeStr = cleanStart + ":" + cleanEnd;

        try {
            CellReference start = new CellReference(cleanStart);
            CellReference end = new CellReference(cleanEnd);

            int startRow = start.getRow();
            int endRow = end.getRow();
            int startCol = start.getCol();
            int endCol = end.getCol();

            for (int r = startRow; r <= endRow; r++) {
                for (int c = startCol; c <= endCol; c++) {
                    String col = CellReference.convertNumToColString(c);
                    String cellRef = sheetName + "!" + col + (r + 1);

                    if (processedRefs.add(cellRef)) {
                        ensureCellExists(cellRef, sheetName, col + (r + 1), graph);

                        graph.addDependency(FormulaDependency.builder()
                                .formulaCell(formulaNode.getCellReference())
                                .referencedCell(cellRef)
                                .dependencyType(FormulaDependency.DependencyType.RANGE_REFERENCE)
                                .crossSheet(crossSheet)
                                .formula(formula)
                                .rangeReference(rangeStr)
                                .build());
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not parse range reference {}:{} in sheet {}: {}",
                    startCell, endCell, sheetName, e.getMessage());
        }
    }

    private void addNamedRangeDependencies(CellNode formulaNode, String rangeName,
                                            String targetRef, String formulaSheet,
                                            SpreadsheetGraph graph, Set<String> processedRefs) {
        try {
            // Named range refs look like "Sheet1!A1:A10" or just "A1:A10"
            Matcher sheetMatcher = SHEET_REF_PATTERN.matcher(targetRef);
            String refSheet = formulaSheet;
            String cellPart = targetRef;

            if (sheetMatcher.find()) {
                refSheet = sheetMatcher.group(1) != null ? sheetMatcher.group(1) : sheetMatcher.group(2);
                cellPart = sheetMatcher.group(3);
            }

            boolean crossSheet = !refSheet.equals(formulaSheet);

            if (cellPart.contains(":")) {
                String[] parts = cellPart.split(":");
                addRangeDependencies(formulaNode, refSheet, parts[0], parts[1],
                        crossSheet, formulaNode.getFormula(), graph, processedRefs);
            } else {
                String cleanRef = cleanCellRef(cellPart);
                String fullRef = refSheet + "!" + cleanRef;
                if (processedRefs.add(fullRef)) {
                    ensureCellExists(fullRef, refSheet, cleanRef, graph);
                    graph.addDependency(FormulaDependency.builder()
                            .formulaCell(formulaNode.getCellReference())
                            .referencedCell(fullRef)
                            .dependencyType(FormulaDependency.DependencyType.NAMED_RANGE_REFERENCE)
                            .crossSheet(crossSheet)
                            .formula(formulaNode.getFormula())
                            .build());
                }
            }
        } catch (Exception e) {
            logger.debug("Could not resolve named range '{}' -> '{}': {}", rangeName, targetRef, e.getMessage());
        }
    }

    /**
     * Ensure a referenced cell exists in the graph, even if it was blank/unvisited.
     */
    private void ensureCellExists(String fullRef, String sheetName, String localRef, SpreadsheetGraph graph) {
        if (!graph.getCells().containsKey(fullRef)) {
            String cleanRef = cleanCellRef(localRef);
            CellReference cr = new CellReference(cleanRef);
            graph.addCell(CellNode.builder()
                    .cellReference(fullRef)
                    .sheetName(sheetName)
                    .column(CellReference.convertNumToColString(cr.getCol()))
                    .row(cr.getRow() + 1)
                    .cellType("BLANK")
                    .displayValue("")
                    .build());
        }
    }

    /**
     * Strip $ anchors from a cell reference: $A$1 -> A1
     */
    private String cleanCellRef(String ref) {
        return ref.replace("$", "");
    }

    /**
     * Check if a string is a known Excel function name rather than a sheet name.
     */
    private boolean isExcelFunction(String name) {
        return EXCEL_FUNCTIONS.contains(name.toUpperCase());
    }

    private static final Set<String> EXCEL_FUNCTIONS = Set.of(
            "SUM", "AVERAGE", "COUNT", "COUNTA", "COUNTIF", "COUNTIFS",
            "SUMIF", "SUMIFS", "AVERAGEIF", "AVERAGEIFS",
            "IF", "IFERROR", "IFNA", "IFS",
            "VLOOKUP", "HLOOKUP", "XLOOKUP", "INDEX", "MATCH",
            "LEFT", "RIGHT", "MID", "LEN", "TRIM", "UPPER", "LOWER", "PROPER",
            "CONCATENATE", "CONCAT", "TEXTJOIN", "TEXT", "VALUE",
            "DATE", "TODAY", "NOW", "YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND",
            "DATEDIF", "EDATE", "EOMONTH", "NETWORKDAYS", "WORKDAY",
            "MAX", "MIN", "LARGE", "SMALL", "RANK", "PERCENTILE",
            "ABS", "ROUND", "ROUNDUP", "ROUNDDOWN", "INT", "MOD", "POWER", "SQRT",
            "AND", "OR", "NOT", "TRUE", "FALSE", "XOR",
            "OFFSET", "INDIRECT", "ROW", "COLUMN", "ROWS", "COLUMNS",
            "CHOOSE", "SWITCH", "TRANSPOSE", "SORT", "FILTER", "UNIQUE",
            "SUMPRODUCT", "SUBTOTAL", "AGGREGATE",
            "ISNUMBER", "ISTEXT", "ISBLANK", "ISERROR", "ISNA", "ISLOGICAL",
            "TYPE", "N", "T", "NUMBERVALUE",
            "PMT", "PV", "FV", "NPV", "IRR", "RATE", "NPER",
            "STDEV", "VAR", "MEDIAN", "MODE", "FREQUENCY",
            "HYPERLINK", "CELL", "INFO",
            "LAMBDA", "LET", "MAP", "REDUCE", "SCAN", "MAKEARRAY", "BYROW", "BYCOL",
            "SEQUENCE", "RANDARRAY", "SORTBY", "TAKE", "DROP", "EXPAND", "WRAPCOLS", "WRAPROWS"
    );
}
