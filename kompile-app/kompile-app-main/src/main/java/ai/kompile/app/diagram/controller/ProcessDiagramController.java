/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.diagram.controller;

import ai.kompile.app.diagram.domain.DiagramSession;
import ai.kompile.app.diagram.service.DiagramGenerationService;
import ai.kompile.process.workflow.ProcessDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * REST controller for business process diagram generation.
 * Provides endpoints for:
 * - Starting diagram generation via agent (SSE streaming)
 * - Finalizing sessions with transcript/diagram data
 * - CRUD operations on diagram sessions
 */
@RestController
@RequestMapping("/api/process/diagrams")
public class ProcessDiagramController {

    private static final Logger log = LoggerFactory.getLogger(ProcessDiagramController.class);
    private static final long SSE_TIMEOUT = TimeUnit.MINUTES.toMillis(10);

    private final DiagramGenerationService diagramService;

    public ProcessDiagramController(DiagramGenerationService diagramService) {
        this.diagramService = diagramService;
    }

    /**
     * Start a new diagram generation session.
     * Returns an SSE stream that relays agent events in real-time.
     * The first event is a "session_created" event with the session ID.
     */
    @PostMapping(value = "/generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateDiagram(@RequestBody DiagramGenerateRequest request) {
        log.info("Starting diagram generation: agent={}, factSheet={}, prompt length={}",
                request.agentName, request.factSheetId,
                request.prompt != null ? request.prompt.length() : 0);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        emitter.onCompletion(() -> log.debug("Diagram SSE connection completed"));
        emitter.onTimeout(() -> {
            log.warn("Diagram SSE connection timed out");
            emitter.complete();
        });

        DiagramSession session = diagramService.startGeneration(
                request.prompt, request.agentName, request.factSheetId, emitter);

        try {
            Map<String, Object> sessionEvent = new LinkedHashMap<>();
            sessionEvent.put("sessionId", session.getId());
            sessionEvent.put("status", session.getStatus());
            emitter.send(SseEmitter.event().name("session_created").data(sessionEvent));
        } catch (Exception e) {
            log.warn("Failed to send session_created event: {}", e.getMessage());
        }

        return emitter;
    }

    /**
     * Finalize a diagram session after generation completes.
     * The client sends back the captured transcript, extracted mermaid code, and metadata.
     */
    @PostMapping("/{sessionId}/finalize")
    public ResponseEntity<DiagramSession> finalizeSession(
            @PathVariable Long sessionId,
            @RequestBody DiagramFinalizeRequest request) {
        return diagramService.finalizeSession(
                sessionId,
                request.transcriptJson,
                request.mermaidCode,
                request.title,
                request.description,
                request.sourcesJson
        ).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Mark a session as failed.
     */
    @PostMapping("/{sessionId}/fail")
    public ResponseEntity<DiagramSession> failSession(
            @PathVariable Long sessionId,
            @RequestBody Map<String, String> body) {
        String error = body.getOrDefault("error", "Unknown error");
        return diagramService.failSession(sessionId, error)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * List diagram sessions, optionally filtered by fact sheet.
     */
    @GetMapping
    public ResponseEntity<List<DiagramSession>> listSessions(
            @RequestParam(required = false) Long factSheetId) {
        return ResponseEntity.ok(diagramService.listSessions(factSheetId));
    }

    /**
     * Get a single diagram session by ID.
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<DiagramSession> getSession(@PathVariable Long sessionId) {
        return diagramService.getSession(sessionId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Delete a diagram session.
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable Long sessionId) {
        diagramService.deleteSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Update the title of a diagram session.
     */
    @PatchMapping("/{sessionId}/title")
    public ResponseEntity<DiagramSession> updateTitle(
            @PathVariable Long sessionId,
            @RequestBody Map<String, String> body) {
        String title = body.get("title");
        return diagramService.updateTitle(sessionId, title)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Update the mermaid code of a diagram session.
     */
    @PatchMapping("/{sessionId}/mermaid")
    public ResponseEntity<DiagramSession> updateMermaid(
            @PathVariable Long sessionId,
            @RequestBody Map<String, String> body) {
        String code = body.get("mermaidCode");
        return diagramService.updateMermaidCode(sessionId, code)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Diagram ↔ Process integration ──────────────────────────────────────────

    /**
     * Convert a diagram session's Mermaid code into an executable ProcessDefinition.
     * Parses the Mermaid flowchart: subgraphs → phases, nodes → steps (shape-inferred
     * step types), edges → dependsOn. Creates a DRAFT ProcessDefinition and links it
     * to this diagram session.
     */
    @PostMapping("/{sessionId}/convert-to-process")
    public ResponseEntity<ProcessDefinition> convertToProcess(@PathVariable Long sessionId) {
        try {
            ProcessDefinition created = diagramService.convertToProcess(sessionId);
            return ResponseEntity.ok(created);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException e) {
            log.warn("Cannot convert diagram to process: {}", e.getMessage());
            return ResponseEntity.unprocessableEntity().build();
        }
    }

    /**
     * Render a ProcessDefinition as a Mermaid flowchart diagram.
     * This allows visualizing any process definition — not just those created from diagrams.
     */
    @GetMapping("/render/{processDefinitionId}")
    public ResponseEntity<Map<String, String>> renderProcessDiagram(
            @PathVariable String processDefinitionId) {
        try {
            String mermaidCode = diagramService.renderProcessAsMermaid(processDefinitionId);
            Map<String, String> result = new LinkedHashMap<>();
            result.put("mermaidCode", mermaidCode);
            result.put("processDefinitionId", processDefinitionId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.unprocessableEntity().build();
        }
    }

    /**
     * Convert raw Mermaid code to a ProcessDefinition preview without persisting.
     * Useful for the frontend to preview what the conversion would produce.
     */
    @PostMapping("/preview-conversion")
    public ResponseEntity<ProcessDefinition> previewConversion(@RequestBody Map<String, String> body) {
        String mermaidCode = body.get("mermaidCode");
        String processName = body.getOrDefault("processName", "Preview Process");
        if (mermaidCode == null || mermaidCode.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            ProcessDefinition preview = diagramService.getConverter().fromMermaid(mermaidCode, processName);
            return ResponseEntity.ok(preview);
        } catch (Exception e) {
            log.warn("Mermaid preview conversion failed: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    // ── Provenance & Cross-Linking ─────────────────────────────────────────────

    /**
     * Get structured provenance citations for a diagram session.
     * Returns a list of source entities with human-readable titles, entity types,
     * discovery sources, and content excerpts. For legacy sessions with raw-text
     * sourcesJson, returns a single fallback entry.
     */
    @GetMapping("/{sessionId}/provenance")
    public ResponseEntity<List<Map<String, Object>>> getSessionProvenance(
            @PathVariable Long sessionId) {
        List<Map<String, Object>> provenance = diagramService.getStructuredProvenance(sessionId);
        return ResponseEntity.ok(provenance);
    }

    /**
     * Get the diagram session that was converted to the given process definition.
     * Used for cross-linking: entity browser → referencing process → diagram session.
     */
    @GetMapping("/by-process/{processDefinitionId}")
    public ResponseEntity<DiagramSession> getByProcessDefinition(
            @PathVariable String processDefinitionId) {
        return diagramService.getByProcessDefinitionId(processDefinitionId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Request DTOs ──────────────────────────────────────────────────────────

    public static class DiagramGenerateRequest {
        public String prompt;
        public String agentName;
        public Long factSheetId;
    }

    public static class DiagramFinalizeRequest {
        public String transcriptJson;
        public String mermaidCode;
        public String title;
        public String description;
        public String sourcesJson;
    }
}
