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
package ai.kompile.app.web.controllers;

import ai.kompile.app.prompts.domain.SystemPromptEntity;
import ai.kompile.app.prompts.domain.SystemPromptTestResultEntity;
import ai.kompile.app.prompts.service.SystemPromptEvalIntegrationService;
import ai.kompile.app.prompts.service.SystemPromptService;
import ai.kompile.react.eval.model.EvalSuite;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for system prompt management.
 */
@RestController
@RequestMapping("/api/system-prompts")
@RequiredArgsConstructor
@Slf4j
public class SystemPromptController {

    private final SystemPromptService promptService;
    private final SystemPromptEvalIntegrationService evalIntegrationService;

    // ==================== DTOs ====================

    public record CreatePromptRequest(
            String name,
            String description,
            String content,
            String variablesJson,
            String tagsJson,
            String createdBy
    ) {}

    public record UpdatePromptRequest(
            String name,
            String description,
            String content,
            String variablesJson,
            String tagsJson,
            String changeNotes
    ) {}

    public record CreateVersionRequest(
            String content,
            String changeNotes
    ) {}

    public record TestPromptRequest(
            String evalSuiteId,
            boolean passed,
            double score,
            int passedCount,
            int failedCount,
            Map<String, Object> detailedResults
    ) {}

    public record ComparePromptsRequest(
            String promptId1,
            String promptId2,
            String evalSuiteId
    ) {}

    // ==================== CRUD Endpoints ====================

    /**
     * List all prompts for the active fact sheet.
     */
    @GetMapping
    public ResponseEntity<List<SystemPromptEntity>> listPrompts() {
        List<SystemPromptEntity> prompts = promptService.getPromptsForActiveFactSheet();
        return ResponseEntity.ok(prompts);
    }

    /**
     * List prompts for a specific fact sheet.
     */
    @GetMapping("/fact-sheet/{factSheetId}")
    public ResponseEntity<List<SystemPromptEntity>> listPromptsForFactSheet(
            @PathVariable Long factSheetId) {
        List<SystemPromptEntity> prompts = promptService.getPromptsForFactSheet(factSheetId);
        return ResponseEntity.ok(prompts);
    }

