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

package ai.kompile.app.services.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

/**
 * Repository for persisting processing state for resume capability.
 *
 * <p>
 * Uses file-based storage for durability across restarts. State files are
 * stored
 * as JSON in a configurable directory (default: ~/.kompile/state/processing).
 * </p>
 *
 * <h2>Features</h2>
 * <ul>
 * <li>In-memory cache for fast lookups</li>
 * <li>Async persistence to disk to avoid blocking ingestion</li>
 * <li>Automatic cleanup of old completed states</li>
 * <li>Recovery of incomplete states on startup</li>
 * </ul>
 *
 * <h2>File Format</h2>
 * <p>
 * States are stored as JSON files named {@code {sourceId}.json} in the state
 * directory.
 * </p>
 */
@Component
public class ProcessingStateRepository {

    private static final Logger logger = LoggerFactory.getLogger(ProcessingStateRepository.class);

    private static final String STATE_DIR_NAME = "processing-state";
    private static final String FILE_EXTENSION = ".json";
    private static final int RETENTION_DAYS = 7;

    private final Path stateDir;
    private final Map<String, ProcessingState> cache = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService persistenceExecutor;
    private final Set<String> pendingWrites = ConcurrentHashMap.newKeySet();

    public ProcessingStateRepository(
            @Value("${kompile.ingest.state-directory:#{null}}") String baseDirectory) {
        // Handle null baseDirectory - use temporary directory as fallback
        if (baseDirectory != null) {
            this.stateDir = Paths.get(baseDirectory, STATE_DIR_NAME);
        } else {
            this.stateDir = Paths.get(System.getProperty("java.io.tmpdir"), "kompile-state", STATE_DIR_NAME);
            logger.warn("kompile.ingest.state-directory not configured, using temporary directory: {}", this.stateDir);
        }
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.persistenceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "state-persistence");
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(stateDir);
            logger.info("Processing state directory: {}", stateDir);

            // Load all existing states into cache
            loadAllStates();

