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

import ai.kompile.orchestrator.model.OrchestratorInstance;
import ai.kompile.orchestrator.model.OrchestratorStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for orchestrator instances.
 */
@Repository
public interface OrchestratorInstanceRepository extends JpaRepository<OrchestratorInstance, String> {

    List<OrchestratorInstance> findByStatus(OrchestratorStatus status);

    List<OrchestratorInstance> findByStatusIn(List<OrchestratorStatus> statuses);

    @Query("SELECT o FROM OrchestratorInstance o WHERE o.status IN ('RUNNING', 'PAUSED', 'INITIALIZING')")
    List<OrchestratorInstance> findAllActive();

    List<OrchestratorInstance> findByOwnerId(String ownerId);

    @Query("SELECT o FROM OrchestratorInstance o WHERE o.currentStateId = :stateId")
    List<OrchestratorInstance> findByCurrentStateId(String stateId);
}
