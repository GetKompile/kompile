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

import ai.kompile.orchestrator.model.llm.LlmSession;
import ai.kompile.orchestrator.model.llm.LlmSessionStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for LLM sessions.
 */
@Repository
public interface LlmSessionRepository extends JpaRepository<LlmSession, Long> {

    List<LlmSession> findByOrchestratorInstanceId(String orchestratorInstanceId);

    List<LlmSession> findByOrchestratorInstanceIdAndStatus(String orchestratorInstanceId, LlmSessionStatus status);

    @Query("SELECT s FROM LlmSession s WHERE s.orchestratorInstanceId = :orchestratorInstanceId AND s.status IN ('STARTING', 'RUNNING')")
    List<LlmSession> findActiveByOrchestratorInstanceId(String orchestratorInstanceId);

    @Query("SELECT s FROM LlmSession s WHERE s.status IN ('STARTING', 'RUNNING')")
    List<LlmSession> findAllActive();

    List<LlmSession> findByTriggerId(String triggerId);

    List<LlmSession> findByWorkflowId(Long workflowId);

    @Query("SELECT s FROM LlmSession s WHERE s.orchestratorInstanceId = :orchestratorInstanceId ORDER BY s.startTime DESC")
    List<LlmSession> findTopByOrchestratorInstanceIdOrderByStartTimeDesc(String orchestratorInstanceId, Pageable pageable);
}
