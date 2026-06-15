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

import ai.kompile.orchestrator.api.StateMachineService;
import ai.kompile.orchestrator.config.OrchestratorProperties;
import ai.kompile.orchestrator.model.OrchestratorInstance;
import ai.kompile.orchestrator.model.OrchestratorStatus;
import ai.kompile.orchestrator.model.snapshot.OrchestratorSnapshot;
import ai.kompile.orchestrator.model.task.TaskInstance;
import ai.kompile.orchestrator.model.task.TaskStatus;
import ai.kompile.orchestrator.model.workflow.Workflow;
import ai.kompile.orchestrator.model.workflow.WorkflowStatus;
import ai.kompile.orchestrator.repository.OrchestratorInstanceRepository;
import ai.kompile.orchestrator.repository.OrchestratorSnapshotRepository;
import ai.kompile.orchestrator.repository.TaskInstanceRepository;
import ai.kompile.orchestrator.repository.WorkflowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service for recovering orchestrators from crashes.
 */
@Slf4j
@RequiredArgsConstructor
public class RecoveryService {

    private final OrchestratorSnapshotRepository snapshotRepository;
    private final OrchestratorInstanceRepository instanceRepository;
    private final StateMachineService stateMachineService;
    private final TaskInstanceRepository taskInstanceRepository;
    private final WorkflowRepository workflowRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final OrchestratorProperties properties;

    private SnapshotService snapshotService;
    private AuditService auditService;

