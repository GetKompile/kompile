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
package ai.kompile.orchestrator.service.impl;

import ai.kompile.orchestrator.config.OrchestratorProperties;
import ai.kompile.orchestrator.model.OrchestratorInstance;
import ai.kompile.orchestrator.model.snapshot.OrchestratorSnapshot;
import ai.kompile.orchestrator.model.task.TaskInstance;
import ai.kompile.orchestrator.model.task.TaskStatus;
import ai.kompile.orchestrator.model.workflow.Workflow;
import ai.kompile.orchestrator.model.workflow.WorkflowStatus;
import ai.kompile.orchestrator.repository.OrchestratorInstanceRepository;
import ai.kompile.orchestrator.repository.OrchestratorSnapshotRepository;
import ai.kompile.orchestrator.repository.TaskInstanceRepository;
import ai.kompile.orchestrator.repository.WorkflowRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for creating and managing orchestrator snapshots.
 */
@Slf4j
@RequiredArgsConstructor
public class SnapshotService {

    private final OrchestratorSnapshotRepository snapshotRepository;
    private final OrchestratorInstanceRepository instanceRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final OrchestratorProperties properties;

    private TaskInstanceRepository taskInstanceRepository;
    private WorkflowRepository workflowRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void setTaskInstanceRepository(TaskInstanceRepository taskInstanceRepository) {
        this.taskInstanceRepository = taskInstanceRepository;
    }

    public void setWorkflowRepository(WorkflowRepository workflowRepository) {
        this.workflowRepository = workflowRepository;
    }

    /**
     * Create a snapshot of the current orchestrator state.
     */
    public OrchestratorSnapshot createSnapshot(String orchestratorInstanceId) {
        OrchestratorInstance instance = instanceRepository.findById(orchestratorInstanceId)
                .orElseThrow(() -> new IllegalArgumentException("Orchestrator not found: " + orchestratorInstanceId));

        log.debug("Creating snapshot for orchestrator: {}", orchestratorInstanceId);

        // Gather running task IDs
        Set<Long> runningTaskIds = new HashSet<>();
        if (taskInstanceRepository != null) {
            List<TaskInstance> runningTasks = taskInstanceRepository
                    .findByOrchestratorInstanceIdAndStatus(orchestratorInstanceId, TaskStatus.RUNNING);
            runningTaskIds = runningTasks.stream()
                    .map(TaskInstance::getId)
                    .collect(Collectors.toSet());
        }

        // Gather active workflow IDs
        Set<Long> activeWorkflowIds = new HashSet<>();
        if (workflowRepository != null) {
            List<Workflow> activeWorkflows = workflowRepository
                    .findByOrchestratorInstanceIdAndStatusIn(orchestratorInstanceId,
                            List.of(WorkflowStatus.IN_PROGRESS, WorkflowStatus.PAUSED));
            activeWorkflowIds = activeWorkflows.stream()
                    .map(Workflow::getId)
                    .collect(Collectors.toSet());
        }

        // Serialize context
        String contextJson = null;
        if (instance.getContext() != null) {
            try {
                contextJson = objectMapper.writeValueAsString(instance.getContext());
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize context for snapshot", e);
            }
        }

        // Create snapshot - convert status to string and task IDs to comma-separated string
        String runningTaskIdsStr = runningTaskIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        Long activeWorkflowId = activeWorkflowIds.isEmpty() ? null : activeWorkflowIds.iterator().next();

        OrchestratorSnapshot snapshot = OrchestratorSnapshot.builder()
                .orchestratorInstanceId(orchestratorInstanceId)
                .currentStateId(instance.getCurrentStateId())
                .previousStateId(instance.getPreviousStateId())
                .orchestratorStatus(instance.getStatus() != null ? instance.getStatus().name() : null)
                .contextJson(contextJson)
                .runningTaskIds(runningTaskIdsStr.isEmpty() ? null : runningTaskIdsStr)
                .activeWorkflowId(activeWorkflowId)
                .snapshotTime(LocalDateTime.now())
                .build();

        snapshot = snapshotRepository.save(snapshot);
        log.info("Created snapshot {} for orchestrator {}", snapshot.getId(), orchestratorInstanceId);

        return snapshot;
    }

    /**
     * Get the latest snapshot for an orchestrator.
     */
    public Optional<OrchestratorSnapshot> getLatestSnapshot(String orchestratorInstanceId) {
        return snapshotRepository.findLatestActive(orchestratorInstanceId);
    }

    /**
     * Get all snapshots for an orchestrator.
     */
    public List<OrchestratorSnapshot> getSnapshots(String orchestratorInstanceId) {
        return snapshotRepository.findByOrchestratorInstanceIdOrderBySnapshotTimeDesc(orchestratorInstanceId);
    }

    /**
     * Get a specific snapshot.
     */
    public Optional<OrchestratorSnapshot> getSnapshot(Long snapshotId) {
        return snapshotRepository.findById(snapshotId);
    }

    /**
     * Delete old snapshots, keeping only the most recent ones.
     */
    public void cleanupSnapshots(String orchestratorInstanceId, int keepCount) {
        List<OrchestratorSnapshot> snapshots = snapshotRepository
                .findByOrchestratorInstanceIdOrderBySnapshotTimeDesc(orchestratorInstanceId);

        if (snapshots.size() > keepCount) {
            List<OrchestratorSnapshot> toDelete = snapshots.subList(keepCount, snapshots.size());
            snapshotRepository.deleteAll(toDelete);
            log.info("Deleted {} old snapshots for orchestrator {}", toDelete.size(), orchestratorInstanceId);
        }
    }

    /**
     * Delete all snapshots for an orchestrator.
     */
    public void deleteSnapshots(String orchestratorInstanceId) {
        snapshotRepository.deactivateSnapshots(orchestratorInstanceId);
        snapshotRepository.deleteInactiveSnapshots(orchestratorInstanceId);
        log.info("Deleted all snapshots for orchestrator {}", orchestratorInstanceId);
    }

    /**
     * Scheduled task to create snapshots for all active orchestrators.
     */
    @Scheduled(fixedDelayString = "${kompile.orchestrator.snapshot-interval:30000}")
    public void createScheduledSnapshots() {
        List<OrchestratorInstance> activeInstances = instanceRepository.findAllActive();

        for (OrchestratorInstance instance : activeInstances) {
            try {
                createSnapshot(instance.getInstanceId());
            } catch (Exception e) {
                log.error("Failed to create snapshot for {}: {}", instance.getInstanceId(), e.getMessage());
            }
        }
    }

    /**
     * Restore context from a snapshot.
     */
    public Map<String, Object> restoreContext(OrchestratorSnapshot snapshot) {
        if (snapshot.getContextJson() == null) {
            return new HashMap<>();
        }

        try {
            return objectMapper.readValue(snapshot.getContextJson(),
                    new TypeReference<HashMap<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to restore context from snapshot", e);
            return new HashMap<>();
        }
    }

    /**
     * Mark a snapshot as used for recovery.
     */
    public void markSnapshotUsedForRecovery(Long snapshotId) {
        snapshotRepository.findById(snapshotId).ifPresent(snapshot -> {
            snapshot.setRecoveryNotes("Recovered at: " + LocalDateTime.now());
            snapshot.setActive(false);
            snapshotRepository.save(snapshot);
        });
    }
}
