/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.io.controller;

import ai.kompile.knowledgegraph.io.GraphIOService;
import ai.kompile.knowledgegraph.io.model.ExportResult;
import ai.kompile.knowledgegraph.io.model.ImportResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/graph/io")
public class GraphIOController {

    private final GraphIOService ioService;

    public GraphIOController(GraphIOService ioService) {
        this.ioService = ioService;
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportResult> importGraph(
            @RequestParam("format") String format,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "edgesFile", required = false) MultipartFile edgesFile,
            @RequestParam(value = "factSheetId", required = false) Long factSheetId) throws Exception {
        byte[] payload = file.getBytes();
        byte[] secondary = edgesFile != null ? edgesFile.getBytes() : null;
        return ResponseEntity.ok(ioService.importGraph(format, payload, secondary));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportGraph(
            @RequestParam("format") String format,
            @RequestParam(value = "factSheetId", required = false) Long factSheetId) throws Exception {
        ExportResult result = ioService.exportGraph(format, factSheetId);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + result.suggestedFilename() + "\"");
        headers.set(HttpHeaders.CONTENT_TYPE, result.contentType());
        return new ResponseEntity<>(result.data(), headers, 200);
    }
}
