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

import ai.kompile.orchestrator.model.workflow.WorkflowStep;
import ai.kompile.orchestrator.model.workflow.WorkflowStepStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for workflow steps.
 */
@Repository
public interface WorkflowStepRepository extends JpaRepository<WorkflowStep, Long> {

    @Query("SELECT s FROM WorkflowStep s WHERE s.workflow.id = :workflowId ORDER BY s.stepNumber ASC")
    List<WorkflowStep> findByWorkflowIdOrderByStepNumber(Long workflowId);

    @Query("SELECT s FROM WorkflowStep s WHERE s.workflow.id = :workflowId AND s.stepNumber = :stepNumber")
    Optional<WorkflowStep> findByWorkflowIdAndStepNumber(Long workflowId, Integer stepNumber);

    @Query("SELECT s FROM WorkflowStep s WHERE s.workflow.id = :workflowId AND s.status = :status")
    List<WorkflowStep> findByWorkflowIdAndStatus(Long workflowId, WorkflowStepStatus status);
}
