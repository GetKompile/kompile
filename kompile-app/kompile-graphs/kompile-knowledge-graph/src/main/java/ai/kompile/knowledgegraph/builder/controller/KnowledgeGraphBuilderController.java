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
package ai.kompile.knowledgegraph.builder.controller;

import ai.kompile.core.graphbuilder.*;
import ai.kompile.knowledgegraph.builder.domain.ExtractionJob;
import ai.kompile.knowledgegraph.builder.domain.ExtractionLogRecord;
import ai.kompile.knowledgegraph.builder.domain.TripleProposal;
import ai.kompile.knowledgegraph.builder.service.ExtractionJobService;
import ai.kompile.knowledgegraph.builder.service.GraphBuilderRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for knowledge graph builder operations.
 */
@RestController
@RequestMapping("/api/knowledge-graph/builder")
@RequiredArgsConstructor
@Slf4j
public class KnowledgeGraphBuilderController {

    private final GraphBuilderRegistry builderRegistry;
    private final ExtractionJobService jobService;

    // ==================== Builder Discovery ====================

    /**
     * List all available graph builders.
     */
    @GetMapping("/builders")
    public ResponseEntity<List<GraphBuilderInfo>> listBuilders() {
        return ResponseEntity.ok(builderRegistry.getBuilderInfos());
    }

    // ==================== Storage Types ====================

    /**
     * List all available storage types for accepted proposals.
     */
    @GetMapping("/storage-types")
    public ResponseEntity<Map<String, Object>> getStorageTypes() {
        // Convert StorageInfo to format expected by frontend
        List<Map<String, Object>> allTypes = jobService.getStorageInfo().stream()
                .map(info -> Map.<String, Object>of(
                        "type", info.storageType(),
                        "available", info.available(),
                        "description", getStorageDescription(info.storageType())
                ))
                .toList();

        return ResponseEntity.ok(Map.of(
                "availableTypes", jobService.getAvailableStorageTypes(),
                "allTypes", allTypes,
                "defaultType", jobService.getDefaultStorageType()
        ));
    }

    /**
     * Get human-readable description for a storage type.
     */
    private String getStorageDescription(String storageType) {
        return switch (storageType.toLowerCase()) {
            case "jpa" -> "Local Database (JPA/JDBC)";
            case "neo4j" -> "Neo4j Graph Database";
            default -> storageType;
        };
    }

