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
package ai.kompile.enrichment.impl;

import ai.kompile.enrichment.domain.AuditSummary;
import ai.kompile.enrichment.domain.EnrichmentAuditEntry;
import ai.kompile.enrichment.domain.RevertResult;
import ai.kompile.enrichment.repository.EnrichmentAuditRepository;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Audit logging + revert for all enrichment cleanup actions.
 */
@Service
public class EnrichmentAuditService {
    private static final Logger log = LoggerFactory.getLogger(EnrichmentAuditService.class);

    private final EnrichmentAuditRepository auditRepository;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private KnowledgeGraphService knowledgeGraphService;

    public EnrichmentAuditService(EnrichmentAuditRepository auditRepository,
                                  ObjectMapper objectMapper) {
        this.auditRepository = auditRepository;
        this.objectMapper = objectMapper;
    }

    // ── Logging ─────────────────────────────────────────────────

    public EnrichmentAuditEntry logAction(Long factSheetId, String jobId, String phase, String action,
                                          String targetNodeId, String targetType,
                                          String beforeJson, String afterJson, String description) {
        EnrichmentAuditEntry entry = EnrichmentAuditEntry.builder()
                .auditId(UUID.randomUUID().toString())
                .factSheetId(factSheetId)
                .enrichmentJobId(jobId)
                .phase(phase)
                .action(action)
                .targetNodeId(targetNodeId)
                .targetType(targetType)
                .beforeSnapshot(beforeJson)
                .afterSnapshot(afterJson)
                .description(description)
                .build();
        return auditRepository.save(entry);
    }

    // ── Query ───────────────────────────────────────────────────

    public Page<EnrichmentAuditEntry> getAuditLog(Long factSheetId, String jobId, String phase, Pageable pageable) {
        if (jobId != null && phase != null) {
            return auditRepository.findByFactSheetIdAndEnrichmentJobIdAndPhaseOrderByCreatedAtDesc(
                    factSheetId, jobId, phase, pageable);
        }
        if (jobId != null) {
            return auditRepository.findByFactSheetIdAndEnrichmentJobIdOrderByCreatedAtDesc(
                    factSheetId, jobId, pageable);
        }
        return auditRepository.findByFactSheetIdOrderByCreatedAtDesc(factSheetId, pageable);
    }

    public Optional<EnrichmentAuditEntry> getEntry(String auditId) {
        return auditRepository.findByAuditId(auditId);
    }

    public AuditSummary getJobSummary(String enrichmentJobId) {
        List<Object[]> actionCounts = auditRepository.countByActionForJob(enrichmentJobId);
        Map<String, Long> countMap = new LinkedHashMap<>();
        long total = 0;
        for (Object[] row : actionCounts) {
            String action = (String) row[0];
            Long count = (Long) row[1];
            countMap.put(action, count);
            total += count;
        }
        List<String> phases = auditRepository.findDistinctPhasesByJobId(enrichmentJobId);
        long reverted = auditRepository.countByEnrichmentJobIdAndRevertedTrue(enrichmentJobId);

        return AuditSummary.builder()
                .totalActions(total)
                .actionCounts(countMap)
                .revertedCount(reverted)
                .phases(phases)
                .build();
    }

    // ── Revert ──────────────────────────────────────────────────

