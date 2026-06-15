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

package ai.kompile.loader.gdrive;

import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.table.TableCellGraphBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Google Sheets spreadsheets via the Sheets API v4 to produce
 * per-sheet table documents with formula extraction.
 *
 * <p>Uses raw HTTP against {@code sheets.googleapis.com} — no Google client
 * library dependency — consistent with the project's existing approach in
 * {@link GoogleDriveLoaderImpl}.
 *
 * <p>For each sheet, produces a Document with:
 * <ul>
 *   <li>{@code content_type = "table"} for RAG compatibility</li>
 *   <li>{@code full_table_content} — markdown table of cell values</li>
 *   <li>{@code formulas} — semicolon-separated list of formulas</li>
 *   <li>Standard table metadata (table_row_count, table_column_count, etc.)</li>
 *   <li>Google Sheets metadata (gdrive_file_id, gdrive_sheet_id, etc.)</li>
 * </ul>
 */
public class GoogleSheetsParser {

    private static final Logger logger = LoggerFactory.getLogger(GoogleSheetsParser.class);

    private static final String SHEETS_API_BASE = "https://sheets.googleapis.com/v4/spreadsheets";

    /** Pattern matching cell references in Google Sheets formulas. */
    private static final Pattern CELL_REF_PATTERN = Pattern.compile(
            "(?:'([^']+)'!)?([A-Z]{1,3}\\d+)(?::([A-Z]{1,3}\\d+))?");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GoogleSheetsParser(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Load a Google Sheets spreadsheet as a list of table documents, one per sheet.
     *
     * @param spreadsheetId the Google Drive file ID
     * @param accessToken   valid OAuth2 access token with spreadsheets.readonly scope
     * @param driveMetadata metadata from the Drive API (name, webViewLink, etc.)
     * @return list of Documents, one per sheet plus an optional formula summary
     */
    public List<Document> loadSpreadsheet(String spreadsheetId, String accessToken,
                                           Map<String, Object> driveMetadata) throws Exception {
        // Fetch spreadsheet with both display values and formulas
        JsonNode spreadsheet = fetchSpreadsheet(spreadsheetId, accessToken);
        if (spreadsheet == null) {
            return List.of();
        }

        String title = spreadsheet.path("properties").path("title").asText(spreadsheetId);
        JsonNode sheets = spreadsheet.path("sheets");
        if (!sheets.isArray() || sheets.isEmpty()) {
            return List.of();
        }

        List<Document> documents = new ArrayList<>();
        List<String> allFormulas = new ArrayList<>();
        int sheetIndex = 0;

        for (JsonNode sheet : sheets) {
            String sheetTitle = sheet.path("properties").path("title").asText("Sheet" + (sheetIndex + 1));
            int sheetId = sheet.path("properties").path("sheetId").asInt(-1);

            // Fetch values and formulas for this sheet
            JsonNode values = fetchSheetValues(spreadsheetId, sheetTitle, accessToken, false);
            JsonNode formulas = fetchSheetValues(spreadsheetId, sheetTitle, accessToken, true);

            Document sheetDoc = buildSheetDocument(
                    sheetTitle, sheetId, sheetIndex, values, formulas,
                    title, spreadsheetId, driveMetadata, allFormulas);

            if (sheetDoc != null) {
                documents.add(sheetDoc);
            }
            sheetIndex++;
        }

        // Extract chart metadata from sheets (sheets.charts field)
        List<Map<String, String>> chartsMeta = new ArrayList<>();
        for (JsonNode sheet : sheets) {
            String sheetTitle2 = sheet.path("properties").path("title").asText("");
            JsonNode chartsNode = sheet.path("charts");
            if (chartsNode.isArray()) {
                for (JsonNode chart : chartsNode) {
                    Map<String, String> cm = new LinkedHashMap<>();
                    cm.put("sheet", sheetTitle2);
                    String chartTitle = chart.path("title").asText(null);
                    if (chartTitle != null && !chartTitle.isEmpty()) cm.put("title", chartTitle);
                    String chartType = chart.path("basicChart").path("chartType").asText(null);
                    if (chartType != null) cm.put("chartType", chartType);
                    int chartId = chart.path("chartId").asInt(-1);
                    if (chartId >= 0) cm.put("chartId", String.valueOf(chartId));
                    chartsMeta.add(cm);
                }
            }
        }
        // Attach chart metadata to the first document
        if (!chartsMeta.isEmpty() && !documents.isEmpty()) {
            documents.get(0).getMetadata().put("gsheet.charts", chartsMeta);
            documents.get(0).getMetadata().put("gsheet.chartCount", chartsMeta.size());
        }

        // Extract named ranges
        JsonNode namedRanges = spreadsheet.path("namedRanges");
        if (namedRanges.isArray() && !namedRanges.isEmpty()) {
            List<Map<String, String>> namedRangesMeta = new ArrayList<>();
            for (JsonNode nr : namedRanges) {
                Map<String, String> nrm = new LinkedHashMap<>();
                nrm.put("name", nr.path("name").asText(""));
                String namedRangeId = nr.path("namedRangeId").asText(null);
                if (namedRangeId != null) nrm.put("namedRangeId", namedRangeId);
                JsonNode range = nr.path("range");
                if (!range.isMissingNode()) {
                    int nrSheetId = range.path("sheetId").asInt(-1);
                    int startRow = range.path("startRowIndex").asInt(-1);
                    int endRow = range.path("endRowIndex").asInt(-1);
                    int startCol = range.path("startColumnIndex").asInt(-1);
                    int endCol = range.path("endColumnIndex").asInt(-1);
                    if (nrSheetId >= 0) nrm.put("sheetId", String.valueOf(nrSheetId));
                    if (startRow >= 0 && endRow >= 0) nrm.put("rowRange", startRow + ":" + endRow);
                    if (startCol >= 0 && endCol >= 0) nrm.put("colRange", startCol + ":" + endCol);
                }
                namedRangesMeta.add(nrm);
            }
            if (!documents.isEmpty()) {
                documents.get(0).getMetadata().put("gsheet.namedRanges", namedRangesMeta);
                documents.get(0).getMetadata().put("gsheet.namedRangeCount", namedRangesMeta.size());
            }
        }

        // Add formula summary document if there are formulas
        if (!allFormulas.isEmpty()) {
            Document formulaDoc = buildFormulaSummaryDocument(
                    title, spreadsheetId, allFormulas, sheetIndex, driveMetadata);
            documents.add(formulaDoc);
        }

        logger.info("Loaded Google Sheet '{}': {} sheets, {} formulas",
                title, sheetIndex, allFormulas.size());

        return documents;
    }

    /**
     * Fetch the spreadsheet metadata (sheets list, properties).
     */
    private JsonNode fetchSpreadsheet(String spreadsheetId, String accessToken) throws Exception {
        String url = SHEETS_API_BASE + "/" + URLEncoder.encode(spreadsheetId, StandardCharsets.UTF_8)
                + "?fields=" + URLEncoder.encode("properties.title,sheets.properties,sheets.charts,namedRanges", StandardCharsets.UTF_8);

        HttpResponse<String> response = sendGet(url, accessToken);
        if (response.statusCode() / 100 != 2) {
            logger.warn("Sheets API metadata fetch failed for {}: HTTP {}", spreadsheetId, response.statusCode());
            return null;
        }
        return objectMapper.readTree(response.body());
    }

    /**
     * Fetch cell values for a single sheet.
     *
     * @param returnFormulas if true, fetches formulas instead of display values
     */
    private JsonNode fetchSheetValues(String spreadsheetId, String sheetTitle,
                                       String accessToken, boolean returnFormulas) throws Exception {
        String range = URLEncoder.encode("'" + sheetTitle + "'", StandardCharsets.UTF_8);
        String renderOption = returnFormulas ? "FORMULA" : "FORMATTED_VALUE";
        String url = SHEETS_API_BASE + "/" + URLEncoder.encode(spreadsheetId, StandardCharsets.UTF_8)
                + "/values/" + range
                + "?valueRenderOption=" + renderOption
                + "&majorDimension=ROWS";

        HttpResponse<String> response = sendGet(url, accessToken);
        if (response.statusCode() / 100 != 2) {
            logger.debug("Sheets API values fetch failed for sheet '{}': HTTP {}", sheetTitle, response.statusCode());
            return null;
        }
        return objectMapper.readTree(response.body());
    }

    private HttpResponse<String> sendGet(String url, String accessToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Build a table Document for a single sheet.
     */
    private Document buildSheetDocument(String sheetTitle, int sheetId, int sheetIndex,
                                         JsonNode valuesResponse, JsonNode formulasResponse,
                                         String spreadsheetTitle, String spreadsheetId,
                                         Map<String, Object> driveMetadata,
                                         List<String> allFormulas) {
        JsonNode valueRows = valuesResponse != null ? valuesResponse.path("values") : null;
        if (valueRows == null || !valueRows.isArray() || valueRows.isEmpty()) {
            return null;
        }

        // Parse formulas
        JsonNode formulaRows = formulasResponse != null ? formulasResponse.path("values") : null;
        List<String> sheetFormulas = new ArrayList<>();
        if (formulaRows != null && formulaRows.isArray()) {
            for (int r = 0; r < formulaRows.size(); r++) {
                JsonNode row = formulaRows.get(r);
                if (row == null || !row.isArray()) continue;
                for (int c = 0; c < row.size(); c++) {
                    String cellValue = row.get(c).asText("");
                    if (cellValue.startsWith("=")) {
                        String colLetter = columnLetter(c);
                        String ref = sheetTitle + "!" + colLetter + (r + 1);
                        sheetFormulas.add(ref + cellValue);
                        allFormulas.add(ref + cellValue);
                    }
                }
            }
        }

        // Determine dimensions
        int maxCols = 0;
        for (JsonNode row : valueRows) {
            if (row.isArray() && row.size() > maxCols) {
                maxCols = row.size();
            }
        }
        int rowCount = valueRows.size();

        // Build markdown table
        StringBuilder markdown = new StringBuilder();
        List<String> headers = new ArrayList<>();
        boolean firstRow = true;

        for (JsonNode row : valueRows) {
            boolean hasContent = false;
            StringBuilder rowMd = new StringBuilder("|");

            for (int c = 0; c < maxCols; c++) {
                String value = (row.isArray() && c < row.size()) ? row.get(c).asText("") : "";
                if (!value.trim().isEmpty()) hasContent = true;
                String escaped = value.replace("|", "\\|").replace("\n", " ");
                rowMd.append(" ").append(escaped).append(" |");
            }

            if (!hasContent) continue;

            if (firstRow) {
                for (int c = 0; c < maxCols; c++) {
                    headers.add((row.isArray() && c < row.size()) ? row.get(c).asText("") : "");
                }
                markdown.append(rowMd).append("\n|");
                for (int c = 0; c < maxCols; c++) {
                    markdown.append("---|");
                }
                markdown.append("\n");
                firstRow = false;
            } else {
                markdown.append(rowMd).append("\n");
            }
        }

        if (markdown.length() == 0) return null;

        // Build summary for embedding
        String summary = String.format("Google Sheet '%s' > '%s' with %d rows and %d columns. Columns: %s",
                spreadsheetTitle, sheetTitle, rowCount, maxCols,
                String.join(", ", headers.subList(0, Math.min(headers.size(), 20))));

        Document doc = new Document(summary);
        Map<String, Object> meta = doc.getMetadata();

        // Standard table metadata (RagToolImpl compatibility)
        meta.put(GraphConstants.META_CONTENT_TYPE, "table");
        meta.put("full_table_content", markdown.toString());
        meta.put("table_summary", summary);
        meta.put(GraphConstants.META_TABLE_ROW_COUNT, rowCount);
        meta.put(GraphConstants.META_TABLE_COLUMN_COUNT, maxCols);
        meta.put(GraphConstants.META_TABLE_INDEX, sheetIndex);
        meta.put("table_extraction_method", "google-sheets-api");
        if (!headers.isEmpty()) {
            meta.put(GraphConstants.META_TABLE_HEADERS, String.join(",", headers));
        }

        // Sheet-specific metadata
        meta.put(GraphConstants.META_SHEET_NAME, sheetTitle);
        meta.put(GraphConstants.META_SHEET_INDEX, sheetIndex);
        meta.put(GraphConstants.META_SHEET_ID, sheetId);

        // Formula metadata
        if (!sheetFormulas.isEmpty()) {
            meta.put(GraphConstants.META_FORMULAS, String.join("; ", sheetFormulas));
            meta.put(GraphConstants.META_FORMULA_COUNT, sheetFormulas.size());
        }

        // Source attribution
        String sourcePath = "gdrive:" + spreadsheetId + "/" + sheetTitle;
        meta.put(GraphConstants.META_SOURCE, sourcePath);
        meta.put(GraphConstants.META_SOURCE_PATH, sourcePath);
        meta.put(GraphConstants.META_SOURCE_TYPE, "GDRIVE");
        meta.put("source_filename", spreadsheetTitle);
        meta.put(GraphConstants.META_FILE_NAME, spreadsheetTitle + " - " + sheetTitle);
        meta.put("loader_name", "Google Drive Loader");
        meta.put(GraphConstants.META_LOADER, "Google Drive Loader");
        meta.put(GraphConstants.META_DOCUMENT_TYPE, "Google Sheets Spreadsheet");
        meta.put("gdrive_file_id", spreadsheetId);
        meta.put("gdrive_sheet_id", sheetId);

        // Copy drive metadata (including owner/modifier/permissions for graph extraction)
        if (driveMetadata != null) {
            for (String key : List.of("gdrive_file_name", "gdrive_mime_type", "gdrive_modified_time",
                    "gdrive_created_time", "gdrive_web_view_link", "gdrive_size_bytes",
                    "gdrive_owner_emails", "gdrive_owner_names",
                    "gdrive_last_modifier_email", "gdrive_last_modifier_name",
                    "gdrive_permissions",
                    "collection_name", GraphConstants.META_SOURCE_ID)) {
                if (driveMetadata.containsKey(key)) {
                    meta.put(key, driveMetadata.get(key));
                }
            }
        }

        // Build cell-level graph for knowledge graph integration
        TableCellGraphBuilder graphBuilder = new TableCellGraphBuilder()
                .namespace("gsheet:" + spreadsheetId + "/" + sheetTitle)
                .tableName(sheetTitle)
                .headers(headers);
        for (JsonNode row : valueRows) {
            List<String> vals = new ArrayList<>();
            for (int c = 0; c < maxCols; c++) {
                vals.add(row.isArray() && c < row.size() ? row.get(c).asText("") : "");
            }
            graphBuilder.addRow(vals);
        }
        Graph cellGraph = graphBuilder.build();
        if (cellGraph.getEntities() != null && !cellGraph.getEntities().isEmpty()) {
            meta.put(GraphConstants.META_TABLE_GRAPH, TableCellGraphBuilder.toJson(cellGraph));
        }

        return doc;
    }

    /**
     * Build a summary document for all formulas across all sheets.
     */
    private Document buildFormulaSummaryDocument(String spreadsheetTitle, String spreadsheetId,
                                                  List<String> allFormulas, int sheetCount,
                                                  Map<String, Object> driveMetadata) {
        StringBuilder sb = new StringBuilder();
        sb.append("Google Sheet Formula Summary: ").append(spreadsheetTitle).append("\n");
        sb.append("Sheets: ").append(sheetCount)
                .append(", Formula cells: ").append(allFormulas.size()).append("\n\n");

        // Group by sheet
        Map<String, List<String>> bySheet = new LinkedHashMap<>();
        for (String formula : allFormulas) {
            int bang = formula.indexOf('!');
            String sheet = bang > 0 ? formula.substring(0, bang) : "Unknown";
            bySheet.computeIfAbsent(sheet, k -> new ArrayList<>()).add(formula);
        }

        for (Map.Entry<String, List<String>> entry : bySheet.entrySet()) {
            sb.append("Sheet '").append(entry.getKey()).append("' formulas:\n");
            for (String f : entry.getValue()) {
                sb.append("  ").append(f).append("\n");
            }
        }

        Document doc = new Document(sb.toString());
        Map<String, Object> meta = doc.getMetadata();
        meta.put(GraphConstants.META_CONTENT_TYPE, GraphConstants.CONTENT_TYPE_FORMULA_GRAPH);
        meta.put("totalFormulas", allFormulas.size());
        meta.put("sheetCount", sheetCount);
        String sourcePath = "gdrive:" + spreadsheetId + "/formulas";
        meta.put(GraphConstants.META_SOURCE, sourcePath);
        meta.put(GraphConstants.META_SOURCE_PATH, sourcePath);
        meta.put(GraphConstants.META_SOURCE_TYPE, "GDRIVE");
        meta.put("source_filename", spreadsheetTitle);
        meta.put(GraphConstants.META_FILE_NAME, spreadsheetTitle + " - Formulas");
        meta.put("loader_name", "Google Drive Loader");
        meta.put(GraphConstants.META_LOADER, "Google Drive Loader");
        meta.put(GraphConstants.META_DOCUMENT_TYPE, "Google Sheets Formula Graph");
        meta.put("gdrive_file_id", spreadsheetId);

        if (driveMetadata != null) {
            for (String key : List.of("gdrive_file_name", "gdrive_mime_type", "gdrive_modified_time",
                    "gdrive_created_time", "gdrive_web_view_link", "gdrive_size_bytes",
                    "gdrive_owner_emails", "gdrive_owner_names",
                    "gdrive_last_modifier_email", "gdrive_last_modifier_name",
                    "gdrive_permissions",
                    "collection_name", GraphConstants.META_SOURCE_ID)) {
                if (driveMetadata.containsKey(key)) {
                    meta.put(key, driveMetadata.get(key));
                }
            }
        }

        return doc;
    }

    /** Convert 0-based column index to letter(s): 0=A, 25=Z, 26=AA, etc. */
    static String columnLetter(int col) {
        StringBuilder sb = new StringBuilder();
        col++;
        while (col > 0) {
            col--;
            sb.insert(0, (char) ('A' + col % 26));
            col /= 26;
        }
        return sb.toString();
    }
}
