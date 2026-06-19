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

package ai.kompile.loader.excel;

import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.table.TableCellGraphBuilder;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.loader.excel.graph.ExcelFormulaGraphExtractor;
import ai.kompile.loader.excel.graph.SpreadsheetGraph;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.Base64;
import java.util.function.Consumer;

import org.springframework.core.annotation.Order;

/**
 * Excel document loader with formula dependency graph extraction.
 *
 * <p>Produces multiple {@link Document}s per workbook:
 * <ul>
 *   <li>One document per sheet (cell data as markdown table)</li>
 *   <li>One document for the formula graph summary (if formulas exist)</li>
 * </ul>
 *
 * <p>The formula dependency graph is stored as serialized JSON in the
 * {@code formulaGraph} metadata key and can be converted to the core
 * {@link Graph} model for knowledge graph integration.
 */
@Component
@Order(10) // Higher priority than Tika fallback (@Order(1000))
public class ExcelLoaderImpl implements DocumentLoader {

    private static final Logger logger = LoggerFactory.getLogger(ExcelLoaderImpl.class);
    private static final ObjectMapper objectMapper = JsonUtils.standardMapper();

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("xls", "xlsx", "xlsm", "ods");

    @Override
    public String getName() {
        return "Excel Formula Graph Loader";
    }

    @Override
    public boolean supports(DocumentSourceDescriptor sourceDescriptor) {
        if (sourceDescriptor.getType() != DocumentSourceDescriptor.SourceType.FILE) {
            return false;
        }
        String path = sourceDescriptor.getPathOrUrl() != null ? sourceDescriptor.getPathOrUrl().toLowerCase() : "";
        return SUPPORTED_EXTENSIONS.stream().anyMatch(path::endsWith);
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor sourceDescriptor) throws Exception {
        return load(sourceDescriptor, null);
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor sourceDescriptor,
                                Consumer<LoaderProgress> progressCallback) throws Exception {
        if (sourceDescriptor.getType() != DocumentSourceDescriptor.SourceType.FILE) {
            throw new IllegalArgumentException("ExcelLoader only supports FILE sources.");
        }

        File file = new File(sourceDescriptor.getPathOrUrl());
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("File does not exist: " + sourceDescriptor.getPathOrUrl());
        }

        String filename = file.getName().toLowerCase();

