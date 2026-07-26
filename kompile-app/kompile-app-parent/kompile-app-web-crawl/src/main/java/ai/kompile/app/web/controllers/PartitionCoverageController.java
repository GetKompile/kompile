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
package ai.kompile.app.web.controllers;

import ai.kompile.core.graphrag.partition.DocumentCoverageReport;
import ai.kompile.core.graphrag.partition.EntityPartition;
import ai.kompile.core.graphrag.partition.PartitionCoverageReport;
import ai.kompile.core.graphrag.partition.PartitionStore;
import ai.kompile.crawl.graph.partition.PartitionFactSheets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * Reads the entity-partition coverage claims the crawl records.
 *
 * <p>The partition pass writes a durable claim per subject — what it looked at, what it read, and
 * what it knowingly did not — and until this controller existed nothing could read them back. A
 * coverage claim nobody can see is a claim nobody relies on, which makes it indistinguishable from
 * having made none: an answer drawn from the graph looks equally confident either way.</p>
 *
 * <p>Two axes, because they are two different questions. By subject ("how much has been read about
 * Acme") is {@link PartitionCoverageReport}; by document ("how much of this filing was read, and
 * for whom") is {@link DocumentCoverageReport}. Neither implies the other.</p>
 *
 * <p>Every number is relative to the policy version the claim was recorded under, which travels in
 * the response rather than being averaged away — two partitions recorded under different policies
 * are two different claims.</p>
 *
 * <p>The store is optional ({@code required = false}) so a deployment that does not scan the
 * partition components degrades to HTTP 503 instead of failing context startup.</p>
 */
@RestController
@RequestMapping("/api/graph/partitions")
public class PartitionCoverageController {

    private static final Logger log = LoggerFactory.getLogger(PartitionCoverageController.class);

    @Autowired(required = false)
    private PartitionStore partitionStore;

    /** Coverage over every partition recorded for this project. */
    @GetMapping("/coverage")
    public ResponseEntity<?> coverage() {
        if (partitionStore == null) {
            return unavailable();
        }
        List<EntityPartition> found = partitionStore.findAll();
        log.debug("[PartitionCoverage] all partitions: {}", found.size());
        return ResponseEntity.ok(PartitionCoverageReport.of("all partitions", found));
    }

    /**
     * Coverage over the partitions discovered against one fact sheet.
     *
     * <p>The fact sheet is a pin, not part of the partition key — a partition is identified by its
     * subject, policy and snapshot — so this is a pin lookup rather than a key lookup.</p>
     */
    @GetMapping("/coverage/fact-sheet/{factSheetId}")
    public ResponseEntity<?> coverageForFactSheet(@PathVariable("factSheetId") long factSheetId) {
        if (partitionStore == null) {
            return unavailable();
        }
        List<EntityPartition> found = partitionStore.findByPin(
                PartitionFactSheets.FACT_SHEET_PIN, String.valueOf(factSheetId));
        log.debug("[PartitionCoverage] factSheet={} partitions={}", factSheetId, found.size());
        return ResponseEntity.ok(
                PartitionCoverageReport.of("fact sheet " + factSheetId, found));
    }

    /**
     * Coverage over the partitions recorded under one discovery policy version.
     *
     * <p>Worth its own endpoint because coverage is policy-relative: a run that had no vector index
     * behind it records its claims under a different version precisely so they can never be
     * compared to ones that did.</p>
     */
    @GetMapping("/coverage/policy/{policyVersion}")
    public ResponseEntity<?> coverageForPolicy(@PathVariable("policyVersion") String policyVersion) {
        if (partitionStore == null) {
            return unavailable();
        }
        List<EntityPartition> found = partitionStore.findByPolicy(policyVersion);
        log.debug("[PartitionCoverage] policy={} partitions={}", policyVersion, found.size());
        return ResponseEntity.ok(PartitionCoverageReport.of("policy " + policyVersion, found));
    }

    /**
     * What became of one document: which of its chunks were read, for which subjects, and which
     * were excluded, deferred, unreadable or invalidated.
     *
     * <p>A query parameter rather than a path variable because document ids are frequently source
     * paths, and a path variable would swallow the slashes.</p>
     */
    @GetMapping("/coverage/document")
    public ResponseEntity<?> coverageForDocument(@RequestParam("documentId") String documentId) {
        if (partitionStore == null) {
            return unavailable();
        }
        if (documentId == null || documentId.isBlank()) {
            return ResponseEntity.badRequest().body("documentId is required");
        }
        List<EntityPartition> found = partitionStore.findByDocument(documentId);
        log.debug("[PartitionCoverage] document={} partitions={}", documentId, found.size());
        return ResponseEntity.ok(DocumentCoverageReport.of(documentId, found));
    }

    /** One partition's claim in full, including the chunk ids it did not read. */
    @GetMapping("/{partitionId}")
    public ResponseEntity<?> partition(@PathVariable("partitionId") String partitionId) {
        if (partitionStore == null) {
            return unavailable();
        }
        Optional<EntityPartition> found = partitionStore.load(partitionId);
        return found.<ResponseEntity<?>>map(
                        partition -> ResponseEntity.ok(
                                PartitionCoverageReport.SubjectCoverage.of(partition)))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body("No partition recorded with id " + partitionId));
    }

    private ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503).body("PartitionStore not available");
    }
}
