/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.knowledgegraph.unified;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/** HTTP transport for the portable {@code .kgraph} contract used by CLI and MCP clients. */
@ConditionalOnProperty(prefix = "kompile.graph.http", name = "enabled", havingValue = "true")
@RestController
@RequestMapping(UnifiedGraphIOController.BASE_PATH)
public class UnifiedGraphIOController {

    public static final String BASE_PATH = "/api/graph/unified";

    private final UnifiedGraphBridge bridge;

    public UnifiedGraphIOController(UnifiedGraphBridge bridge) {
        this.bridge = bridge;
    }

    @GetMapping(value = "/export", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> exportGraph(
            @RequestParam(value = "factSheetId", required = false) Long factSheetId) throws IOException {
        byte[] payload = bridge.exportBytes(factSheetId);
        String scope = factSheetId == null ? "global" : factSheetId.toString();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"kompile-graph-" + scope + ".kgraph\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(payload.length)
                .body(payload);
    }

    @PostMapping(
            value = "/import",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> importGraph(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "factSheetId", required = false) Long factSheetId) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "A non-empty .kgraph file is required"));
        }
        try {
            return ResponseEntity.ok(bridge.importBytes(file.getBytes(), factSheetId));
        } catch (IOException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", safeMessage(e)));
        }
    }

    private static String safeMessage(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? "Invalid .kgraph payload"
                : error.getMessage();
    }
}
