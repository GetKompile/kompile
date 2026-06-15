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

import ai.kompile.orchestrator.model.output.OutputClassifier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for output classifiers.
 */
@Repository
public interface OutputClassifierRepository extends JpaRepository<OutputClassifier, Long> {

    /**
     * Find all classifiers for an orchestrator instance.
     */
    List<OutputClassifier> findByOrchestratorInstanceId(String orchestratorInstanceId);

    /**
     * Find enabled classifiers for an orchestrator instance.
     */
    List<OutputClassifier> findByOrchestratorInstanceIdAndEnabledTrue(String orchestratorInstanceId);

    /**
     * Find a classifier by instance ID and name.
     */
    Optional<OutputClassifier> findByOrchestratorInstanceIdAndName(String orchestratorInstanceId, String name);

    /**
     * Find classifiers by tag.
     */
    @Query("SELECT c FROM OutputClassifier c WHERE c.orchestratorInstanceId = :instanceId AND c.tags LIKE %:tag%")
    List<OutputClassifier> findByInstanceIdAndTag(@Param("instanceId") String instanceId, @Param("tag") String tag);

    /**
     * Check if a classifier with the given name exists.
     */
    boolean existsByOrchestratorInstanceIdAndName(String orchestratorInstanceId, String name);

    /**
     * Count classifiers for an instance.
     */
    long countByOrchestratorInstanceId(String orchestratorInstanceId);

    /**
     * Delete all classifiers for an instance.
     */
    void deleteByOrchestratorInstanceId(String orchestratorInstanceId);
}
