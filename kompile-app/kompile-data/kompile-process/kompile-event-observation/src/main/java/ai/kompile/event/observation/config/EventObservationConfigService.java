/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.event.observation.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Loads and saves {@link EventObservationConfig} from {@code ~/.kompile/config/event-observation-config.json}.
 *
 * <p>Mirrors {@code GraphExtractionConfigService}: read/write under a {@link ReentrantReadWriteLock},
 * defensive {@link EventObservationConfig#copy() copies} returned to callers, defaults written on
 * first run, and partial merge on update so the UI/CLI can patch individual fields.</p>
 */
@Service
public class EventObservationConfigService {

    private static final Logger log = LoggerFactory.getLogger(EventObservationConfigService.class);
    private static final String CONFIG_FILENAME = "event-observation-config.json";

    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Path configPath;

    private EventObservationConfig currentConfig;

    public EventObservationConfigService(@Value("${kompile.data.dir:${user.home}/.kompile}") String dataDir) {
        this.objectMapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.configPath = Paths.get(dataDir, "config", CONFIG_FILENAME);
    }

    @PostConstruct
    public void init() {
        loadConfig();
    }

    private void loadConfig() {
        lock.writeLock().lock();
        try {
            if (Files.exists(configPath)) {
                currentConfig = objectMapper.readValue(configPath.toFile(), EventObservationConfig.class);
                log.info("Loaded event-observation config from {}", configPath);
            } else {
                currentConfig = EventObservationConfig.defaults();
                saveConfigInternal();
                log.info("Wrote default event-observation config to {}", configPath);
            }
        } catch (IOException e) {
            log.warn("Failed to load event-observation config from {} — using defaults: {}", configPath, e.getMessage());
            currentConfig = EventObservationConfig.defaults();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public EventObservationConfig getConfig() {
        lock.readLock().lock();
        try {
            if (currentConfig == null) {
                currentConfig = EventObservationConfig.defaults();
            }
            return currentConfig.copy();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Merge non-null fields of {@code patch} into the current config and persist.
     */
    public EventObservationConfig updateConfig(EventObservationConfig patch) {
        lock.writeLock().lock();
        try {
            if (currentConfig == null) {
                currentConfig = EventObservationConfig.defaults();
            }
            if (patch.enabled != null) currentConfig.enabled = patch.enabled;
            if (patch.opportunityModel != null) currentConfig.opportunityModel = patch.opportunityModel;
            if (patch.halfLifeDays != null) currentConfig.halfLifeDays = patch.halfLifeDays;
            if (patch.priorAlpha != null) currentConfig.priorAlpha = patch.priorAlpha;
            if (patch.priorBeta != null) currentConfig.priorBeta = patch.priorBeta;
            if (patch.priorBlendK != null) currentConfig.priorBlendK = patch.priorBlendK;
            if (patch.minEvidenceForPrior != null) currentConfig.minEvidenceForPrior = patch.minEvidenceForPrior;
            if (patch.storageBackends != null) currentConfig.storageBackends = patch.storageBackends;
            if (patch.decayOnEachCrawl != null) currentConfig.decayOnEachCrawl = patch.decayOnEachCrawl;
            if (patch.entityEventsEnabled != null) currentConfig.entityEventsEnabled = patch.entityEventsEnabled;
            if (patch.connectionEventsEnabled != null) currentConfig.connectionEventsEnabled = patch.connectionEventsEnabled;
            if (patch.processStepEventsEnabled != null) currentConfig.processStepEventsEnabled = patch.processStepEventsEnabled;
            if (patch.fineGrainedMutationsEnabled != null) currentConfig.fineGrainedMutationsEnabled = patch.fineGrainedMutationsEnabled;
            patch.getAdditionalProperties().forEach(currentConfig::setAdditionalProperty);
            saveConfigInternal();
            return currentConfig.copy();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void saveConfigInternal() {
        try {
            Files.createDirectories(configPath.getParent());
            objectMapper.writeValue(configPath.toFile(), currentConfig);
        } catch (IOException e) {
            log.warn("Failed to save event-observation config to {}: {}", configPath, e.getMessage());
        }
    }
}
