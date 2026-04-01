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
package ai.kompile.knowledgegraph.builder.service;

import ai.kompile.core.graphbuilder.*;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.knowledgegraph.builder.domain.ExtractionJob;
import ai.kompile.knowledgegraph.builder.domain.ExtractionJob.JobStatus;
import ai.kompile.knowledgegraph.builder.domain.ExtractionLogRecord;
import ai.kompile.knowledgegraph.builder.domain.TripleProposal;
import ai.kompile.knowledgegraph.builder.domain.TripleProposal.ProposalStatus;
import ai.kompile.knowledgegraph.builder.repository.ExtractionJobRepository;
import ai.kompile.knowledgegraph.builder.repository.ExtractionLogRepository;
import ai.kompile.knowledgegraph.builder.repository.TripleProposalRepository;
import ai.kompile.knowledgegraph.builder.storage.GraphStorageRegistry;
import ai.kompile.knowledgegraph.builder.storage.GraphStorageStrategy;
import ai.kompile.knowledgegraph.builder.storage.GraphStorageStrategy.StorageResult;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Service for managing extraction jobs, proposals, and acceptance workflows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExtractionJobService {

    private final ExtractionJobRepository jobRepository;
    private final TripleProposalRepository proposalRepository;
    private final ExtractionLogRepository logRepository;
    private final GraphNodeRepository nodeRepository;
    private final GraphEdgeRepository edgeRepository;
    private final GraphStorageRegistry storageRegistry;
    private final ObjectMapper objectMapper;

    // Track active jobs for progress updates
    private final Map<String, Consumer<BuildProgress>> progressCallbacks = new ConcurrentHashMap<>();

    // ==================== Job Management ====================

    /**
     * Create a new extraction job.
     */
    @Transactional
    public ExtractionJob createJob(Long factSheetId, String builderType, BuilderConfig config) {
        String configJson = null;
        if (config != null) {
            try {
                configJson = objectMapper.writeValueAsString(config);
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize builder config", e);
            }
        }

        ExtractionJob job = ExtractionJob.builder()
                .factSheetId(factSheetId)
                .builderType(builderType)
                .configJson(configJson)
                .status(JobStatus.PENDING)
                .build();

        return jobRepository.save(job);
    }

    /**
     * Get a job by ID.
     */
    public Optional<ExtractionJob> getJob(String jobId) {
        return jobRepository.findByJobId(jobId);
    }

    /**
     * Get jobs for a fact sheet.
     */
    public Page<ExtractionJob> getJobsForFactSheet(Long factSheetId, Pageable pageable) {
        return jobRepository.findByFactSheetId(factSheetId, pageable);
    }

    /**
     * Get jobs by status.
     */
    public Page<ExtractionJob> getJobsByStatus(Long factSheetId, JobStatus status, Pageable pageable) {
        return jobRepository.findByFactSheetIdAndStatus(factSheetId, status, pageable);
    }

    /**
     * Start a job.
     */
    @Transactional
    public ExtractionJob startJob(String jobId, int totalChunks) {
        ExtractionJob job = jobRepository.findByJobId(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        job.setTotalChunks(totalChunks);
        job.start();
        return jobRepository.save(job);
    }

    /**
     * Update job progress.
     */
    @Transactional
    public void updateJobProgress(String jobId, int processedChunks, int proposalsCreated) {
        jobRepository.updateProgress(jobId, processedChunks, proposalsCreated);

        // Notify progress callback if registered
        Consumer<BuildProgress> callback = progressCallbacks.get(jobId);
        if (callback != null) {
            ExtractionJob job = jobRepository.findByJobId(jobId).orElse(null);
            if (job != null) {
                callback.accept(BuildProgress.processing(
                        jobId,
                        job.getTotalChunks(),
                        processedChunks,
                        proposalsCreated,
                        null
                ));
            }
        }
    }

    /**
     * Complete a job.
     */
    @Transactional
    public ExtractionJob completeJob(String jobId) {
        ExtractionJob job = jobRepository.findByJobId(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        job.complete();
        ExtractionJob saved = jobRepository.save(job);

        // Notify completion
        Consumer<BuildProgress> callback = progressCallbacks.remove(jobId);
        if (callback != null) {
            callback.accept(BuildProgress.completed(jobId, job.getTotalChunks(), job.getProposalsCreated()));
        }

        return saved;
    }

    /**
     * Complete a job with a specific proposals count.
     */
    @Transactional
    public ExtractionJob completeJob(String jobId, int proposalsCreated) {
        ExtractionJob job = jobRepository.findByJobId(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        job.setProposalsCreated(proposalsCreated);
        job.complete();
        ExtractionJob saved = jobRepository.save(job);

        // Notify completion
        Consumer<BuildProgress> callback = progressCallbacks.remove(jobId);
        if (callback != null) {
            callback.accept(BuildProgress.completed(jobId, job.getTotalChunks(), proposalsCreated));
        }

        return saved;
    }

    /**
     * Fail a job.
     */
    @Transactional
    public ExtractionJob failJob(String jobId, String errorMessage) {
        ExtractionJob job = jobRepository.findByJobId(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        job.fail(errorMessage);
        ExtractionJob saved = jobRepository.save(job);

        // Notify failure
        Consumer<BuildProgress> callback = progressCallbacks.remove(jobId);
        if (callback != null) {
            callback.accept(BuildProgress.failed(jobId, job.getProcessedChunks(), errorMessage));
        }

        return saved;
    }

    /**
     * Cancel a job.
     */
    @Transactional
    public ExtractionJob cancelJob(String jobId) {
        ExtractionJob job = jobRepository.findByJobId(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        if (job.isTerminal()) {
            throw new IllegalStateException("Cannot cancel terminal job: " + job.getStatus());
        }

        job.cancel();
        progressCallbacks.remove(jobId);
        return jobRepository.save(job);
    }

    /**
     * Register a progress callback for a job.
     */
    public void registerProgressCallback(String jobId, Consumer<BuildProgress> callback) {
        progressCallbacks.put(jobId, callback);
    }

    /**
     * Check if a running job exists for a fact sheet.
     */
    public boolean hasRunningJob(Long factSheetId) {
        return jobRepository.hasRunningJob(factSheetId);
    }

    // ==================== Proposal Management ====================

    /**
     * Create proposals from extracted triples.
     */
    @Transactional
    public List<TripleProposal> createProposals(ExtractionJob job, List<ProposedTriple> triples) {
        List<TripleProposal> proposals = new ArrayList<>();

        for (ProposedTriple triple : triples) {
            TripleProposal proposal = TripleProposal.builder()
                    .job(job)
                    .factSheetId(job.getFactSheetId())
                    .subjectName(triple.subjectName())
                    .subjectType(triple.subjectType())
                    .predicateName(triple.predicateName())
                    .objectName(triple.objectName())
                    .objectType(triple.objectType())
                    .confidence(triple.confidence())
                    .sourceChunkId(triple.sourceChunkId())
                    .sourceDocumentId(triple.sourceDocumentId())
                    .sourceContext(triple.sourceContext())
                    .status(ProposalStatus.PENDING)
                    .build();

            if (triple.metadata() != null && !triple.metadata().isEmpty()) {
                try {
                    proposal.setMetadataJson(objectMapper.writeValueAsString(triple.metadata()));
                } catch (JsonProcessingException e) {
                    log.warn("Failed to serialize proposal metadata", e);
                }
            }

            proposals.add(proposal);
        }

        return proposalRepository.saveAll(proposals);
    }

    /**
     * Create proposals from extracted triples using job ID.
     *
     * @param jobId the job ID
     * @param factSheetId the fact sheet ID
     * @param triples the proposed triples
     * @return the number of proposals created
     */
    @Transactional
    public int createProposalsFromTriples(String jobId, Long factSheetId, List<ProposedTriple> triples) {
        if (triples == null || triples.isEmpty()) {
            return 0;
        }

        ExtractionJob job = jobRepository.findByJobId(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        List<TripleProposal> created = createProposals(job, triples);
        return created.size();
    }

    /**
     * Create a manual proposal.
     */
    @Transactional
    public TripleProposal createManualProposal(
            Long factSheetId,
            String subjectName, String subjectType,
            String predicateName,
            String objectName, String objectType,
            String description,
            boolean autoAccept) {

        // Find or create a manual job for this fact sheet
        ExtractionJob job = findOrCreateManualJob(factSheetId);

        TripleProposal proposal = TripleProposal.builder()
                .job(job)
                .factSheetId(factSheetId)
                .subjectName(subjectName)
                .subjectType(subjectType)
                .predicateName(predicateName)
                .objectName(objectName)
                .objectType(objectType)
                .subjectDescription(description)
                .confidence(1.0) // Manual proposals have full confidence
                .status(autoAccept ? ProposalStatus.ACCEPTED : ProposalStatus.PENDING)
                .build();

        if (autoAccept) {
            proposal.setReviewedAt(LocalDateTime.now());
            proposal.setReviewedBy("manual");
        }

        TripleProposal saved = proposalRepository.save(proposal);

        // If auto-accept, create the graph nodes/edges
        if (autoAccept) {
            acceptProposalInternal(saved, "manual");
        }

        return saved;
    }

    private ExtractionJob findOrCreateManualJob(Long factSheetId) {
        List<ExtractionJob> manualJobs = jobRepository.findByFactSheetIdAndStatus(factSheetId, JobStatus.RUNNING);
        for (ExtractionJob job : manualJobs) {
            if ("manual".equals(job.getBuilderType())) {
                return job;
            }
        }

        // Create a new manual job
        ExtractionJob job = ExtractionJob.builder()
                .factSheetId(factSheetId)
                .builderType("manual")
                .status(JobStatus.RUNNING)
                .jobName("Manual Triple Creation")
                .build();
        job.start();
        return jobRepository.save(job);
    }

    /**
     * Get a proposal by ID.
     */
    public Optional<TripleProposal> getProposal(String proposalId) {
        return proposalRepository.findByProposalId(proposalId);
    }

    /**
     * Get proposals for a job.
     */
    public Page<TripleProposal> getProposalsForJob(String jobId, Pageable pageable) {
        return proposalRepository.findByJobId(jobId, pageable);
    }

    /**
     * Get proposals for a fact sheet.
     */
    public Page<TripleProposal> getProposalsForFactSheet(Long factSheetId, Pageable pageable) {
        return proposalRepository.findByFactSheetId(factSheetId, pageable);
    }

    /**
     * Get proposals by status.
     */
    public Page<TripleProposal> getProposalsByStatus(Long factSheetId, ProposalStatus status, Pageable pageable) {
        return proposalRepository.findByFactSheetIdAndStatus(factSheetId, status, pageable);
    }

    /**
     * Search proposals.
     */
    public Page<TripleProposal> searchProposals(Long factSheetId, String query, Pageable pageable) {
        return proposalRepository.searchByFactSheetAndQuery(factSheetId, query, pageable);
    }

    /**
     * Accept a proposal and create graph nodes/edges using the default storage.
     */
    @Transactional
    public TripleProposal acceptProposal(String proposalId, String reviewedBy) {
        return acceptProposal(proposalId, reviewedBy, null);
    }

    /**
     * Accept a proposal and create graph nodes/edges using the specified storage type.
     *
     * @param proposalId the proposal ID
     * @param reviewedBy who reviewed this proposal
     * @param storageType the storage type ("jpa", "neo4j"), or null for default
     */
    @Transactional
    public TripleProposal acceptProposal(String proposalId, String reviewedBy, String storageType) {
        TripleProposal proposal = proposalRepository.findByProposalId(proposalId)
                .orElseThrow(() -> new IllegalArgumentException("Proposal not found: " + proposalId));

        if (!proposal.isPending()) {
            throw new IllegalStateException("Proposal is not pending: " + proposal.getStatus());
        }

        proposal.accept(reviewedBy);
        acceptProposalInternal(proposal, reviewedBy, storageType);

        return proposalRepository.save(proposal);
    }

    private void acceptProposalInternal(TripleProposal proposal, String reviewedBy) {
        acceptProposalInternal(proposal, reviewedBy, null);
    }

    private void acceptProposalInternal(TripleProposal proposal, String reviewedBy, String storageType) {
        // Get the appropriate storage strategy
        GraphStorageStrategy storage = storageRegistry.getStrategyWithFallback(storageType);

        log.debug("Accepting proposal {} using storage: {}", proposal.getProposalId(), storage.getStorageType());

        // Store the proposal using the selected strategy
        StorageResult result = storage.storeProposal(proposal);

        if (result.success()) {
            proposal.setSubjectNodeId(result.subjectNodeId());
            proposal.setObjectNodeId(result.objectNodeId());
            proposal.setEdgeId(result.edgeId());
        } else {
            log.warn("Storage failed for proposal {}: {}, falling back to JPA",
                    proposal.getProposalId(), result.errorMessage());

            // Fall back to JPA if the primary storage failed
            if (!"jpa".equalsIgnoreCase(storage.getStorageType())) {
                GraphStorageStrategy jpaStorage = storageRegistry.getStrategy("jpa")
                        .orElseThrow(() -> new IllegalStateException("JPA storage not available"));
                StorageResult fallbackResult = jpaStorage.storeProposal(proposal);
                if (fallbackResult.success()) {
                    proposal.setSubjectNodeId(fallbackResult.subjectNodeId());
                    proposal.setObjectNodeId(fallbackResult.objectNodeId());
                    proposal.setEdgeId(fallbackResult.edgeId());
                } else {
                    throw new RuntimeException("Failed to store proposal: " + fallbackResult.errorMessage());
                }
            } else {
                throw new RuntimeException("Failed to store proposal: " + result.errorMessage());
            }
        }

        // Update job counters
        ExtractionJob job = proposal.getJob();
        if (job.getProposalsAccepted() == null) {
            job.setProposalsAccepted(0);
        }
        job.setProposalsAccepted(job.getProposalsAccepted() + 1);
        jobRepository.save(job);
    }

    private GraphNode findOrCreateEntityNode(Long factSheetId, String name, String type, String description) {
        // Generate external ID from name and type
        String externalId = "entity_" + name.toLowerCase().replaceAll("[^a-z0-9]", "_");

        Optional<GraphNode> existing = nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(
                externalId, NodeLevel.ENTITY, factSheetId);

        if (existing.isPresent()) {
            return existing.get();
        }

        // Create new entity node
        GraphNode node = GraphNode.builder()
                .nodeType(NodeLevel.ENTITY)
                .externalId(externalId)
                .title(name)
                .description(description)
                .factSheetId(factSheetId)
                .metadataJson("{\"entityType\": \"" + (type != null ? type : "UNKNOWN") + "\"}")
                .build();

        return nodeRepository.save(node);
    }

    private GraphEdge createEdgeForProposal(GraphNode source, GraphNode target, TripleProposal proposal) {
        GraphEdge edge = GraphEdge.builder()
                .sourceNode(source)
                .targetNode(target)
                .edgeType(EdgeType.USER_DEFINED) // Or a new EXTRACTED type
                .label(proposal.getPredicateName())
                .description(proposal.getPredicateName() + " relationship")
                .weight(proposal.getConfidence())
                .factSheetId(proposal.getFactSheetId())
                .bidirectional(false)
                .build();

        GraphEdge saved = edgeRepository.save(edge);

        // Update edge counts
        source.incrementEdgeCount();
        target.incrementEdgeCount();
        nodeRepository.save(source);
        nodeRepository.save(target);

        return saved;
    }

    /**
     * Reject a proposal.
     */
    @Transactional
    public TripleProposal rejectProposal(String proposalId, String reviewedBy, String reason) {
        TripleProposal proposal = proposalRepository.findByProposalId(proposalId)
                .orElseThrow(() -> new IllegalArgumentException("Proposal not found: " + proposalId));

        if (!proposal.isPending()) {
            throw new IllegalStateException("Proposal is not pending: " + proposal.getStatus());
        }

        proposal.reject(reviewedBy, reason);

        // Update job counters
        ExtractionJob job = proposal.getJob();
        if (job.getProposalsRejected() == null) {
            job.setProposalsRejected(0);
        }
        job.setProposalsRejected(job.getProposalsRejected() + 1);
        jobRepository.save(job);

        return proposalRepository.save(proposal);
    }

    /**
     * Bulk accept proposals using the default storage.
     */
    @Transactional
    public int bulkAcceptProposals(List<String> proposalIds, String reviewedBy) {
        return bulkAcceptProposals(proposalIds, reviewedBy, null);
    }

    /**
     * Bulk accept proposals using the specified storage type.
     *
     * @param proposalIds the proposal IDs to accept
     * @param reviewedBy who reviewed these proposals
     * @param storageType the storage type ("jpa", "neo4j"), or null for default
     */
    @Transactional
    public int bulkAcceptProposals(List<String> proposalIds, String reviewedBy, String storageType) {
        List<TripleProposal> proposals = proposalRepository.findByProposalIds(proposalIds);
        int accepted = 0;

        for (TripleProposal proposal : proposals) {
            if (proposal.isPending()) {
                proposal.accept(reviewedBy);
                acceptProposalInternal(proposal, reviewedBy, storageType);
                proposalRepository.save(proposal);
                accepted++;
            }
        }

        return accepted;
    }

    /**
     * Bulk reject proposals.
     */
    @Transactional
    public int bulkRejectProposals(List<String> proposalIds, String reviewedBy, String reason) {
        return proposalRepository.bulkReject(proposalIds, LocalDateTime.now(), reviewedBy, reason);
    }

    /**
     * Auto-accept high-confidence proposals using the default storage.
     */
    @Transactional
    public int autoAcceptHighConfidence(String jobId, double threshold) {
        return autoAcceptHighConfidence(jobId, threshold, null);
    }

    /**
     * Auto-accept high-confidence proposals using the specified storage type.
     *
     * @param jobId the job ID
     * @param threshold the minimum confidence threshold
     * @param storageType the storage type ("jpa", "neo4j"), or null for default
     */
    @Transactional
    public int autoAcceptHighConfidence(String jobId, double threshold, String storageType) {
        List<TripleProposal> highConfidence = proposalRepository.findHighConfidencePending(threshold);
        int accepted = 0;

        for (TripleProposal proposal : highConfidence) {
            if (proposal.getJob().getJobId().equals(jobId)) {
                proposal.accept("auto");
                acceptProposalInternal(proposal, "auto", storageType);
                proposalRepository.save(proposal);
                accepted++;
            }
        }

        return accepted;
    }

    // ==================== Storage Info ====================

    /**
     * Get available storage types.
     */
    public List<String> getAvailableStorageTypes() {
        return storageRegistry.getAvailableStorageTypes();
    }

    /**
     * Get storage info for all registered strategies.
     */
    public List<GraphStorageRegistry.StorageInfo> getStorageInfo() {
        return storageRegistry.getStorageInfo();
    }

    /**
     * Get the default storage type.
     */
    public String getDefaultStorageType() {
        return storageRegistry.getDefaultStorageType();
    }

    // ==================== Log Management ====================

    /**
     * Save an extraction log record.
     */
    @Transactional
    public ExtractionLogRecord saveLog(ExtractionLogRecord log) {
        return logRepository.save(log);
    }

    /**
     * Get logs for a job.
     */
    public Page<ExtractionLogRecord> getLogsForJob(String jobId, Pageable pageable) {
        return logRepository.findByJobId(jobId, pageable);
    }

    /**
     * Get log for a specific chunk.
     */
    public Optional<ExtractionLogRecord> getLogForChunk(String jobId, String chunkId) {
        return logRepository.findByJobIdAndChunkId(jobId, chunkId);
    }

    /**
     * Get job statistics.
     */
    public Map<String, Object> getJobStatistics(String jobId) {
        Map<String, Object> stats = new HashMap<>();

        stats.put("totalLogs", logRepository.countByJobId(jobId));
        stats.put("successfulLogs", logRepository.countSuccessfulByJobId(jobId));
        stats.put("failedLogs", logRepository.countFailedByJobId(jobId));
        stats.put("averageLatencyMs", logRepository.getAverageLatencyByJobId(jobId));
        stats.put("totalTokens", logRepository.getTotalTokensByJobId(jobId));
        stats.put("totalEntities", logRepository.getTotalEntitiesByJobId(jobId));
        stats.put("totalRelationships", logRepository.getTotalRelationshipsByJobId(jobId));

        return stats;
    }

    // ==================== Cleanup ====================

    /**
     * Delete old completed jobs and their data.
     */
    @Transactional
    public int cleanupOldJobs(int daysOld) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(daysOld);
        return jobRepository.deleteOldCompletedJobs(cutoff);
    }
}
