/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.staging.repository;

import ai.kompile.app.staging.domain.StagingServiceConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for managing StagingServiceConfig entities.
 */
@Repository
public interface StagingServiceConfigRepository extends JpaRepository<StagingServiceConfig, Long> {

    /**
     * Find the currently active staging service configuration.
     */
    Optional<StagingServiceConfig> findByActiveTrue();

    /**
     * Find configuration by name.
     */
    Optional<StagingServiceConfig> findByName(String name);

    /**
     * Find configuration by endpoint URL.
     */
    Optional<StagingServiceConfig> findByEndpointUrl(String endpointUrl);

    /**
     * Find all configurations ordered by name.
     */
    List<StagingServiceConfig> findAllByOrderByNameAsc();

    /**
     * Find all verified configurations.
     */
    List<StagingServiceConfig> findByVerifiedTrue();

    /**
     * Deactivate all configurations.
     */
    @Modifying
    @Query("UPDATE StagingServiceConfig c SET c.active = false")
    void deactivateAll();

    /**
     * Activate a specific configuration by ID.
     */
    @Modifying
    @Query("UPDATE StagingServiceConfig c SET c.active = true WHERE c.id = :id")
    void activateById(Long id);

    /**
     * Check if a configuration with the given name exists (excluding a specific ID).
     */
    boolean existsByNameAndIdNot(String name, Long id);

    /**
     * Check if a configuration with the given name exists.
     */
    boolean existsByName(String name);
}
