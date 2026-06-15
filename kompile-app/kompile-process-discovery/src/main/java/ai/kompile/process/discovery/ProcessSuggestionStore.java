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

package ai.kompile.process.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory + file-backed store for auto-discovered {@link ProcessSuggestion} instances.
 * Suggestions are written to {@code ~/.kompile/processes/suggestions/} as JSON files
 * (one file per suggestion) so they survive application restarts.
 */
@Component
public class ProcessSuggestionStore {

    private static final Logger log = LoggerFactory.getLogger(ProcessSuggestionStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .findAndRegisterModules();

    private final Map<String, ProcessSuggestion> suggestions = new ConcurrentHashMap<>();
    private final Path storageDir;

    public ProcessSuggestionStore() {
        this.storageDir = Path.of(System.getProperty("user.home"), ".kompile", "processes", "suggestions");
        loadFromDisk();
    }

    /**
     * Persists a suggestion. If the suggestion has no {@code id}, one is generated.
     * If it has no {@code discoveredAt}, the current instant is set.
     */
    public void save(ProcessSuggestion suggestion) {
        if (suggestion.getId() == null) {
            suggestion.setId("suggestion-" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (suggestion.getDiscoveredAt() == null) {
            suggestion.setDiscoveredAt(Instant.now());
        }
        suggestions.put(suggestion.getId(), suggestion);
        writeToDisk(suggestion);
    }

    /** Persists all suggestions in the list, assigning IDs and timestamps as needed. */
    public void saveAll(List<ProcessSuggestion> suggestionList) {
        for (ProcessSuggestion s : suggestionList) {
            save(s);
        }
    }

    /** Returns a suggestion by its ID. */
    public Optional<ProcessSuggestion> get(String id) {
        return Optional.ofNullable(suggestions.get(id));
    }

    /** Returns all stored suggestions. */
    public List<ProcessSuggestion> listAll() {
        return new ArrayList<>(suggestions.values());
    }

    /** Returns all suggestions belonging to a specific fact sheet. */
    public List<ProcessSuggestion> listByFactSheet(Long factSheetId) {
        return suggestions.values().stream()
                .filter(s -> factSheetId.equals(s.getFactSheetId()))
                .collect(Collectors.toList());
    }

    /** Returns all suggestions that have not yet been accepted. */
    public List<ProcessSuggestion> listPending() {
        return suggestions.values().stream()
                .filter(s -> !Boolean.TRUE.equals(s.getAccepted()))
                .collect(Collectors.toList());
    }

    /**
     * Marks a suggestion as accepted and records the ID of the ProcessDefinition
     * that was created from it. Uses {@code computeIfPresent} to hold the bucket
     * lock during mutation, preventing concurrent reads of a partially-updated entry.
     */
    public void markAccepted(String id, String processDefinitionId) {
        suggestions.computeIfPresent(id, (key, s) -> {
            s.setAccepted(true);
            s.setAcceptedProcessDefinitionId(processDefinitionId);
            writeToDisk(s);
            return s;
        });
    }

    /** Removes a suggestion from memory and disk. */
    public void delete(String id) {
        suggestions.remove(id);
        try {
            Files.deleteIfExists(storageDir.resolve(id + ".json"));
        } catch (IOException e) {
            log.warn("Failed to delete suggestion file {}: {}", id, e.getMessage());
        }
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private void writeToDisk(ProcessSuggestion suggestion) {
        try {
            Files.createDirectories(storageDir);
            Path file = storageDir.resolve(suggestion.getId() + ".json");
            MAPPER.writeValue(file.toFile(), suggestion);
        } catch (IOException e) {
            log.warn("Failed to persist suggestion {}: {}", suggestion.getId(), e.getMessage());
        }
    }

    private void loadFromDisk() {
        if (!Files.isDirectory(storageDir)) return;
        try (var stream = Files.list(storageDir)) {
            stream.filter(p -> p.toString().endsWith(".json")).forEach(path -> {
                try {
                    ProcessSuggestion s = MAPPER.readValue(path.toFile(), ProcessSuggestion.class);
                    if (s.getId() != null) {
                        suggestions.put(s.getId(), s);
                    }
                } catch (IOException e) {
                    log.warn("Failed to load suggestion from {}: {}", path, e.getMessage());
                }
            });
            log.info("Loaded {} process suggestions from disk", suggestions.size());
        } catch (IOException e) {
            log.warn("Failed to list suggestion directory: {}", e.getMessage());
        }
    }
}