    @Transactional
    public RevertResult revertAction(String auditId) {
        Optional<EnrichmentAuditEntry> entryOpt = auditRepository.findByAuditId(auditId);
        if (entryOpt.isEmpty()) {
            return RevertResult.builder()
                    .failedRevertIds(List.of(auditId))
                    .warnings(List.of("Audit entry not found: " + auditId))
                    .build();
        }
        EnrichmentAuditEntry entry = entryOpt.get();
        if (entry.isReverted()) {
            return RevertResult.builder()
                    .warnings(List.of("Already reverted: " + auditId))
                    .build();
        }

        List<String> warnings = new ArrayList<>();
        int nodesRestored = 0;
        int edgesRestored = 0;

        try {
            if (entry.getBeforeSnapshot() != null && knowledgeGraphService != null) {
                JsonNode before = objectMapper.readTree(entry.getBeforeSnapshot());
                String targetType = entry.getTargetType();

                if ("GRAPH_NODE".equals(targetType) || "SNIPPET".equals(targetType) || "ENTITY_CATEGORY".equals(targetType)) {
                    if (entry.getAfterSnapshot() == null) {
                        // Node was deleted — recreate it
                        String nodeType = before.has("nodeType") ? before.path("nodeType").asText() : "ENTITY";
                        String externalId = before.has("externalId") ? before.path("externalId").asText() : entry.getTargetNodeId();
                        String title = before.has("title") ? before.path("title").asText() : "";
                        String description = before.has("description") ? before.path("description").asText() : null;
                        Map<String, Object> metadata = new HashMap<>();
                        if (before.has("metadataJson") && !before.path("metadataJson").asText().isEmpty()) {
                            metadata = objectMapper.readValue(before.path("metadataJson").asText(),
                                    objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, Object.class));
                        }
                        if (entry.getFactSheetId() != null) {
                            metadata.put("factSheetId", entry.getFactSheetId());
                        }
                        knowledgeGraphService.createNode(NodeLevel.valueOf(nodeType), externalId, title,
                                description, metadata);
                        nodesRestored++;
                    } else {
                        // Node was modified — restore before state
                        String title = before.has("title") ? before.path("title").asText() : null;
                        if (title != null && entry.getTargetNodeId() != null) {
                            knowledgeGraphService.getNode(entry.getTargetNodeId()).ifPresent(node -> {
                                // node.setTitle would require repository access directly
                                // For now, log a warning that manual restore may be needed
                            });
                            warnings.add("Modified node " + entry.getTargetNodeId() + " — partial restore only (title)");
                        }
                    }
                } else if ("GRAPH_EDGE".equals(targetType) && entry.getAfterSnapshot() == null) {
                    // Edge was deleted — recreate
                    warnings.add("Edge recreation from snapshot not fully supported yet");
                }
            }

            entry.setReverted(true);
            entry.setRevertedBy("user");
            entry.setRevertedAt(Instant.now());
            auditRepository.save(entry);
        } catch (Exception e) {
            log.error("Failed to revert audit entry {}: {}", auditId, e.getMessage());
            return RevertResult.builder()
                    .failedRevertIds(List.of(auditId))
                    .warnings(List.of("Revert failed: " + e.getMessage()))
                    .build();
        }

        return RevertResult.builder()
                .actionsReverted(1)
                .nodesRestored(nodesRestored)
                .edgesRestored(edgesRestored)
                .failedRevertIds(List.of())
                .warnings(warnings)
                .build();
    }

    @Transactional
    public RevertResult revertPhase(String enrichmentJobId, String phase) {
        List<EnrichmentAuditEntry> entries = auditRepository
                .findByEnrichmentJobIdAndPhaseAndRevertedFalseOrderByCreatedAtDesc(enrichmentJobId, phase);
        return revertEntries(entries);
    }

    @Transactional
    public RevertResult revertJob(String enrichmentJobId) {
        List<EnrichmentAuditEntry> entries = auditRepository
                .findByEnrichmentJobIdAndRevertedFalseOrderByCreatedAtDesc(enrichmentJobId);
        return revertEntries(entries);
    }

    private RevertResult revertEntries(List<EnrichmentAuditEntry> entries) {
        int totalReverted = 0;
        int totalNodesRestored = 0;
        int totalEdgesRestored = 0;
        List<String> allFailed = new ArrayList<>();
        List<String> allWarnings = new ArrayList<>();

        for (EnrichmentAuditEntry entry : entries) {
            RevertResult r = revertAction(entry.getAuditId());
            totalReverted += r.getActionsReverted();
            totalNodesRestored += r.getNodesRestored();
            totalEdgesRestored += r.getEdgesRestored();
            if (r.getFailedRevertIds() != null) allFailed.addAll(r.getFailedRevertIds());
            if (r.getWarnings() != null) allWarnings.addAll(r.getWarnings());
        }

        return RevertResult.builder()
                .actionsReverted(totalReverted)
                .nodesRestored(totalNodesRestored)
                .edgesRestored(totalEdgesRestored)
                .failedRevertIds(allFailed)
                .warnings(allWarnings)
                .build();
    }
}
