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
package ai.kompile.orchestrator.repository;

import ai.kompile.orchestrator.model.workflow.Workflow;
import ai.kompile.orchestrator.model.workflow.WorkflowStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for workflows.
 */
@Repository
public interface WorkflowRepository extends JpaRepository<Workflow, Long> {

    List<Workflow> findByOrchestratorInstanceId(String orchestratorInstanceId);

    List<Workflow> findByOrchestratorInstanceIdAndStatus(String orchestratorInstanceId, WorkflowStatus status);

    @Query("SELECT w FROM Workflow w WHERE w.orchestratorInstanceId = :orchestratorInstanceId AND w.status IN ('IN_PROGRESS', 'WAITING_APPROVAL', 'WAITING_TASK', 'WAITING_LLM', 'PAUSED')")
    List<Workflow> findActiveByOrchestratorInstanceId(String orchestratorInstanceId);

    @Query("SELECT w FROM Workflow w WHERE w.status IN ('IN_PROGRESS', 'WAITING_APPROVAL', 'WAITING_TASK', 'WAITING_LLM')")
    List<Workflow> findAllActive();

    List<Workflow> findByOrchestratorInstanceIdAndStatusIn(String orchestratorInstanceId, List<WorkflowStatus> statuses);

    @Query("SELECT w FROM Workflow w WHERE w.orchestratorInstanceId = :orchestratorInstanceId ORDER BY w.createdAt DESC")
    List<Workflow> findTopByOrchestratorInstanceIdOrderByCreatedAtDesc(String orchestratorInstanceId, Pageable pageable);
}