    /**
     * Get a prompt by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getPrompt(@PathVariable String id) {
        return promptService.getPromptById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get the active prompt for the current fact sheet.
     */
    @GetMapping("/active")
    public ResponseEntity<?> getActivePrompt() {
        return promptService.getActivePrompt()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get the active prompt for a specific fact sheet.
     */
    @GetMapping("/fact-sheet/{factSheetId}/active")
    public ResponseEntity<?> getActivePromptForFactSheet(@PathVariable Long factSheetId) {
        return promptService.getActivePromptForFactSheet(factSheetId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Create a new prompt.
     */
    @PostMapping
    public ResponseEntity<?> createPrompt(@RequestBody CreatePromptRequest request) {
        try {
            if (request.name() == null || request.name().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Name is required"));
            }
            if (request.content() == null || request.content().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Content is required"));
            }

            SystemPromptEntity prompt = SystemPromptEntity.builder()
                    .name(request.name())
                    .description(request.description())
                    .content(request.content())
                    .variablesJson(request.variablesJson())
                    .tagsJson(request.tagsJson())
                    .createdBy(request.createdBy())
                    .build();

            SystemPromptEntity created = promptService.createPrompt(prompt);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (Exception e) {
            log.error("Error creating prompt: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Update an existing prompt.
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updatePrompt(
            @PathVariable String id,
            @RequestBody UpdatePromptRequest request) {
        try {
            SystemPromptEntity updates = SystemPromptEntity.builder()
                    .name(request.name())
                    .description(request.description())
                    .content(request.content())
                    .variablesJson(request.variablesJson())
                    .tagsJson(request.tagsJson())
                    .changeNotes(request.changeNotes())
                    .build();

            SystemPromptEntity updated = promptService.updatePrompt(id, updates);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error updating prompt {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Delete a prompt.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deletePrompt(@PathVariable String id) {
        try {
            promptService.deletePrompt(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error deleting prompt {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== Versioning Endpoints ====================

    /**
     * Create a new version of a prompt.
     */
    @PostMapping("/{id}/versions")
    public ResponseEntity<?> createVersion(
            @PathVariable String id,
            @RequestBody CreateVersionRequest request) {
        try {
            SystemPromptEntity newVersion;
            if (request.content() != null) {
                newVersion = promptService.createNewVersionWithContent(
                        id, request.content(), request.changeNotes());
            } else {
                newVersion = promptService.createNewVersion(id, request.changeNotes());
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(newVersion);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error creating version for prompt {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get version history for a prompt.
     */
    @GetMapping("/{id}/versions")
    public ResponseEntity<?> getVersionHistory(@PathVariable String id) {
        try {
            List<SystemPromptEntity> history = promptService.getVersionHistory(id);
            return ResponseEntity.ok(history);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error getting version history for prompt {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Activate a specific prompt version.
     */
    @PostMapping("/{id}/activate")
    public ResponseEntity<?> activatePrompt(@PathVariable String id) {
        try {
            SystemPromptEntity activated = promptService.activatePrompt(id);
            return ResponseEntity.ok(activated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error activating prompt {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== Search Endpoints ====================

    /**
     * Search prompts by name.
     */
    @GetMapping("/search")
    public ResponseEntity<List<SystemPromptEntity>> searchPrompts(
            @RequestParam String q) {
        List<SystemPromptEntity> results = promptService.searchByName(q);
        return ResponseEntity.ok(results);
    }

    /**
     * Find prompts by tag.
     */
    @GetMapping("/by-tag/{tag}")
    public ResponseEntity<List<SystemPromptEntity>> findByTag(@PathVariable String tag) {
        List<SystemPromptEntity> results = promptService.findByTag(tag);
        return ResponseEntity.ok(results);
    }

    // ==================== Variable Endpoints ====================

    /**
     * Extract variables from a prompt's content.
     */
    @GetMapping("/{id}/variables/extract")
    public ResponseEntity<?> extractVariables(@PathVariable String id) {
        return promptService.getPromptById(id)
                .map(prompt -> {
                    List<String> variables = promptService.extractVariables(prompt.getContent());
                    return ResponseEntity.ok(Map.of("variables", variables));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ==================== Testing Endpoints ====================

    /**
     * Get available eval suites for a prompt.
     */
    @GetMapping("/{id}/eval-suites")
    public ResponseEntity<?> getAvailableEvalSuites(@PathVariable String id) {
        try {
            List<EvalSuite> suites = evalIntegrationService.getAvailableSuitesForPrompt(id);
            return ResponseEntity.ok(suites);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error getting eval suites for prompt {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Record a test result for a prompt.
     */
    @PostMapping("/{id}/test")
    public ResponseEntity<?> recordTestResult(
            @PathVariable String id,
            @RequestBody TestPromptRequest request) {
        try {
            SystemPromptTestResultEntity result = evalIntegrationService.recordTestResult(
                    id,
                    request.evalSuiteId(),
                    request.passed(),
                    request.score(),
                    request.passedCount(),
                    request.failedCount(),
                    request.detailedResults()
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error recording test result for prompt {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get test result history for a prompt.
     */
    @GetMapping("/{id}/test-results")
    public ResponseEntity<?> getTestResults(
            @PathVariable String id,
            @RequestParam(required = false) String evalSuiteId) {
        try {
            List<SystemPromptTestResultEntity> results;
            if (evalSuiteId != null) {
                results = evalIntegrationService.getTestResultHistory(id, evalSuiteId);
            } else {
                results = evalIntegrationService.getTestResultHistory(id);
            }
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            log.error("Error getting test results for prompt {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get test statistics for a prompt.
     */
    @GetMapping("/{id}/test-stats")
    public ResponseEntity<?> getTestStats(@PathVariable String id) {
        try {
            Map<String, Object> stats = evalIntegrationService.getPromptTestStats(id);
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Error getting test stats for prompt {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Compare two prompts.
     */
    @PostMapping("/compare")
    public ResponseEntity<?> comparePrompts(@RequestBody ComparePromptsRequest request) {
        try {
            Map<String, Object> comparison = evalIntegrationService.comparePrompts(
                    request.promptId1(),
                    request.promptId2(),
                    request.evalSuiteId()
            );
            return ResponseEntity.ok(comparison);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error comparing prompts: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== Statistics Endpoints ====================

    /**
     * Get prompt count for the active fact sheet.
     */
    @GetMapping("/stats/count")
    public ResponseEntity<Map<String, Long>> getPromptCount() {
        long count = promptService.getPromptCount();
        return ResponseEntity.ok(Map.of("count", count));
    }
}
