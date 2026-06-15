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
package ai.kompile.app.eval.repository;

import ai.kompile.app.eval.domain.ExperimentRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExperimentRunRepository extends JpaRepository<ExperimentRunEntity, String> {

    List<ExperimentRunEntity> findByExperimentIdOrderByStartedAtDesc(String experimentId);

    List<ExperimentRunEntity> findByModelIdOrderByStartedAtDesc(String modelId);

    @Query("SELECT r FROM ExperimentRunEntity r WHERE r.experiment.id = :experimentId " +
           "AND r.status = 'COMPLETED' ORDER BY r.averageScore DESC")
    List<ExperimentRunEntity> findCompletedRunsByExperimentRanked(@Param("experimentId") String experimentId);
}