    /**
     * Get builder info by ID.
     */
    @GetMapping("/builders/{builderId}")
    public ResponseEntity<GraphBuilderInfo> getBuilder(@PathVariable("builderId") String builderId) {
        return builderRegistry.getBuilderInfo(builderId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ==================== Job Management ====================

    /**
     * Start a new extraction job.
     */
    @PostMapping("/jobs")
    public ResponseEntity<ExtractionJobResponse> startJob(@RequestBody StartJobRequest request) {
        // Validate builder exists
        if (!builderRegistry.hasBuilder(request.builderType()) &&
            builderRegistry.getBuilderByTypeString(request.builderType()).isEmpty()) {
            return ResponseEntity.badRequest().body(
                    new ExtractionJobResponse(null, "Unknown builder type: " + request.builderType()));
        }

        // Check for existing running job
        if (jobService.hasRunningJob(request.factSheetId())) {
            return ResponseEntity.badRequest().body(
                    new ExtractionJobResponse(null, "A job is already running for this fact sheet"));
        }

        // Create job
        ExtractionJob job = jobService.createJob(
                request.factSheetId(),
                request.builderType(),
                request.config()
        );

        log.info("Created extraction job: {} for fact sheet: {}", job.getJobId(), request.factSheetId());

        return ResponseEntity.ok(ExtractionJobResponse.from(job));
    }

    /**
     * Get job status.
     */
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<ExtractionJobResponse> getJobStatus(@PathVariable("jobId") String jobId) {
        return jobService.getJob(jobId)
                .map(job -> ResponseEntity.ok(ExtractionJobResponse.from(job)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get jobs for a fact sheet.
     */
    @GetMapping("/jobs")
    public ResponseEntity<Page<ExtractionJobResponse>> getJobs(
            @RequestParam(name = "factSheetId") Long factSheetId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ExtractionJob> jobs = jobService.getJobsForFactSheet(factSheetId, pageable);
        return ResponseEntity.ok(jobs.map(ExtractionJobResponse::from));
    }

    /**
     * Cancel a running job.
     */
    @PostMapping("/jobs/{jobId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelJob(@PathVariable("jobId") String jobId) {
        try {
            ExtractionJob job = jobService.cancelJob(jobId);
            return ResponseEntity.ok(Map.of(
                    "cancelled", true,
                    "jobId", job.getJobId(),
                    "status", job.getStatus().name()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "cancelled", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Get job statistics.
     */
    @GetMapping("/jobs/{jobId}/statistics")
    public ResponseEntity<Map<String, Object>> getJobStatistics(@PathVariable("jobId") String jobId) {
        if (jobService.getJob(jobId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(jobService.getJobStatistics(jobId));
    }

    // ==================== Extraction Logs (Full Transparency) ====================

    /**
     * Get extraction logs for a job.
     */
    @GetMapping("/jobs/{jobId}/logs")
    public ResponseEntity<Page<ExtractionLogResponse>> getJobLogs(
            @PathVariable("jobId") String jobId,
            @RequestParam(defaultValue = "0", name = "page") int page,
            @RequestParam(defaultValue = "50", name = "size") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt"));
        Page<ExtractionLogRecord> logs = jobService.getLogsForJob(jobId, pageable);
        return ResponseEntity.ok(logs.map(ExtractionLogResponse::from));
    }

    /**
     * Get extraction log for a specific chunk.
     */
    @GetMapping("/jobs/{jobId}/logs/{chunkId}")
    public ResponseEntity<ExtractionLogResponse> getLogForChunk(
            @PathVariable("jobId") String jobId,
            @PathVariable("chunkId") String chunkId) {

        return jobService.getLogForChunk(jobId, chunkId)
                .map(log -> ResponseEntity.ok(ExtractionLogResponse.from(log)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ==================== Proposals ====================

    /**
     * Get proposals for a job.
     */
    @GetMapping("/proposals")
    public ResponseEntity<Page<TripleProposalResponse>> getProposals(
            @RequestParam(required = false, name = "jobId") String jobId,
            @RequestParam(required = false, name = "factSheetId") Long factSheetId,
            @RequestParam(required = false, name = "status") String status,
            @RequestParam(required = false, name = "query") String query,
            @RequestParam(defaultValue = "0", name = "page") int page,
            @RequestParam(defaultValue = "50", name = "size") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "confidence"));

        Page<TripleProposal> proposals;

        if (query != null && !query.isEmpty() && factSheetId != null) {
            proposals = jobService.searchProposals(factSheetId, query, pageable);
        } else if (jobId != null) {
            proposals = jobService.getProposalsForJob(jobId, pageable);
        } else if (factSheetId != null) {
            if (status != null && !status.isEmpty()) {
                TripleProposal.ProposalStatus proposalStatus =
                        TripleProposal.ProposalStatus.valueOf(status.toUpperCase());
                proposals = jobService.getProposalsByStatus(factSheetId, proposalStatus, pageable);
            } else {
                proposals = jobService.getProposalsForFactSheet(factSheetId, pageable);
            }
        } else {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(proposals.map(TripleProposalResponse::from));
    }

    /**
     * Get a specific proposal.
     */
    @GetMapping("/proposals/{proposalId}")
    public ResponseEntity<TripleProposalResponse> getProposal(@PathVariable("proposalId") String proposalId) {
        return jobService.getProposal(proposalId)
                .map(p -> ResponseEntity.ok(TripleProposalResponse.from(p)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Accept a proposal.
     *
     * @param proposalId the proposal ID
     * @param reviewedBy who reviewed this proposal (default: "user")
     * @param storageType the storage type ("jpa", "neo4j"), or null/empty for default
     */
    @PostMapping("/proposals/{proposalId}/accept")
    public ResponseEntity<Map<String, Object>> acceptProposal(
            @PathVariable("proposalId") String proposalId,
            @RequestParam(defaultValue = "user", name = "reviewedBy") String reviewedBy,
            @RequestParam(required = false, name = "storageType") String storageType) {
        try {
            TripleProposal proposal = jobService.acceptProposal(proposalId, reviewedBy, storageType);
            return ResponseEntity.ok(Map.of(
                    "accepted", true,
                    "proposalId", proposal.getProposalId(),
                    "subjectNodeId", proposal.getSubjectNodeId(),
                    "objectNodeId", proposal.getObjectNodeId(),
                    "edgeId", proposal.getEdgeId(),
                    "storageType", storageType != null ? storageType : jobService.getDefaultStorageType()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "accepted", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Reject a proposal.
     */
    @PostMapping("/proposals/{proposalId}/reject")
    public ResponseEntity<Map<String, Object>> rejectProposal(
            @PathVariable("proposalId") String proposalId,
            @RequestBody(required = false) RejectRequest request) {
        try {
            String reason = request != null ? request.reason() : null;
            String reviewedBy = request != null && request.reviewedBy() != null ? request.reviewedBy() : "user";

            TripleProposal proposal = jobService.rejectProposal(proposalId, reviewedBy, reason);
            return ResponseEntity.ok(Map.of(
                    "rejected", true,
                    "proposalId", proposal.getProposalId()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "rejected", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Bulk accept proposals.
     */
    @PostMapping("/proposals/bulk-accept")
    public ResponseEntity<Map<String, Object>> bulkAccept(@RequestBody BulkActionRequest request) {
        int accepted = jobService.bulkAcceptProposals(
                request.proposalIds(),
                request.reviewedBy() != null ? request.reviewedBy() : "user",
                request.storageType()
        );
        return ResponseEntity.ok(Map.of(
                "accepted", accepted,
                "total", request.proposalIds().size(),
                "failed", request.proposalIds().size() - accepted,
                "storageType", request.storageType() != null ? request.storageType() : jobService.getDefaultStorageType()
        ));
    }

    /**
     * Bulk reject proposals.
     */
    @PostMapping("/proposals/bulk-reject")
    public ResponseEntity<Map<String, Object>> bulkReject(@RequestBody BulkRejectRequest request) {
        int rejected = jobService.bulkRejectProposals(
                request.proposalIds(),
                request.reviewedBy() != null ? request.reviewedBy() : "user",
                request.reason()
        );
        return ResponseEntity.ok(Map.of(
                "rejected", rejected,
                "total", request.proposalIds().size()
        ));
    }

    /**
     * Create a manual proposal.
     */
    @PostMapping("/proposals/manual")
    public ResponseEntity<TripleProposalResponse> createManualProposal(@RequestBody ManualProposalRequest request) {
        TripleProposal proposal = jobService.createManualProposal(
                request.factSheetId(),
                request.subjectName(),
                request.subjectType(),
                request.predicateName(),
                request.objectName(),
                request.objectType(),
                request.description(),
                request.autoAccept() != null && request.autoAccept()
        );
        return ResponseEntity.ok(TripleProposalResponse.from(proposal));
    }

    // ==================== Request/Response Records ====================

    public record StartJobRequest(
            Long factSheetId,
            String builderType,
            BuilderConfig config,
            List<String> chunkIds
    ) {}

    public record RejectRequest(
            String reason,
            String reviewedBy
    ) {}

    public record BulkActionRequest(
            List<String> proposalIds,
            String reviewedBy,
            String storageType
    ) {}

    public record BulkRejectRequest(
            List<String> proposalIds,
            String reviewedBy,
            String reason
    ) {}

    public record ManualProposalRequest(
            Long factSheetId,
            String subjectName,
            String subjectType,
            String predicateName,
            String objectName,
            String objectType,
            String description,
            Boolean autoAccept
    ) {}

    public record ExtractionJobResponse(
            String jobId,
            Long factSheetId,
            String builderType,
            String status,
            Integer totalChunks,
            Integer processedChunks,
            Integer proposalsCreated,
            Integer proposalsAccepted,
            Integer proposalsRejected,
            Integer progressPercent,
            String createdAt,
            String startedAt,
            String completedAt,
            String errorMessage
    ) {
        public ExtractionJobResponse(String jobId, String errorMessage) {
            this(jobId, null, null, null, null, null, null, null, null, null, null, null, null, errorMessage);
        }

        public static ExtractionJobResponse from(ExtractionJob job) {
            return new ExtractionJobResponse(
                    job.getJobId(),
                    job.getFactSheetId(),
                    job.getBuilderType(),
                    job.getStatus().name(),
                    job.getTotalChunks(),
                    job.getProcessedChunks(),
                    job.getProposalsCreated(),
                    job.getProposalsAccepted(),
                    job.getProposalsRejected(),
                    job.getProgressPercent(),
                    job.getCreatedAt() != null ? job.getCreatedAt().toString() : null,
                    job.getStartedAt() != null ? job.getStartedAt().toString() : null,
                    job.getCompletedAt() != null ? job.getCompletedAt().toString() : null,
                    job.getErrorMessage()
            );
        }
    }

    public record ExtractionLogResponse(
            Long id,
            String chunkId,
            String documentId,
            String promptText,
            String responseText,
            String inputText,
            String parsedEntitiesJson,
            String parsedRelationshipsJson,
            Integer entitiesCount,
            Integer relationshipsCount,
            String modelProvider,
            String modelName,
            Long latencyMs,
            Integer promptTokens,
            Integer responseTokens,
            Boolean success,
            String errorMessage,
            String createdAt
    ) {
        public static ExtractionLogResponse from(ExtractionLogRecord record) {
            return new ExtractionLogResponse(
                    record.getId(),
                    record.getChunkId(),
                    record.getDocumentId(),
                    record.getPromptText(),
                    record.getResponseText(),
                    record.getInputText(),
                    record.getParsedEntitiesJson(),
                    record.getParsedRelationshipsJson(),
                    record.getEntitiesCount(),
                    record.getRelationshipsCount(),
                    record.getModelProvider(),
                    record.getModelName(),
                    record.getLatencyMs(),
                    record.getPromptTokens(),
                    record.getResponseTokens(),
                    record.getSuccess(),
                    record.getErrorMessage(),
                    record.getCreatedAt() != null ? record.getCreatedAt().toString() : null
            );
        }
    }

    public record TripleProposalResponse(
            String proposalId,
            String jobId,
            Long factSheetId,
            String subjectName,
            String subjectType,
            String predicateName,
            String objectName,
            String objectType,
            Double confidence,
            String status,
            String sourceChunkId,
            String sourceDocumentId,
            String sourceContext,
            String createdAt,
            String reviewedAt,
            String reviewedBy,
            String rejectionReason,
            String subjectNodeId,
            String objectNodeId,
            String edgeId
    ) {
        public static TripleProposalResponse from(TripleProposal proposal) {
            return new TripleProposalResponse(
                    proposal.getProposalId(),
                    proposal.getJob() != null ? proposal.getJob().getJobId() : null,
                    proposal.getFactSheetId(),
                    proposal.getSubjectName(),
                    proposal.getSubjectType(),
                    proposal.getPredicateName(),
                    proposal.getObjectName(),
                    proposal.getObjectType(),
                    proposal.getConfidence(),
                    proposal.getStatus().name(),
                    proposal.getSourceChunkId(),
                    proposal.getSourceDocumentId(),
                    proposal.getSourceContext(),
                    proposal.getCreatedAt() != null ? proposal.getCreatedAt().toString() : null,
                    proposal.getReviewedAt() != null ? proposal.getReviewedAt().toString() : null,
                    proposal.getReviewedBy(),
                    proposal.getRejectionReason(),
                    proposal.getSubjectNodeId(),
                    proposal.getObjectNodeId(),
                    proposal.getEdgeId()
            );
        }
    }
}
