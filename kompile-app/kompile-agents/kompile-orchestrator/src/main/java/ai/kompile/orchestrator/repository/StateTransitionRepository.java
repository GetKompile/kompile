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

import ai.kompile.orchestrator.model.state.StateTransition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for state transitions.
 */
@Repository
public interface StateTransitionRepository extends JpaRepository<StateTransition, Long> {

    /**
     * Find all transitions for an orchestrator instance.
     */
    List<StateTransition> findByOrchestratorInstanceIdOrderByPriorityDesc(String orchestratorInstanceId);

    /**
     * Find transitions from a specific state.
     */
    List<StateTransition> findByOrchestratorInstanceIdAndFromStateIdOrderByPriorityDesc(
            String orchestratorInstanceId, String fromStateId);

    /**
     * Find transitions to a specific state.
     */
    List<StateTransition> findByOrchestratorInstanceIdAndToStateId(
            String orchestratorInstanceId, String toStateId);

    /**
     * Find enabled transitions from a state.
     */
    List<StateTransition> findByOrchestratorInstanceIdAndFromStateIdAndEnabledTrueOrderByPriorityDesc(
            String orchestratorInstanceId, String fromStateId);

    /**
     * Find auto-trigger transitions from a state.
     */
    List<StateTransition> findByOrchestratorInstanceIdAndFromStateIdAndAutoTriggerTrueAndEnabledTrueOrderByPriorityDesc(
            String orchestratorInstanceId, String fromStateId);

    /**
     * Find transition between two specific states.
     */
    List<StateTransition> findByOrchestratorInstanceIdAndFromStateIdAndToStateId(
            String orchestratorInstanceId, String fromStateId, String toStateId);

    /**
     * Check if transition exists between two states.
     */
    boolean existsByOrchestratorInstanceIdAndFromStateIdAndToStateId(
            String orchestratorInstanceId, String fromStateId, String toStateId);

    /**
     * Delete all transitions for an instance.
     */
    void deleteByOrchestratorInstanceId(String orchestratorInstanceId);

    /**
     * Delete all transitions from a state.
     */
    void deleteByOrchestratorInstanceIdAndFromStateId(String orchestratorInstanceId, String fromStateId);

    /**
     * Delete all transitions to a state.
     */
    void deleteByOrchestratorInstanceIdAndToStateId(String orchestratorInstanceId, String toStateId);

    /**
     * Count transitions for an instance.
     */
    long countByOrchestratorInstanceId(String orchestratorInstanceId);

    /**
     * Get all unique state IDs referenced in transitions for an instance.
     */
    @Query("SELECT DISTINCT t.fromStateId FROM StateTransition t WHERE t.orchestratorInstanceId = :instanceId " +
           "UNION SELECT DISTINCT t.toStateId FROM StateTransition t WHERE t.orchestratorInstanceId = :instanceId")
    List<String> findAllReferencedStateIds(@Param("instanceId") String instanceId);
}
