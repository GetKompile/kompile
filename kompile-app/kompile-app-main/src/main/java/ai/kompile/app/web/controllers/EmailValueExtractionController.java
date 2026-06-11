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

package ai.kompile.app.web.controllers;

import ai.kompile.loader.email.inbox.EmailBodyValueExtractor;
import ai.kompile.loader.email.inbox.EmailBodyValueExtractor.ExtractedValue;
import ai.kompile.loader.email.inbox.EmailValueCellMapper;
import ai.kompile.loader.email.inbox.EmailValueCellMapper.CellMapping;
import ai.kompile.loader.excel.graph.SpreadsheetGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST endpoints for extracting structured values from email body text
 * and mapping them to Excel spreadsheet cells.
 */
@RestController
@RequestMapping("/api/email/extract-values")
public class EmailValueExtractionController {

    private final ObjectMapper objectMapper;
    private final EmailBodyValueExtractor valueExtractor = new EmailBodyValueExtractor();
    private final EmailValueCellMapper cellMapper = new EmailValueCellMapper();

    public EmailValueExtractionController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Extract structured values from email body text.
     *
     * @param request contains "text" (email body), optional "metadata" (email headers)
     * @return list of extracted values with type, parsed value, context, and confidence
     */
    @PostMapping("/extract")
    public ResponseEntity<Map<String, Object>> extractValues(@RequestBody Map<String, Object> request) {
        String text = request.get("text") instanceof String s ? s : null;
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Request must include 'text' field"));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = request.get("metadata") instanceof Map m ? m : new HashMap<>();
        Document doc = new Document(text, metadata);

        List<ExtractedValue> values = valueExtractor.extract(doc);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", values.size());
        response.put("values", values.stream().map(ExtractedValue::toMap).collect(Collectors.toList()));
        return ResponseEntity.ok(response);
    }

    /**
     * Extract values from email text and map them to cells in a SpreadsheetGraph.
     *
     * @param request contains "text" (email body), "spreadsheetGraphJson" (SpreadsheetGraph JSON),
     *                optional "metadata" (email headers), optional "confidenceThreshold" (default 0.5)
     * @return list of suggested cell mappings
     */
    @PostMapping("/map-to-cells")
    public ResponseEntity<Map<String, Object>> mapToCells(@RequestBody Map<String, Object> request) {
        String text = request.get("text") instanceof String s ? s : null;
        String graphJson = request.get("spreadsheetGraphJson") instanceof String s ? s : null;

        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Request must include 'text' field"));
        }
        if (graphJson == null || graphJson.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Request must include 'spreadsheetGraphJson' field"));
        }

        double threshold = 0.5;
        if (request.get("confidenceThreshold") instanceof Number n) {
            threshold = n.doubleValue();
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = request.get("metadata") instanceof Map m ? m : new HashMap<>();
            Document doc = new Document(text, metadata);

            SpreadsheetGraph graph = objectMapper.readValue(graphJson, SpreadsheetGraph.class);
            List<ExtractedValue> values = valueExtractor.extract(doc);
            List<CellMapping> mappings = cellMapper.mapValues(values, graph);

            // Filter by confidence threshold
            double finalThreshold = threshold;
            List<CellMapping> filtered = mappings.stream()
                    .filter(m -> m.confidence >= finalThreshold)
                    .collect(Collectors.toList());

            // Build cell overrides map for direct use with Excel execution
            Map<String, Object> cellOverrides = new LinkedHashMap<>();
            for (CellMapping m : filtered) {
                String sanitizedRef = m.cellReference.replace("!", "_").replace(":", "_");
                cellOverrides.put(sanitizedRef, m.extractedValue.parsedValue);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("extractedValueCount", values.size());
            response.put("mappingCount", filtered.size());
            response.put("mappings", filtered.stream().map(CellMapping::toMap).collect(Collectors.toList()));
            response.put("cellOverrides", cellOverrides);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Failed to process: " + e.getMessage()));
        }
    }
}