        try {
            reportProgress(progressCallback, "Opening", 0, "Opening workbook");
            return loadWorkbook(file, filename, progressCallback);
        } catch (Exception e) {
            logger.warn("Unable to parse spreadsheet '{}': {}. File may be corrupted or password-protected.",
                    file.getName(), e.getMessage());

            Document errorDoc = new Document(
                    "[Error: Unable to parse spreadsheet file. The file may be corrupted, truncated, or password-protected.]");
            addMetadata(errorDoc, file, "Spreadsheet");
            errorDoc.getMetadata().put("parseError", true);
            errorDoc.getMetadata().put("errorMessage", e.getMessage() != null ? e.getMessage() : "Unknown error");
            return List.of(errorDoc);
        }
    }

    private List<Document> loadWorkbook(File file, String filename,
                                         Consumer<LoaderProgress> progressCallback) throws IOException {
        String docType;
        if (filename.endsWith(".xls")) {
            docType = "Microsoft Excel Spreadsheet (.xls)";
        } else if (filename.endsWith(".xlsx") || filename.endsWith(".xlsm")) {
            docType = "Microsoft Excel Spreadsheet (.xlsx)";
        } else if (filename.endsWith(".ods")) {
            docType = "LibreOffice Calc Spreadsheet (.ods)";
        } else {
            docType = "Spreadsheet";
        }

        // WorkbookFactory handles all formats: XLS, XLSX, XLSM, ODS
        try (Workbook workbook = WorkbookFactory.create(file)) {
            return extractWorkbook(workbook, file, docType, progressCallback);
        }
    }

    private List<Document> extractWorkbook(Workbook workbook, File file, String docType,
                                            Consumer<LoaderProgress> progressCallback) {
        List<Document> documents = new ArrayList<>();
        int totalSheets = workbook.getNumberOfSheets();

        // Extract workbook-level document properties (author, title, keywords, etc.)
        Map<String, Object> workbookProperties = extractWorkbookProperties(workbook);

        // Phase 1: Extract formula graph
        reportProgress(progressCallback, "Analyzing", 10, "Extracting formula dependency graph");
        ExcelFormulaGraphExtractor extractor = new ExcelFormulaGraphExtractor(workbook);
        SpreadsheetGraph spreadsheetGraph = extractor.extract(workbook, file.getName());

        // Convert to core Graph model
        Graph coreGraph = spreadsheetGraph.toGraph();
        String graphJson = serializeGraph(coreGraph);

        // Phase 2: Extract per-sheet content as markdown tables
        for (int i = 0; i < totalSheets; i++) {
            Sheet sheet = workbook.getSheetAt(i);
            String sheetName = sheet.getSheetName();
            int progressPct = 20 + (int) ((double) i / totalSheets * 60);
            reportProgress(progressCallback, "Extracting", progressPct,
                    "Processing sheet: " + sheetName);

            if (Thread.currentThread().isInterrupted()) {
                logger.info("Excel loading interrupted at sheet {}/{}", i + 1, totalSheets);
                break;
            }

            Document sheetDoc = extractSheetAsMarkdown(sheet, file, docType, i);
            if (sheetDoc != null) {
                // Attach per-sheet formula info
                List<String> sheetFormulas = new ArrayList<>();
                for (var cell : spreadsheetGraph.getFormulaCells()) {
                    if (cell.getSheetName().equals(sheetName)) {
                        sheetFormulas.add(cell.getCellReference() + "=" + cell.getFormula());
                    }
                }
                if (!sheetFormulas.isEmpty()) {
                    sheetDoc.getMetadata().put(GraphConstants.META_FORMULAS, String.join("; ", sheetFormulas));
                    sheetDoc.getMetadata().put(GraphConstants.META_FORMULA_COUNT, sheetFormulas.size());
                }

                // Attach per-sheet cell comments as structured list for graph extraction
                List<Map<String, String>> sheetCellComments = new ArrayList<>();
                for (var cellEntry : spreadsheetGraph.getCells().entrySet()) {
                    var cellNode = cellEntry.getValue();
                    if (cellNode.getSheetName() != null && cellNode.getSheetName().equals(sheetName)
                            && cellNode.getComment() != null && !cellNode.getComment().isBlank()) {
                        Map<String, String> cm = new LinkedHashMap<>();
                        cm.put("cellRef", cellNode.getCellReference());
                        cm.put("text", cellNode.getComment());
                        if (cellNode.getCommentAuthor() != null) cm.put("author", cellNode.getCommentAuthor());
                        sheetCellComments.add(cm);
                    }
                }
                if (!sheetCellComments.isEmpty()) {
                    sheetDoc.getMetadata().put(GraphConstants.META_CELL_COMMENTS, sheetCellComments);
                }

                // Attach per-sheet formula cells as structured list for graph extraction
                List<Map<String, String>> sheetFormulaCells = new ArrayList<>();
                for (var cell : spreadsheetGraph.getFormulaCells()) {
                    if (cell.getSheetName().equals(sheetName)) {
                        Map<String, String> fc = new LinkedHashMap<>();
                        fc.put("cellRef", cell.getCellReference());
                        fc.put("formula", cell.getFormula());
                        if (cell.getDisplayValue() != null) fc.put("value", cell.getDisplayValue());
                        sheetFormulaCells.add(fc);
                    }
                }
                if (!sheetFormulaCells.isEmpty()) {
                    sheetDoc.getMetadata().put(GraphConstants.META_FORMULA_CELLS, sheetFormulaCells);
                }

                // Attach named ranges on first sheet (workbook-level)
                if (i == 0 && !spreadsheetGraph.getNamedRanges().isEmpty()) {
                    List<Map<String, String>> namedRangesList = new ArrayList<>();
                    for (var nr : spreadsheetGraph.getNamedRanges().entrySet()) {
                        Map<String, String> nrMap = new LinkedHashMap<>();
                        nrMap.put("name", nr.getKey());
                        nrMap.put("refersTo", nr.getValue());
                        // Extract sheet name from reference if present (e.g., "Sheet1!A1:B10")
                        String ref = nr.getValue();
                        if (ref != null && ref.contains("!")) {
                            nrMap.put("sheetName", ref.substring(0, ref.indexOf('!')));
                        }
                        namedRangesList.add(nrMap);
                    }
                    sheetDoc.getMetadata().put(GraphConstants.META_NAMED_RANGES, namedRangesList);
                }

                documents.add(sheetDoc);
            }
        }

        // Phase 3: Create formula graph document (if there are formulas)
        if (!spreadsheetGraph.getFormulaCells().isEmpty()) {
            reportProgress(progressCallback, "Building graph", 85, "Creating formula graph document");

            Document graphDoc = new Document(spreadsheetGraph.toSummary());
            addMetadata(graphDoc, file, "Excel Formula Graph");
            graphDoc.getMetadata().put(GraphConstants.META_CONTENT_TYPE, "formula_graph");
            graphDoc.getMetadata().put(GraphConstants.META_FORMULA_GRAPH, graphJson);
            graphDoc.getMetadata().put("totalFormulas", spreadsheetGraph.getFormulaCells().size());
            graphDoc.getMetadata().put("totalDependencies", spreadsheetGraph.getDependencies().size());
            graphDoc.getMetadata().put("crossSheetDependencies", spreadsheetGraph.getCrossSheetDependencies().size());
            graphDoc.getMetadata().put("namedRangeCount", spreadsheetGraph.getNamedRanges().size());
            graphDoc.getMetadata().put("sheetCount", spreadsheetGraph.getSheetNames().size());
            graphDoc.getMetadata().put("entityCount", coreGraph.getEntities().size());
            graphDoc.getMetadata().put("relationshipCount", coreGraph.getRelationships().size());
            graphDoc.getMetadata().put("storage_type", "search");
            documents.add(graphDoc);
        }

        // Phase 4: Extract embedded images/pictures
        reportProgress(progressCallback, "Images", 90, "Extracting embedded images");
        List<Document> imageDocs = extractEmbeddedImages(workbook, file, docType);
        documents.addAll(imageDocs);

        // Phase 5: Extract chart metadata
        reportProgress(progressCallback, "Charts", 95, "Extracting chart metadata");
        List<Document> chartDocs = extractChartMetadata(workbook, file, docType);
        documents.addAll(chartDocs);

        // Apply workbook-level document properties to all produced documents
        if (!workbookProperties.isEmpty()) {
            for (Document doc : documents) {
                workbookProperties.forEach((key, value) -> doc.getMetadata().putIfAbsent(key, value));
            }
        }

        reportProgress(progressCallback, "Complete", 100,
                String.format("Loaded %d sheets, %d formulas, %d dependencies, %d images, %d charts",
                        totalSheets, spreadsheetGraph.getFormulaCells().size(),
                        spreadsheetGraph.getDependencies().size(),
                        imageDocs.size(), chartDocs.size()));

        return documents;
    }

    /**
     * Extract a single sheet as a markdown table document.
     */
    private Document extractSheetAsMarkdown(Sheet sheet, File file, String docType, int sheetIndex) {
        int lastRow = sheet.getLastRowNum();
        if (lastRow < 0) return null;

        // Determine the max column across all rows
        int maxCol = 0;
        for (Row row : sheet) {
            if (row.getLastCellNum() > maxCol) {
                maxCol = row.getLastCellNum();
            }
        }
        if (maxCol == 0) return null;

        StringBuilder markdown = new StringBuilder();
        StringBuilder plainText = new StringBuilder();
        plainText.append("Sheet: ").append(sheet.getSheetName()).append("\n\n");

        boolean firstRow = true;
        List<String> headers = new ArrayList<>();

        for (Row row : sheet) {
            boolean hasContent = false;
            StringBuilder rowMd = new StringBuilder("|");
            StringBuilder rowTxt = new StringBuilder();

            for (int c = 0; c < maxCol; c++) {
                Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String value = "";
                if (cell != null) {
                    value = getCellDisplayValue(cell);
                    if (!value.trim().isEmpty()) hasContent = true;
                }
                // Escape pipes in markdown
                String escaped = value.replace("|", "\\|").replace("\n", " ").replace("\r", "");
                rowMd.append(" ").append(escaped).append(" |");
                rowTxt.append(value).append("\t");
            }

            if (!hasContent) continue;

            if (firstRow) {
                headers = new ArrayList<>();
                for (int c = 0; c < maxCol; c++) {
                    Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    headers.add(cell != null ? getCellDisplayValue(cell) : "");
                }
                markdown.append(rowMd).append("\n|");
                for (int c = 0; c < maxCol; c++) {
                    markdown.append("---|");
                }
                markdown.append("\n");
                firstRow = false;
            } else {
                markdown.append(rowMd).append("\n");
            }
            plainText.append(rowTxt).append("\n");
        }

        if (markdown.length() == 0) return null;

        // Use a summary for embedding, full content in metadata
        int dataRows = (int) java.util.stream.StreamSupport.stream(sheet.spliterator(), false).count();
        String summary = String.format("Sheet '%s' with %d rows and %d columns. Columns: %s",
                sheet.getSheetName(), dataRows, maxCol,
                String.join(", ", headers.subList(0, Math.min(headers.size(), 20))));

        Document doc = new Document(summary);
        addMetadata(doc, file, docType);

        // Standard table metadata keys (recognized by RagToolImpl, TableAwareChunker, MultiVectorDocument)
        doc.getMetadata().put(GraphConstants.META_CONTENT_TYPE, "table");
        doc.getMetadata().put("full_table_content", markdown.toString());
        doc.getMetadata().put("table_summary", summary);
        doc.getMetadata().put(GraphConstants.META_TABLE_ROW_COUNT, dataRows);
        doc.getMetadata().put(GraphConstants.META_TABLE_COLUMN_COUNT, maxCol);
        doc.getMetadata().put(GraphConstants.META_TABLE_INDEX, sheetIndex);
        doc.getMetadata().put("table_extraction_method", "excel-poi");
        if (!headers.isEmpty()) {
            doc.getMetadata().put(GraphConstants.META_TABLE_HEADERS, String.join(",", headers));
        }

        // Excel-specific metadata
        doc.getMetadata().put(GraphConstants.META_SHEET_NAME, sheet.getSheetName());
        doc.getMetadata().put(GraphConstants.META_SHEET_INDEX, sheetIndex);
        doc.getMetadata().put("full_text_content", plainText.toString());

        // Build cell-level table graph for knowledge graph persistence
        TableCellGraphBuilder graphBuilder = new TableCellGraphBuilder()
                .namespace("excel:" + file.getName() + "/" + sheet.getSheetName())
                .tableName(sheet.getSheetName())
                .headers(headers);
        for (Row row : sheet) {
            List<String> cellValues = new ArrayList<>();
            for (int c = 0; c < maxCol; c++) {
                Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                cellValues.add(cell != null ? getCellDisplayValue(cell) : "");
            }
            graphBuilder.addRow(cellValues);
        }
        Graph tableGraph = graphBuilder.build();
        if (!tableGraph.getEntities().isEmpty()) {
            doc.getMetadata().put(GraphConstants.META_TABLE_GRAPH, TableCellGraphBuilder.toJson(tableGraph));
        }

        // Data quality signal detection
        List<Map<String, String>> dqFlags = detectDataQualityFlags(sheet, maxCol);
        if (!dqFlags.isEmpty()) {
            try {
                doc.getMetadata().put("dq_flags", objectMapper.writeValueAsString(dqFlags));
                doc.getMetadata().put("dq_flag_count", dqFlags.size());

                // Append DQ signals to the embedding text so they're retrievable
                StringBuilder dqSection = new StringBuilder("\n\n## Data Quality Signals\n");
                for (Map<String, String> flag : dqFlags) {
                    dqSection.append(String.format("- [%s] %s: %s\n",
                            flag.get("flag"), flag.get("cell"), flag.get("rowContext")));
                }
                // Update the document text to include DQ signals so they're embedded and retrievable
                String updatedText = doc.getText() + dqSection;
                doc = doc.mutate().text(updatedText).metadata(doc.getMetadata()).build();
            } catch (Exception e) {
                logger.debug("Could not serialize DQ flags: {}", e.getMessage());
            }
        }

        return doc;
    }

    /**
     * Scans a sheet for data quality flag cells (FAIL, REVIEW, FLAG, WARN, AT RISK, LOST, ERROR).
     * Returns a list of flag records with cell reference, flag value, and row context.
     */
    private List<Map<String, String>> detectDataQualityFlags(Sheet sheet, int maxCol) {
        List<Map<String, String>> flags = new ArrayList<>();
        Set<String> FLAG_PATTERNS = Set.of("fail", "review", "flag", "warn", "at risk", "lost", "error",
                "stale", "expired", "overdue", "inactive", "rejected", "blocked", "tbd");

        for (Row row : sheet) {
            for (int c = 0; c < maxCol; c++) {
                Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                if (cell == null || cell.getCellType() != CellType.STRING) continue;

                String value = cell.getStringCellValue().trim();
                String valueLower = value.toLowerCase();

                boolean isFlag = false;
                for (String pattern : FLAG_PATTERNS) {
                    if (valueLower.equals(pattern) || valueLower.startsWith(pattern + " ")
                            || valueLower.startsWith(pattern + ":")) {
                        isFlag = true;
                        break;
                    }
                }

                if (isFlag) {
                    // Build row context from all cells in the same row
                    StringBuilder rowContext = new StringBuilder();
                    for (int rc = 0; rc < maxCol; rc++) {
                        Cell contextCell = row.getCell(rc, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        if (contextCell != null && rc != c) {
                            String cv = getCellDisplayValue(contextCell).trim();
                            if (!cv.isEmpty()) {
                                if (rowContext.length() > 0) rowContext.append(" | ");
                                rowContext.append(cv);
                            }
                        }
                    }

                    String cellRef = sheet.getSheetName() + "!" +
                            org.apache.poi.ss.util.CellReference.convertNumToColString(c) +
                            (row.getRowNum() + 1);

                    Map<String, String> flag = new LinkedHashMap<>();
                    flag.put("cell", cellRef);
                    flag.put("flag", value);
                    flag.put("rowContext", rowContext.toString());
                    flags.add(flag);
                }
            }
        }
        return flags;
    }

    private String getCellDisplayValue(Cell cell) {
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                }
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) && !Double.isInfinite(val)) {
                    return String.valueOf((long) val);
                }
                return String.valueOf(val);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                // Show formula with evaluated value
                return "=" + cell.getCellFormula();
            case BLANK:
                return "";
            default:
                return "";
        }
    }

    /**
     * Extract embedded images/pictures from all sheets.
     * Each image becomes a document with content_type=image and base64-encoded data in metadata.
     */
    private List<Document> extractEmbeddedImages(Workbook workbook, File file, String docType) {
        List<Document> docs = new ArrayList<>();
        try {
            List<? extends PictureData> pictures = workbook.getAllPictures();
            for (int i = 0; i < pictures.size(); i++) {
                PictureData picture = pictures.get(i);
                String mimeType = picture.getMimeType();
                byte[] data = picture.getData();
                if (data == null || data.length == 0) continue;

                String summary = String.format("Embedded image %d in '%s' (%s, %d bytes)",
                        i + 1, file.getName(), mimeType, data.length);

                Document doc = new Document(summary);
                addMetadata(doc, file, docType);
                doc.getMetadata().put(GraphConstants.META_CONTENT_TYPE, "image");
                doc.getMetadata().put("image_index", i);
                doc.getMetadata().put("image_mime_type", mimeType);
                doc.getMetadata().put("image_size_bytes", data.length);
                doc.getMetadata().put("storage_type", "search");

                // Store base64-encoded image for VLM processing downstream
                if (data.length <= 10 * 1024 * 1024) { // limit to 10MB per image
                    doc.getMetadata().put("image_data_base64", Base64.getEncoder().encodeToString(data));
                }

                docs.add(doc);
            }
        } catch (Exception e) {
            logger.debug("Could not extract embedded images from '{}': {}", file.getName(), e.getMessage());
        }
        return docs;
    }

    /**
     * Extract chart metadata from XLSX workbooks.
     * Charts are represented as first-class documents with content_type=chart.
     */
    private List<Document> extractChartMetadata(Workbook workbook, File file, String docType) {
        List<Document> docs = new ArrayList<>();
        // Chart extraction is only available for XSSF (xlsx) workbooks
        if (!(workbook instanceof org.apache.poi.xssf.usermodel.XSSFWorkbook)) {
            return docs;
        }

        try {
            org.apache.poi.xssf.usermodel.XSSFWorkbook xssf =
                    (org.apache.poi.xssf.usermodel.XSSFWorkbook) workbook;
            int chartIndex = 0;
            for (int s = 0; s < xssf.getNumberOfSheets(); s++) {
                org.apache.poi.xssf.usermodel.XSSFSheet sheet = xssf.getSheetAt(s);
                String sheetName = sheet.getSheetName();

                for (org.apache.poi.xssf.usermodel.XSSFChart chart : sheet.getDrawingPatriarch() != null
                        ? sheet.getDrawingPatriarch().getCharts()
                        : Collections.<org.apache.poi.xssf.usermodel.XSSFChart>emptyList()) {
                    String title = chart.getTitleText() != null ? chart.getTitleText().toString() : "";

                    StringBuilder summary = new StringBuilder();
                    summary.append(String.format("Chart '%s' on sheet '%s' in '%s'",
                            title.isEmpty() ? "Chart " + (chartIndex + 1) : title,
                            sheetName, file.getName()));

                    Document doc = new Document(summary.toString());
                    addMetadata(doc, file, docType);
                    doc.getMetadata().put(GraphConstants.META_CONTENT_TYPE, "chart");
                    doc.getMetadata().put("chart_index", chartIndex);
                    doc.getMetadata().put("chart_title", title);
                    doc.getMetadata().put(GraphConstants.META_SHEET_NAME, sheetName);
                    doc.getMetadata().put(GraphConstants.META_SHEET_INDEX, s);
                    doc.getMetadata().put("storage_type", "search");

                    docs.add(doc);
                    chartIndex++;
                }
            }
        } catch (Exception e) {
            logger.debug("Could not extract chart metadata from '{}': {}", file.getName(), e.getMessage());
        }
        return docs;
    }

    private String serializeGraph(Graph graph) {
        try {
            return objectMapper.writeValueAsString(graph);
        } catch (Exception e) {
            logger.warn("Could not serialize formula graph: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * Extracts workbook-level document properties (author, title, keywords, etc.)
     * from OOXML (XLSX/XLSM) and OLE2 (XLS) workbooks using POI APIs.
     */
    private Map<String, Object> extractWorkbookProperties(Workbook workbook) {
        Map<String, Object> props = new LinkedHashMap<>();
        try {
            if (workbook instanceof XSSFWorkbook xssf) {
                // OOXML format: .xlsx, .xlsm
                POIXMLProperties xmlProps = xssf.getProperties();
                if (xmlProps != null) {
                    POIXMLProperties.CoreProperties core = xmlProps.getCoreProperties();
                    if (core != null) {
                        putIfNonBlank(props, GraphConstants.META_AUTHOR, core.getCreator());
                        putIfNonBlank(props, GraphConstants.META_TITLE, core.getTitle());
                        putIfNonBlank(props, GraphConstants.META_SUBJECT, core.getSubject());
                        putIfNonBlank(props, GraphConstants.META_KEYWORDS, core.getKeywords());
                        putIfNonBlank(props, GraphConstants.META_DESCRIPTION, core.getDescription());
                        putIfNonBlank(props, "lastModifiedBy", core.getLastModifiedByUser());
                        if (core.getCreated() != null) {
                            props.put(GraphConstants.META_CREATION_DATE, core.getCreated().toString());
                        }
                        if (core.getModified() != null) {
                            props.put(GraphConstants.META_MODIFICATION_DATE, core.getModified().toString());
                        }
                    }
                    POIXMLProperties.ExtendedProperties ext = xmlProps.getExtendedProperties();
                    if (ext != null && ext.getUnderlyingProperties() != null) {
                        putIfNonBlank(props, GraphConstants.META_APPLICATION_NAME,
                                ext.getUnderlyingProperties().getApplication());
                        putIfNonBlank(props, "company",
                                ext.getUnderlyingProperties().getCompany());
                    }
                    // Custom document properties
                    try {
                        POIXMLProperties.CustomProperties custom = xmlProps.getCustomProperties();
                        if (custom != null) {
                            Map<String, String> customMap = new LinkedHashMap<>();
                            var ct = custom.getUnderlyingProperties();
                            if (ct != null) {
                                for (var prop : ct.getPropertyList()) {
                                    String propName = prop.getName();
                                    String propValue = null;
                                    if (prop.isSetLpwstr()) propValue = prop.getLpwstr();
                                    else if (prop.isSetBool()) propValue = String.valueOf(prop.getBool());
                                    else if (prop.isSetI4()) propValue = String.valueOf(prop.getI4());
                                    else if (prop.isSetR8()) propValue = String.valueOf(prop.getR8());
                                    if (propName != null && propValue != null && !propValue.isBlank()) {
                                        customMap.put(propName, propValue.trim());
                                    }
                                }
                            }
                            if (!customMap.isEmpty()) {
                                props.put("office.customProperties", customMap);
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Could not extract custom properties: {}", e.getMessage());
                    }
                }
            } else if (workbook instanceof HSSFWorkbook hssf) {
                // OLE2 format: .xls
                SummaryInformation si = hssf.getSummaryInformation();
                if (si != null) {
                    putIfNonBlank(props, GraphConstants.META_AUTHOR, si.getAuthor());
                    putIfNonBlank(props, GraphConstants.META_TITLE, si.getTitle());
                    putIfNonBlank(props, GraphConstants.META_SUBJECT, si.getSubject());
                    putIfNonBlank(props, GraphConstants.META_KEYWORDS, si.getKeywords());
                    putIfNonBlank(props, GraphConstants.META_DESCRIPTION, si.getComments());
                    putIfNonBlank(props, GraphConstants.META_APPLICATION_NAME, si.getApplicationName());
                    putIfNonBlank(props, "lastModifiedBy", si.getLastAuthor());
                    if (si.getCreateDateTime() != null) {
                        props.put(GraphConstants.META_CREATION_DATE, si.getCreateDateTime().toString());
                    }
                    if (si.getLastSaveDateTime() != null) {
                        props.put(GraphConstants.META_MODIFICATION_DATE, si.getLastSaveDateTime().toString());
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not extract workbook properties: {}", e.getMessage());
        }
        return props;
    }

    private static void putIfNonBlank(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.putIfAbsent(key, value);
        }
    }

    private void addMetadata(Document document, File file, String docType) {
        // Standard loader metadata (used by orchestrator)
        document.getMetadata().put(GraphConstants.META_SOURCE, file.getAbsolutePath());
        document.getMetadata().put(GraphConstants.META_FILE_NAME, file.getName());
        document.getMetadata().put(GraphConstants.META_FILE_SIZE, file.length());
        document.getMetadata().put(GraphConstants.META_LAST_MODIFIED, file.lastModified());
        document.getMetadata().put(GraphConstants.META_DOCUMENT_TYPE, docType);
        document.getMetadata().put(GraphConstants.META_LOADER, getName());

        // Source attribution metadata (used by SourceAttributionHelper / IndexSyncService)
        document.getMetadata().put(GraphConstants.META_SOURCE_PATH, file.getAbsolutePath());
        document.getMetadata().put("source_filename", file.getName());
        document.getMetadata().put(GraphConstants.META_SOURCE_TYPE, "FILE");
        document.getMetadata().put("loader_name", getName());
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            document.getMetadata().put("file_extension", name.substring(dot + 1).toLowerCase());
        }
    }

    private void reportProgress(Consumer<LoaderProgress> callback, String phase, int pct, String message) {
        if (callback != null) {
            callback.accept(new LoaderProgress(phase, pct, phase, message, null));
        }
    }
}
