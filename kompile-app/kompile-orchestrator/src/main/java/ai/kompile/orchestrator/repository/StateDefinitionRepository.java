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

import ai.kompile.orchestrator.model.state.StateCategory;
import ai.kompile.orchestrator.model.state.StateDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for state definitions.
 */
@Repository
public interface StateDefinitionRepository extends JpaRepository<StateDefinition, String> {

    List<StateDefinition> findByCategory(StateCategory category);

    List<StateDefinition> findByBuiltin(boolean builtin);

    List<StateDefinition> findByBuiltinOrderByDisplayOrderAsc(boolean builtin);

    /**
     * Find all states for an orchestrator instance.
     */
    List<StateDefinition> findByOrchestratorInstanceIdOrderByDisplayOrderAsc(String orchestratorInstanceId);

    /**
     * Find states by category for an instance.
     */
    List<StateDefinition> findByOrchestratorInstanceIdAndCategory(String orchestratorInstanceId, StateCategory category);

    /**
     * Find all states for an instance or global states (null instanceId).
     */
    @Query("SELECT s FROM StateDefinition s WHERE s.orchestratorInstanceId = :instanceId OR s.orchestratorInstanceId IS NULL ORDER BY s.displayOrder ASC")
    List<StateDefinition> findByInstanceIdIncludingGlobal(@Param("instanceId") String instanceId);

    /**
     * Find initial states for an instance.
     */
    List<StateDefinition> findByOrchestratorInstanceIdAndCategoryOrderByDisplayOrderAsc(
            String orchestratorInstanceId, StateCategory category);

    /**
     * Find state by ID within an instance.
     */
    Optional<StateDefinition> findByStateIdAndOrchestratorInstanceId(String stateId, String orchestratorInstanceId);

    /**
     * Check if state exists for an instance.
     */
    boolean existsByStateIdAndOrchestratorInstanceId(String stateId, String orchestratorInstanceId);

    /**
     * Delete all states for an instance.
     */
    void deleteByOrchestratorInstanceId(String orchestratorInstanceId);

    /**
     * Count states for an instance.
     */
    long countByOrchestratorInstanceId(String orchestratorInstanceId);
}
