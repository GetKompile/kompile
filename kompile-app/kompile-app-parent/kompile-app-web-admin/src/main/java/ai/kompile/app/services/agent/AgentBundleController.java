/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.app.services.agent;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** REST surface for importing bundles, starting runs, and observing run activity. */
@RestController
@RequestMapping("/api/agent-bundles")
public class AgentBundleController {

    private final AgentBundleRunManager manager;

    public AgentBundleController(AgentBundleRunManager manager) {
        this.manager = manager;
    }

    @GetMapping
    public List<AgentBundleRunManager.BundleSummary> bundles() {
        return manager.listBundles();
    }

    @GetMapping("/{bundleId}")
    public ResponseEntity<?> bundle(@PathVariable String bundleId) {
        return manager.getBundle(bundleId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{bundleId}/tools")
    public ResponseEntity<?> tools(@PathVariable String bundleId) {
        if (manager.getBundle(bundleId).isEmpty()) return ResponseEntity.notFound().build();
        try {
            return ResponseEntity.ok(manager.discoverTools(bundleId));
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", String.valueOf(e.getMessage())));
        }
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importBundle(@RequestPart("bundle") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "bundle upload is required"));
        }
        try {
            AgentBundleRunManager.BundleSummary summary = manager.importBundle(
                    file.getInputStream(), file.getOriginalFilename());
            return ResponseEntity.status(HttpStatus.CREATED).body(summary);
        } catch (IOException | IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of(
                    "error", String.valueOf(e.getMessage())));
        }
    }

    @DeleteMapping("/{bundleId}")
    public ResponseEntity<?> deleteBundle(@PathVariable String bundleId) {
        try {
            if (!manager.deleteBundle(bundleId)) return ResponseEntity.notFound().build();
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", String.valueOf(e.getMessage())));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", String.valueOf(e.getMessage())));
        }
    }

    @PostMapping("/{bundleId}/runs")
    public ResponseEntity<?> startRun(@PathVariable String bundleId,
                                      @RequestBody RunRequest request) {
        try {
            AgentBundleRunManager.RunSummary summary = manager.startRun(
                    bundleId,
                    request == null ? null : request.prompt(),
                    request == null ? 0 : request.timeoutSeconds());
            return ResponseEntity.accepted().body(summary);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @GetMapping("/runs")
    public List<AgentBundleRunManager.RunSummary> runs() {
        return manager.listRuns();
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<?> run(@PathVariable String runId) {
        return manager.getRun(runId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/runs/{runId}/events")
    public ResponseEntity<?> events(@PathVariable String runId,
                                    @RequestParam(defaultValue = "0") long after) {
        try {
            if (manager.getRun(runId).isEmpty()) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(manager.events(runId, Math.max(0, after)));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @GetMapping(value = "/runs/{runId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<?> eventStream(@PathVariable String runId,
                                         @RequestParam(defaultValue = "0") long after) throws IOException {
        SseEmitter emitter = manager.streamEvents(runId, Math.max(0, after));
        if (emitter == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(emitter);
    }

    @PostMapping("/runs/{runId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String runId) {
        return manager.cancelRun(runId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record RunRequest(String prompt, long timeoutSeconds) { }
}
