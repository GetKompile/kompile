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

package ai.kompile.app.staging.service;

import ai.kompile.app.staging.domain.StagingServiceConfig;
import ai.kompile.app.staging.repository.StagingServiceConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Service for managing staging service configurations.
 */
@Service
@Transactional
public class StagingServiceConfigService {

    private static final Logger log = LoggerFactory.getLogger(StagingServiceConfigService.class);

    @Autowired
    private StagingServiceConfigRepository repository;

    /** No-arg for Spring AOT / CGLIB proxy creation. */
    public StagingServiceConfigService() {
    }

    public StagingServiceConfigService(StagingServiceConfigRepository repository) {
        this.repository = repository;
    }


    /**
     * Get all configurations.
     */
    @Transactional(readOnly = true)
    public List<StagingServiceConfig> getAllConfigs() {
        return repository.findAllByOrderByNameAsc();
    }

    /**
     * Get configuration by ID.
     */
    @Transactional(readOnly = true)
    public Optional<StagingServiceConfig> getConfigById(Long id) {
        return repository.findById(id);
    }

    /**
     * Get the active configuration.
     */
    @Transactional(readOnly = true)
    public Optional<StagingServiceConfig> getActiveConfig() {
        return repository.findByActiveTrue();
    }

    /**
     * Create a new configuration.
     */
    public StagingServiceConfig createConfig(StagingServiceConfig config) {
        // Check for duplicate name
        if (repository.existsByName(config.getName())) {
            throw new IllegalArgumentException("Configuration with name '" + config.getName() + "' already exists");
        }

        // If this is the first config or marked as active, set it as active
        if (config.isActive() || repository.count() == 0) {
            repository.deactivateAll();
            config.setActive(true);
        }

        log.info("Creating new staging service config: {}", config.getName());
        return repository.save(config);
    }

    /**
     * Update an existing configuration.
     */
    public StagingServiceConfig updateConfig(Long id, StagingServiceConfig updates) {
        StagingServiceConfig existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Configuration not found: " + id));

        // Check for duplicate name (excluding current)
        if (!existing.getName().equals(updates.getName()) &&
                repository.existsByNameAndIdNot(updates.getName(), id)) {
            throw new IllegalArgumentException("Configuration with name '" + updates.getName() + "' already exists");
        }

        // Update fields
        existing.setName(updates.getName());
        existing.setEndpointUrl(updates.getEndpointUrl());
        existing.setApiKey(updates.getApiKey());
        existing.setConnectionTimeoutMs(updates.getConnectionTimeoutMs());
        existing.setReadTimeoutMs(updates.getReadTimeoutMs());
        existing.setAutoSync(updates.isAutoSync());
        existing.setSyncIntervalMinutes(updates.getSyncIntervalMinutes());
        existing.setDescription(updates.getDescription());

        // Handle active status change
        if (updates.isActive() && !existing.isActive()) {
            repository.deactivateAll();
            existing.setActive(true);
        }

        // Reset verification status if endpoint changed
        if (!existing.getEndpointUrl().equals(updates.getEndpointUrl())) {
            existing.setVerified(false);
            existing.setLastVerifiedAt(null);
            existing.setLastError(null);
        }

        log.info("Updated staging service config: {}", existing.getName());
        return repository.save(existing);
    }

    /**
     * Delete a configuration.
     */
    public void deleteConfig(Long id) {
        StagingServiceConfig config = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Configuration not found: " + id));

        log.info("Deleting staging service config: {}", config.getName());
        repository.delete(config);

        // If we deleted the active config, activate the first remaining one
        if (config.isActive()) {
            repository.findAllByOrderByNameAsc().stream()
                    .findFirst()
                    .ifPresent(c -> {
                        c.setActive(true);
                        repository.save(c);
                    });
        }
    }

    /**
     * Set a configuration as active.
     */
    public StagingServiceConfig setActive(Long id) {
        StagingServiceConfig config = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Configuration not found: " + id));

        repository.deactivateAll();
        config.setActive(true);

        log.info("Set staging service config as active: {}", config.getName());
        return repository.save(config);
    }

    /**
     * Update verification status after testing connection.
     */
    public StagingServiceConfig updateVerificationStatus(Long id, boolean verified, String error) {
        StagingServiceConfig config = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Configuration not found: " + id));

        config.setVerified(verified);
        config.setLastVerifiedAt(Instant.now());
        config.setLastError(verified ? null : error);

        log.info("Updated verification status for {}: verified={}", config.getName(), verified);
        return repository.save(config);
    }

    /**
     * Check if any staging service is configured and active.
     */
    @Transactional(readOnly = true)
    public boolean hasActiveConfig() {
        return repository.findByActiveTrue().isPresent();
    }
}