            // Schedule periodic cleanup of old states
            persistenceExecutor.scheduleAtFixedRate(
                    this::cleanupOldStates,
                    1, 24, TimeUnit.HOURS);
        } catch (IOException e) {
            logger.error("Failed to initialize state directory: {}", e.getMessage());
        }
    }

    /**
     * Finds a state that can be resumed.
     *
     * @param sourceId The source document identifier
     * @return Optional containing the resumable state, or empty if none exists or
     *         not resumable
     */
    public Optional<ProcessingState> findResumable(String sourceId) {
        String normalizedId = normalizeId(sourceId);

        // Check cache first
        ProcessingState cached = cache.get(normalizedId);
        if (cached != null) {
            if (cached.canResume()) {
                logger.info("Found resumable state for {}: page {}/{}, {} chunks",
                        sourceId, cached.lastProcessedPage(), cached.totalPages(), cached.chunksCreated());
                return Optional.of(cached);
            }
            return Optional.empty();
        }

        // Try loading from disk
        return loadFromDisk(normalizedId)
                .filter(ProcessingState::canResume)
                .map(state -> {
                    cache.put(normalizedId, state);
                    logger.info("Loaded resumable state from disk for {}", sourceId);
                    return state;
                });
    }

    /**
     * Finds a state by task ID.
     *
     * @param taskId The task identifier
     * @return Optional containing the state, or empty if not found
     */
    public Optional<ProcessingState> findByTaskId(String taskId) {
        return cache.values().stream()
                .filter(state -> taskId.equals(state.taskId()))
                .findFirst();
    }

    /**
     * Saves a state. The state is written to cache immediately and persisted
     * asynchronously.
     *
     * @param state The state to save
     */
    public void save(ProcessingState state) {
        String normalizedId = normalizeId(state.sourceId());
        cache.put(normalizedId, state);

        // Async persist to disk (debounced)
        if (pendingWrites.add(normalizedId)) {
            persistenceExecutor.schedule(() -> {
                try {
                    pendingWrites.remove(normalizedId);
                    ProcessingState current = cache.get(normalizedId);
                    if (current != null) {
                        saveToFile(normalizedId, current);
                    }
                } catch (Exception e) {
                    logger.error("Failed to persist state for {}: {}", normalizedId, e.getMessage());
                }
            }, 100, TimeUnit.MILLISECONDS); // Small delay to batch rapid updates
        }
    }

    /**
     * Saves a state synchronously (blocking).
     * Use this when you need to ensure the state is persisted before continuing.
     *
     * @param state The state to save
     */
    public void saveSync(ProcessingState state) {
        String normalizedId = normalizeId(state.sourceId());
        cache.put(normalizedId, state);
        saveToFile(normalizedId, state);
    }

    /**
     * Deletes a state.
     *
     * @param sourceId The source document identifier
     */
    public void delete(String sourceId) {
        String normalizedId = normalizeId(sourceId);
        cache.remove(normalizedId);

        Path statePath = stateDir.resolve(normalizedId + FILE_EXTENSION);
        try {
            Files.deleteIfExists(statePath);
            logger.debug("Deleted state file for {}", sourceId);
        } catch (IOException e) {
            logger.warn("Failed to delete state file for {}: {}", sourceId, e.getMessage());
        }
    }

    /**
     * Gets all incomplete processing states (for recovery on startup).
     *
     * @return List of states that can be resumed
     */
    public List<ProcessingState> findIncomplete() {
        return cache.values().stream()
                .filter(ProcessingState::canResume)
                .toList();
    }

    /**
     * Gets all active (non-terminal) processing states.
     *
     * @return List of active states
     */
    public List<ProcessingState> findActive() {
        return cache.values().stream()
                .filter(ProcessingState::isActive)
                .toList();
    }

    /**
     * Gets all states.
     */
    public List<ProcessingState> findAll() {
        return new ArrayList<>(cache.values());
    }

    /**
     * Gets count of states by phase.
     */
    public Map<ProcessingState.ProcessingPhase, Long> getStateCounts() {
        return cache.values().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        ProcessingState::phase,
                        java.util.stream.Collectors.counting()));
    }

    // ========== Private Methods ==========

    private String normalizeId(String sourceId) {
        // Replace characters that are problematic for filenames
        return sourceId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void loadAllStates() {
        try (Stream<Path> paths = Files.list(stateDir)) {
            paths.filter(p -> p.toString().endsWith(FILE_EXTENSION))
                    .forEach(path -> {
                        try {
                            ProcessingState state = objectMapper.readValue(path.toFile(), ProcessingState.class);
                            String id = path.getFileName().toString().replace(FILE_EXTENSION, "");
                            cache.put(id, state);
                        } catch (Exception e) {
                            logger.warn("Failed to load state from {}: {}", path, e.getMessage());
                        }
                    });

            logger.info("Loaded {} processing states from disk", cache.size());

            // Log incomplete states for potential recovery
            List<ProcessingState> incomplete = findIncomplete();
            if (!incomplete.isEmpty()) {
                logger.info("Found {} incomplete states that can be resumed:", incomplete.size());
                for (ProcessingState state : incomplete) {
                    logger.info("  - {}: page {}/{}, phase={}", state.fileName(),
                            state.lastProcessedPage(), state.totalPages(), state.phase());
                }
            }
        } catch (IOException e) {
            logger.warn("Could not list state directory: {}", e.getMessage());
        }
    }

    private Optional<ProcessingState> loadFromDisk(String normalizedId) {
        Path statePath = stateDir.resolve(normalizedId + FILE_EXTENSION);
        if (!Files.exists(statePath)) {
            return Optional.empty();
        }

        try {
            ProcessingState state = objectMapper.readValue(statePath.toFile(), ProcessingState.class);
            return Optional.of(state);
        } catch (Exception e) {
            logger.warn("Failed to load state from {}: {}", statePath, e.getMessage());
            return Optional.empty();
        }
    }

    private void saveToFile(String normalizedId, ProcessingState state) {
        Path statePath = stateDir.resolve(normalizedId + FILE_EXTENSION);
        Path tempPath = stateDir.resolve(normalizedId + ".tmp");

        try {
            // Write to temp file first for atomic replacement
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempPath.toFile(), state);

            // Atomic move
            Files.move(tempPath, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            logger.debug("Persisted state for {} (page {}/{})",
                    state.sourceId(), state.lastProcessedPage(), state.totalPages());
        } catch (IOException e) {
            logger.error("Failed to save state to {}: {}", statePath, e.getMessage());
            // Try to clean up temp file
            try {
                Files.deleteIfExists(tempPath);
            } catch (IOException cleanupEx) {
                logger.warn("Failed to clean up temp file {} after save failure: {}", tempPath, cleanupEx.getMessage());
            }
        }
    }

    private void cleanupOldStates() {
        logger.debug("Running state cleanup...");
        Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        int cleaned = 0;

        for (Iterator<Map.Entry<String, ProcessingState>> it = cache.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, ProcessingState> entry = it.next();
            ProcessingState state = entry.getValue();

            // Only clean up completed/failed/cancelled states older than retention period
            if (state.isTerminal() && state.lastUpdated().isBefore(cutoff)) {
                it.remove();

                // Delete file
                Path statePath = stateDir.resolve(entry.getKey() + FILE_EXTENSION);
                try {
                    Files.deleteIfExists(statePath);
                    cleaned++;
                } catch (IOException e) {
                    logger.warn("Failed to delete old state file: {}", statePath);
                }
            }
        }

        if (cleaned > 0) {
            logger.info("Cleaned up {} old processing states", cleaned);
        }
    }

    /**
     * Shuts down the persistence executor.
     * MEMORY LEAK FIX: Added @PreDestroy to ensure executor is properly shut down
     * on Spring context close.
     */
    @PreDestroy
    public void shutdown() {
        persistenceExecutor.shutdown();
        try {
            if (!persistenceExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                persistenceExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            persistenceExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
