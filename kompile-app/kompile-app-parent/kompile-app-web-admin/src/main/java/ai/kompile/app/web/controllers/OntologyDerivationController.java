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

import ai.kompile.app.ontology.OntologyDerivationJobService;
import ai.kompile.app.ontology.OntologyDerivationService;
import ai.kompile.app.web.dto.ontology.DeriveOntologyRequest;
import ai.kompile.app.web.dto.ontology.DerivationJobView;
import ai.kompile.process.ontology.OntologySchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Augments the process-engine ontology API ({@code /api/process/ontology}) with <b>derivation</b>
 * endpoints that build an {@code OntologySchema} from a fact sheet's crawl graph.
 *
 * <p>These endpoints live in {@code kompile-app-main} (not in the process-engine module that owns
 * {@code ProcessEngineController}) because derivation needs the knowledge-graph services and the
 * {@code LLMChat} abstraction, which the process-engine module does not depend on. They share the
 * {@code /api/process/ontology} prefix so the UI can treat ontology operations as one resource; the
 * concrete paths ({@code /derive}, {@code /derive/candidates}) do not collide with the create / get /
 * update / validate handlers in {@code ProcessEngineController}.</p>
 *
 * <p>{@code POST /derive} returns an <b>unsaved draft</b>; the client reviews it and persists it via
 * the existing {@code POST /api/process/ontology} endpoint.</p>
 */
@RestController
@RequestMapping("/api/process/ontology")
public class OntologyDerivationController {

    private static final Logger logger = LoggerFactory.getLogger(OntologyDerivationController.class);

    private final OntologyDerivationService derivationService;
    private final OntologyDerivationJobService jobService;

    public OntologyDerivationController(OntologyDerivationService derivationService,
                                        OntologyDerivationJobService jobService) {
        this.derivationService = derivationService;
        this.jobService = jobService;
    }

    /**
     * Graph-grounded candidate entity types and relationship hints for the derivation wizard.
     * {@code GET /api/process/ontology/derive/candidates?factSheetId=..&limit=60}
     */
    @GetMapping("/derive/candidates")
    public ResponseEntity<?> candidates(@RequestParam("factSheetId") Long factSheetId,
                                        @RequestParam(value = "limit", defaultValue = "60") int limit) {
        try {
            return ResponseEntity.ok(derivationService.candidates(factSheetId, limit));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to load ontology derivation candidates for factSheet {}", factSheetId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to load candidates: " + e.getMessage()));
        }
    }

    /**
     * Derive an unsaved draft ontology from a fact sheet's crawl graph (LLM or structural fallback).
     * {@code POST /api/process/ontology/derive}
     */
    @PostMapping("/derive")
    public ResponseEntity<?> derive(@RequestBody DeriveOntologyRequest request) {
        try {
            OntologySchema draft = derivationService.derive(request);
            return ResponseEntity.ok(draft);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            // Nothing to derive from (e.g. empty graph and no LLM configured).
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Ontology derivation failed for factSheet {}",
                    request != null ? request.factSheetId() : null, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Ontology derivation failed: " + e.getMessage()));
        }
    }

    /**
     * Start an asynchronous derivation so the client can watch the agent generate in real time
     * (live logs + transcript stream on {@code taskId}).
     * {@code POST /api/process/ontology/derive/async} → 202 with {jobId, taskId, status}.
     */
    @PostMapping("/derive/async")
    public ResponseEntity<?> deriveAsync(@RequestBody DeriveOntologyRequest request) {
        if (request == null || request.factSheetId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "factSheetId is required"));
        }
        try {
            OntologyDerivationJobService.Job job = jobService.start(request);
            return ResponseEntity.accepted()
                    .body(new DerivationJobView(job.getJobId(), job.getTaskId(), job.getStatus(), null, null));
        } catch (Exception e) {
            logger.error("Failed to start ontology derivation job", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to start derivation: " + e.getMessage()));
        }
    }

    /**
     * Poll an async derivation job; when {@code status} is COMPLETED the {@code draft} is populated.
     * {@code GET /api/process/ontology/derive/jobs/{jobId}}
     */
    @GetMapping("/derive/jobs/{jobId}")
    public ResponseEntity<?> deriveJob(@PathVariable("jobId") String jobId) {
        OntologyDerivationJobService.Job job = jobService.get(jobId);
        if (job == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Job not found: " + jobId));
        }
        return ResponseEntity.ok(new DerivationJobView(
                job.getJobId(), job.getTaskId(), job.getStatus(), job.getDraft(), job.getError()));
    }
}
