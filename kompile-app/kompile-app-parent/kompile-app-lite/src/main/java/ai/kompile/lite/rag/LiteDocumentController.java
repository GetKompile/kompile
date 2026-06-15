/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.lite.rag;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Document upload and management endpoints for Kompile Lite.
 */
@RestController
@RequestMapping("/api/lite/documents")
public class LiteDocumentController {

    @Autowired
    private LiteRagService ragService;

    /**
     * Upload and ingest a file.
     */
    @PostMapping("/upload")
    public ResponseEntity<LiteRagService.IngestResult> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            var result = ragService.ingestFile(file);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(new LiteRagService.IngestResult(
                            file.getOriginalFilename(), 0, 0, "Error: " + e.getMessage()));
        }
    }

    /**
     * Ingest raw text.
     */
    @PostMapping("/text")
    public ResponseEntity<LiteRagService.IngestResult> ingestText(@RequestBody TextIngestRequest request) {
        var result = ragService.ingestText(request.text(), request.sourceName());
        return ResponseEntity.ok(result);
    }

    /**
     * List indexed documents.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listDocuments(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        var docs = ragService.listDocuments(offset, limit);
        long total = ragService.getDocumentCount();
        return ResponseEntity.ok(Map.of(
                "documents", docs,
                "total", total,
                "offset", offset,
                "limit", limit
        ));
    }

    /**
     * Delete documents by IDs.
     */
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> deleteDocuments(@RequestBody List<String> ids) {
        boolean success = ragService.deleteDocuments(ids);
        return ResponseEntity.ok(Map.of("deleted", success, "ids", ids));
    }

    /**
     * Delete all documents.
     */
    @DeleteMapping("/all")
    public ResponseEntity<Map<String, Object>> deleteAll() {
        boolean success = ragService.deleteAll();
        return ResponseEntity.ok(Map.of("deleted", success));
    }

    /**
     * Trigger graph construction.
     */
    @PostMapping("/graph/build")
    public ResponseEntity<Map<String, String>> buildGraph() {
        String result = ragService.buildGraph();
        return ResponseEntity.ok(Map.of("result", result));
    }

    public record TextIngestRequest(String text, String sourceName) {}
}
