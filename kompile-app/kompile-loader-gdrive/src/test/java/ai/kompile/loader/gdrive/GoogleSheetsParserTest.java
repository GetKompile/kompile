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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link GoogleSheetsParser}.
 *
 * <p>Uses a mocked HttpClient to simulate Sheets API v4 responses
 * without needing real Google credentials.
 */
class GoogleSheetsParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testColumnLetterConversion() {
        assertEquals("A", GoogleSheetsParser.columnLetter(0));
        assertEquals("B", GoogleSheetsParser.columnLetter(1));
        assertEquals("Z", GoogleSheetsParser.columnLetter(25));
        assertEquals("AA", GoogleSheetsParser.columnLetter(26));
        assertEquals("AB", GoogleSheetsParser.columnLetter(27));
        assertEquals("AZ", GoogleSheetsParser.columnLetter(51));
        assertEquals("BA", GoogleSheetsParser.columnLetter(52));
    }

    @Test
    void testLoadSpreadsheetSingleSheet() throws Exception {
        // Simulate Sheets API responses
        String metadataJson = """
                {
                  "properties": {"title": "Budget 2025"},
                  "sheets": [
                    {"properties": {"title": "Q1", "sheetId": 0}}
                  ]
                }
                """;

        String valuesJson = """
                {
                  "values": [
                    ["Month", "Revenue", "Expenses"],
                    ["January", "10000", "8000"],
                    ["February", "12000", "9000"]
                  ]
                }
                """;

        String formulasJson = """
                {
                  "values": [
                    ["Month", "Revenue", "Expenses"],
                    ["January", "10000", "8000"],
                    ["February", "12000", "9000"]
                  ]
                }
                """;

        HttpClient mockClient = createMockClient(metadataJson, valuesJson, formulasJson);
        GoogleSheetsParser parser = new GoogleSheetsParser(mockClient, objectMapper);

        Map<String, Object> driveMetadata = new LinkedHashMap<>();
        driveMetadata.put("gdrive_file_name", "Budget 2025");
        driveMetadata.put("gdrive_mime_type", "application/vnd.google-apps.spreadsheet");
        driveMetadata.put("collection_name", "finance");

        List<Document> docs = parser.loadSpreadsheet("spreadsheet123", "token", driveMetadata);

        assertEquals(1, docs.size()); // 1 sheet, no formulas = no formula summary doc
        Document sheetDoc = docs.get(0);

        // Verify table metadata
        assertEquals("table", sheetDoc.getMetadata().get("content_type"));
        assertNotNull(sheetDoc.getMetadata().get("full_table_content"));
        assertEquals(3, sheetDoc.getMetadata().get("table_row_count"));
        assertEquals(3, sheetDoc.getMetadata().get("table_column_count"));
        assertEquals("excel-poi".equals(sheetDoc.getMetadata().get("table_extraction_method"))
                ? "excel-poi" : "google-sheets-api",
                sheetDoc.getMetadata().get("table_extraction_method"));

        // Verify sheet metadata
        assertEquals("Q1", sheetDoc.getMetadata().get("sheetName"));
        assertEquals(0, sheetDoc.getMetadata().get("sheetIndex"));

        // Verify source attribution
        assertEquals("gdrive:spreadsheet123/Q1", sheetDoc.getMetadata().get("source"));
        assertEquals("gdrive:spreadsheet123/Q1", sheetDoc.getMetadata().get("source_path"));
        assertEquals("GDRIVE", sheetDoc.getMetadata().get("source_type"));
        assertEquals("Budget 2025", sheetDoc.getMetadata().get("source_filename"));

        // Verify drive metadata was copied
        assertEquals("finance", sheetDoc.getMetadata().get("collection_name"));
    }

    @Test
    void testLoadSpreadsheetWithFormulas() throws Exception {
        String metadataJson = """
                {
                  "properties": {"title": "Calcs"},
                  "sheets": [
                    {"properties": {"title": "Data", "sheetId": 0}}
                  ]
                }
                """;

        String valuesJson = """
                {
                  "values": [
                    ["A", "B", "Sum"],
                    ["10", "20", "30"]
                  ]
                }
                """;

        String formulasJson = """
                {
                  "values": [
                    ["A", "B", "Sum"],
                    ["10", "20", "=A2+B2"]
                  ]
                }
                """;

        HttpClient mockClient = createMockClient(metadataJson, valuesJson, formulasJson);
        GoogleSheetsParser parser = new GoogleSheetsParser(mockClient, objectMapper);

        List<Document> docs = parser.loadSpreadsheet("calc123", "token", Map.of());

        // 1 sheet doc + 1 formula summary doc
        assertEquals(2, docs.size());

        Document sheetDoc = docs.get(0);
        assertEquals("table", sheetDoc.getMetadata().get("content_type"));
        assertNotNull(sheetDoc.getMetadata().get("formulas"));
        assertEquals(1, sheetDoc.getMetadata().get("formulaCount"));

        Document formulaDoc = docs.get(1);
        assertEquals("formula_graph", formulaDoc.getMetadata().get("content_type"));
        assertEquals(1, formulaDoc.getMetadata().get("totalFormulas"));
        assertTrue(formulaDoc.getText().contains("Formula Summary"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void testLoadSpreadsheetMultipleSheets() throws Exception {
        String metadataJson = """
                {
                  "properties": {"title": "Multi"},
                  "sheets": [
                    {"properties": {"title": "Sheet1", "sheetId": 0}},
                    {"properties": {"title": "Sheet2", "sheetId": 1}}
                  ]
                }
                """;

        String valuesJson1 = """
                {"values": [["Col1"], ["Val1"]]}
                """;
        String formulasJson1 = """
                {"values": [["Col1"], ["Val1"]]}
                """;
        String valuesJson2 = """
                {"values": [["ColA", "ColB"], ["X", "Y"]]}
                """;
        String formulasJson2 = """
                {"values": [["ColA", "ColB"], ["X", "Y"]]}
                """;

        // Create mock that returns different responses for sequential calls
        HttpClient mockClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> metaResp = mock(HttpResponse.class);
        when(metaResp.statusCode()).thenReturn(200);
        when(metaResp.body()).thenReturn(metadataJson);

        @SuppressWarnings("unchecked")
        HttpResponse<String> vals1 = mock(HttpResponse.class);
        when(vals1.statusCode()).thenReturn(200);
        when(vals1.body()).thenReturn(valuesJson1);

        @SuppressWarnings("unchecked")
        HttpResponse<String> forms1 = mock(HttpResponse.class);
        when(forms1.statusCode()).thenReturn(200);
        when(forms1.body()).thenReturn(formulasJson1);

        @SuppressWarnings("unchecked")
        HttpResponse<String> vals2 = mock(HttpResponse.class);
        when(vals2.statusCode()).thenReturn(200);
        when(vals2.body()).thenReturn(valuesJson2);

        @SuppressWarnings("unchecked")
        HttpResponse<String> forms2 = mock(HttpResponse.class);
        when(forms2.statusCode()).thenReturn(200);
        when(forms2.body()).thenReturn(formulasJson2);

        when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(metaResp)   // metadata
                .thenReturn(vals1)      // Sheet1 values
                .thenReturn(forms1)     // Sheet1 formulas
                .thenReturn(vals2)      // Sheet2 values
                .thenReturn(forms2);    // Sheet2 formulas

        GoogleSheetsParser parser = new GoogleSheetsParser(mockClient, objectMapper);
        List<Document> docs = parser.loadSpreadsheet("multi123", "token", Map.of());

        assertEquals(2, docs.size());
        assertEquals("Sheet1", docs.get(0).getMetadata().get("sheetName"));
        assertEquals("Sheet2", docs.get(1).getMetadata().get("sheetName"));
        assertEquals(0, docs.get(0).getMetadata().get("sheetIndex"));
        assertEquals(1, docs.get(1).getMetadata().get("sheetIndex"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void testLoadSpreadsheetApiFailure() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> failResp = mock(HttpResponse.class);
        when(failResp.statusCode()).thenReturn(403);
        when(failResp.body()).thenReturn("{\"error\": \"forbidden\"}");

        when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(failResp);

        GoogleSheetsParser parser = new GoogleSheetsParser(mockClient, objectMapper);
        List<Document> docs = parser.loadSpreadsheet("bad123", "token", Map.of());

        assertTrue(docs.isEmpty());
    }

    @Test
    void testMarkdownTableContent() throws Exception {
        String metadataJson = """
                {
                  "properties": {"title": "Test"},
                  "sheets": [{"properties": {"title": "S1", "sheetId": 0}}]
                }
                """;

        String valuesJson = """
                {
                  "values": [
                    ["Name", "Value"],
                    ["Alpha", "100"],
                    ["Beta", "200"]
                  ]
                }
                """;

        HttpClient mockClient = createMockClient(metadataJson, valuesJson, valuesJson);
        GoogleSheetsParser parser = new GoogleSheetsParser(mockClient, objectMapper);
        List<Document> docs = parser.loadSpreadsheet("md123", "token", Map.of());

        assertEquals(1, docs.size());
        String tableContent = (String) docs.get(0).getMetadata().get("full_table_content");
        assertNotNull(tableContent);

        // Should contain markdown table separators
        assertTrue(tableContent.contains("|---"));
        // Should contain cell values
        assertTrue(tableContent.contains("Alpha"));
        assertTrue(tableContent.contains("200"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void testEmptySpreadsheet() throws Exception {
        String metadataJson = """
                {
                  "properties": {"title": "Empty"},
                  "sheets": []
                }
                """;

        HttpClient mockClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn(metadataJson);
        when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(resp);

        GoogleSheetsParser parser = new GoogleSheetsParser(mockClient, objectMapper);
        List<Document> docs = parser.loadSpreadsheet("empty123", "token", Map.of());

        assertTrue(docs.isEmpty());
    }

    /**
     * Creates a mock HttpClient that returns the given responses in order:
     * metadata, then values, then formulas for a single-sheet spreadsheet.
     */
    @SuppressWarnings("unchecked")
    private HttpClient createMockClient(String metadataJson, String valuesJson,
                                         String formulasJson) throws Exception {
        HttpClient mockClient = mock(HttpClient.class);

        HttpResponse<String> metaResp = mock(HttpResponse.class);
        when(metaResp.statusCode()).thenReturn(200);
        when(metaResp.body()).thenReturn(metadataJson);

        HttpResponse<String> valsResp = mock(HttpResponse.class);
        when(valsResp.statusCode()).thenReturn(200);
        when(valsResp.body()).thenReturn(valuesJson);

        HttpResponse<String> formsResp = mock(HttpResponse.class);
        when(formsResp.statusCode()).thenReturn(200);
        when(formsResp.body()).thenReturn(formulasJson);

        when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(metaResp)
                .thenReturn(valsResp)
                .thenReturn(formsResp);

        return mockClient;
    }
}