    public void setSnapshotService(SnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    public void setAuditService(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * Recover all orchestrators that were running when the system crashed.
     */
    public void recoverAllOnStartup() {
        if (!properties.isRecoveryOnStartup()) {
            log.info("Recovery on startup is disabled");
            return;
        }

        log.info("Starting orchestrator recovery...");

        // Find orchestrators that were active
        List<OrchestratorInstance> activeInstances = instanceRepository.findAllActive();

        for (OrchestratorInstance instance : activeInstances) {
            try {
                boolean recovered = recoverOrchestrator(instance.getInstanceId());
                log.info("Recovery of orchestrator {}: {}", instance.getInstanceId(), recovered ? "success" : "failed");
            } catch (Exception e) {
                log.error("Error recovering orchestrator {}: {}", instance.getInstanceId(), e.getMessage(), e);
            }
        }

        log.info("Orchestrator recovery complete");
    }

    /**
     * Recover a specific orchestrator from its latest snapshot.
     */
    public boolean recoverOrchestrator(String orchestratorInstanceId) {
        OrchestratorInstance instance = instanceRepository.findById(orchestratorInstanceId)
                .orElse(null);

        if (instance == null) {
            log.warn("Cannot recover: orchestrator not found: {}", orchestratorInstanceId);
            return false;
        }

        // Get latest snapshot
        Optional<OrchestratorSnapshot> snapshotOpt = snapshotRepository
                .findLatestActive(orchestratorInstanceId);

        if (snapshotOpt.isEmpty()) {
            log.warn("No snapshot found for orchestrator: {}", orchestratorInstanceId);
            return recoverWithoutSnapshot(instance);
        }

        OrchestratorSnapshot snapshot = snapshotOpt.get();
        return recoverFromSnapshot(instance, snapshot);
    }

    /**
     * Recover from a specific snapshot.
     */
    public boolean recoverFromSnapshot(OrchestratorInstance instance, OrchestratorSnapshot snapshot) {
        log.info("Recovering orchestrator {} from snapshot {} (state: {})",
                instance.getInstanceId(), snapshot.getId(), snapshot.getCurrentStateId());

        try {
            // Mark as recovering
            instance.setStatus(OrchestratorStatus.RECOVERING);
            instanceRepository.save(instance);

            // Restore state
            instance.setCurrentStateId(snapshot.getCurrentStateId());
            instance.setPreviousStateId(snapshot.getPreviousStateId());

            // Restore context
            if (snapshotService != null) {
                Map<String, Object> context = snapshotService.restoreContext(snapshot);
                instance.setContext(context);
            }

            // Handle running tasks
            Long[] runningTaskIds = snapshot.getRunningTaskIdArray();
            if (runningTaskIds != null && runningTaskIds.length > 0) {
                for (Long taskId : runningTaskIds) {
                    handleInterruptedTask(taskId);
                }
            }

            // Handle active workflow (singular)
            if (snapshot.getActiveWorkflowId() != null) {
                handleInterruptedWorkflow(snapshot.getActiveWorkflowId());
            }

            // Restore orchestrator to running state
            instance.setStatus(OrchestratorStatus.RUNNING);
            instance = instanceRepository.save(instance);

            // Mark snapshot as used
            if (snapshotService != null) {
                snapshotService.markSnapshotUsedForRecovery(snapshot.getId());
            }

            // Log to audit
            if (auditService != null) {
                auditService.logOrchestratorRecovery(instance, true);
            }

            // Resume state machine processing
            if (instance.getCurrentStateId() != null) {
                stateMachineService.forceTransitionTo(instance.getInstanceId(), instance.getCurrentStateId());
            }

            log.info("Successfully recovered orchestrator {}", instance.getInstanceId());
            return true;

        } catch (Exception e) {
            log.error("Failed to recover orchestrator {}: {}", instance.getInstanceId(), e.getMessage(), e);
            instance.setStatus(OrchestratorStatus.FAILED);
            instance.setErrorMessage("Recovery failed: " + e.getMessage());
            instanceRepository.save(instance);

            if (auditService != null) {
                auditService.logOrchestratorRecovery(instance, false);
            }

            return false;
        }
    }

    /**
     * Recover without a snapshot (best effort).
     */
    private boolean recoverWithoutSnapshot(OrchestratorInstance instance) {
        log.info("Recovering orchestrator {} without snapshot", instance.getInstanceId());

        try {
            // Mark as recovering
            instance.setStatus(OrchestratorStatus.RECOVERING);
            instanceRepository.save(instance);

            // Mark any running tasks as failed
            List<TaskInstance> runningTasks = taskInstanceRepository
                    .findByOrchestratorInstanceIdAndStatus(instance.getInstanceId(), TaskStatus.RUNNING);
            for (TaskInstance task : runningTasks) {
                handleInterruptedTask(task.getId());
            }

            // Mark active workflows as paused
            List<Workflow> activeWorkflows = workflowRepository
                    .findByOrchestratorInstanceIdAndStatusIn(instance.getInstanceId(),
                            List.of(WorkflowStatus.IN_PROGRESS));
            for (Workflow workflow : activeWorkflows) {
                handleInterruptedWorkflow(workflow.getId());
            }

            // If instance has a current state, try to resume
            if (instance.getCurrentStateId() != null) {
                instance.setStatus(OrchestratorStatus.RUNNING);
            } else {
                // No state to recover to
                instance.setStatus(OrchestratorStatus.PAUSED);
                log.warn("Orchestrator {} recovered to PAUSED state (no current state)", instance.getInstanceId());
            }

            instanceRepository.save(instance);

            if (auditService != null) {
                auditService.logOrchestratorRecovery(instance, true);
            }

            return true;

        } catch (Exception e) {
            log.error("Failed to recover orchestrator {} without snapshot: {}",
                    instance.getInstanceId(), e.getMessage(), e);
            instance.setStatus(OrchestratorStatus.FAILED);
            instance.setErrorMessage("Recovery failed: " + e.getMessage());
            instanceRepository.save(instance);

            if (auditService != null) {
                auditService.logOrchestratorRecovery(instance, false);
            }

            return false;
        }
    }

    /**
     * Handle a task that was running when the crash occurred.
     */
    private void handleInterruptedTask(Long taskId) {
        taskInstanceRepository.findById(taskId).ifPresent(task -> {
            if (task.getStatus() == TaskStatus.RUNNING) {
                task.setStatus(TaskStatus.FAILED);
                task.setErrorMessage("Task interrupted by system restart");
                task.setEndTime(LocalDateTime.now());
                taskInstanceRepository.save(task);
                log.info("Marked interrupted task {} as failed", taskId);
            }
        });
    }

    /**
     * Handle a workflow that was active when the crash occurred.
     */
    private void handleInterruptedWorkflow(Long workflowId) {
        workflowRepository.findById(workflowId).ifPresent(workflow -> {
            if (workflow.getStatus() == WorkflowStatus.IN_PROGRESS) {
                workflow.setStatus(WorkflowStatus.PAUSED);
                workflowRepository.save(workflow);
                log.info("Paused interrupted workflow {}", workflowId);
            }
        });
    }

    /**
     * Check if an orchestrator can be recovered.
     */
    public boolean canRecover(String orchestratorInstanceId) {
        Optional<OrchestratorSnapshot> snapshot = snapshotRepository
                .findLatestActive(orchestratorInstanceId);
        return snapshot.isPresent();
    }

    /**
     * Get recovery information for an orchestrator.
     */
    public Map<String, Object> getRecoveryInfo(String orchestratorInstanceId) {
        Optional<OrchestratorSnapshot> snapshotOpt = snapshotRepository
                .findLatestActive(orchestratorInstanceId);

        if (snapshotOpt.isEmpty()) {
            return Map.of(
                    "canRecover", false,
                    "reason", "No snapshot available"
            );
        }

        OrchestratorSnapshot snapshot = snapshotOpt.get();
        Long[] taskIds = snapshot.getRunningTaskIdArray();
        return Map.of(
                "canRecover", true,
                "snapshotId", snapshot.getId(),
                "snapshotTime", snapshot.getSnapshotTime(),
                "currentStateId", snapshot.getCurrentStateId(),
                "runningTaskCount", taskIds != null ? taskIds.length : 0,
                "activeWorkflowId", snapshot.getActiveWorkflowId() != null ? snapshot.getActiveWorkflowId() : 0L
        );
    }
}
