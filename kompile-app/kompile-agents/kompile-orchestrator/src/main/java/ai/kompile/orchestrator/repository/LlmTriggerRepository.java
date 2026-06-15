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

import ai.kompile.orchestrator.model.llm.LlmTrigger;
import ai.kompile.orchestrator.model.llm.LlmTriggerType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for LLM triggers.
 */
@Repository
public interface LlmTriggerRepository extends JpaRepository<LlmTrigger, String> {

    List<LlmTrigger> findByTriggerType(LlmTriggerType triggerType);

    List<LlmTrigger> findByEnabled(boolean enabled);

    @Query("SELECT t FROM LlmTrigger t WHERE t.triggerType = :triggerType AND t.enabled = true ORDER BY t.priority DESC")
    List<LlmTrigger> findEnabledByTriggerType(LlmTriggerType triggerType);

    @Query("SELECT t FROM LlmTrigger t WHERE t.triggerType = :triggerType AND t.targetStateId = :stateId AND t.enabled = true")
    List<LlmTrigger> findEnabledByTypeAndState(LlmTriggerType triggerType, String stateId);

    @Query("SELECT t FROM LlmTrigger t WHERE t.triggerType = :triggerType AND t.targetTaskId = :taskId AND t.enabled = true")
    List<LlmTrigger> findEnabledByTypeAndTask(LlmTriggerType triggerType, String taskId);
}
