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

package ai.kompile.app.services.mcp;

import ai.kompile.core.mcp.EnhancedToolDefinition;
import ai.kompile.core.mcp.ToolDefinitionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * File-based implementation of ToolDefinitionRepository.
 * Persists tool definitions as JSON files in a configurable directory.
 * Each tool is stored as a separate file named {tool-name}.json.
 */
@Repository
public class FileBasedToolDefinitionRepository implements ToolDefinitionRepository {

    private static final Logger logger = LoggerFactory.getLogger(FileBasedToolDefinitionRepository.class);
    private static final String JSON_EXTENSION = ".json";

    private final ObjectMapper objectMapper;
    private final Path storageDirectory;

    // In-memory cache for faster access
    private final Map<String, EnhancedToolDefinition> cache = new ConcurrentHashMap<>();
    private final Map<String, String> nameToIdIndex = new ConcurrentHashMap<>();

    public FileBasedToolDefinitionRepository(
            @Value("${kompile.tools.definitions.directory:./data/tool-definitions}") String storageDir) {
        this.storageDirectory = Paths.get(storageDir);
        this.objectMapper = createObjectMapper();
    }

    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @PostConstruct
    public void init() {
        try {
            // Create storage directory if it doesn't exist
            Files.createDirectories(storageDirectory);
            logger.info("Tool definitions storage directory: {}", storageDirectory.toAbsolutePath());

            // Load existing definitions into cache
            loadAllFromDisk();
            logger.info("Loaded {} tool definitions from disk", cache.size());
        } catch (IOException e) {
            logger.error("Failed to initialize tool definition repository: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize tool definition repository", e);
        }
    }

    @Override
    public EnhancedToolDefinition save(EnhancedToolDefinition definition) {
        Objects.requireNonNull(definition, "Tool definition cannot be null");
        Objects.requireNonNull(definition.getName(), "Tool name cannot be null");

        // Generate ID if not present
        if (definition.getId() == null || definition.getId().isEmpty()) {
            definition.setId(UUID.randomUUID().toString());
        }

        // Set timestamps
        Instant now = Instant.now();
        if (definition.getCreatedAt() == null) {
            definition.setCreatedAt(now);
        }
        definition.setUpdatedAt(now);

        // Save to disk
        Path filePath = getFilePath(definition.getName());
        try {
            objectMapper.writeValue(filePath.toFile(), definition);
            logger.info("Saved tool definition '{}' to {}", definition.getName(), filePath);
        } catch (IOException e) {
            logger.error("Failed to save tool definition '{}': {}", definition.getName(), e.getMessage(), e);
            throw new RuntimeException("Failed to save tool definition", e);
        }

        // Update cache
        cache.put(definition.getId(), definition);
        nameToIdIndex.put(definition.getName().toLowerCase(), definition.getId());

        return definition;
    }

    @Override
    public Optional<EnhancedToolDefinition> findById(String id) {
        return Optional.ofNullable(cache.get(id));
    }

    @Override
    public Optional<EnhancedToolDefinition> findByName(String name) {
        if (name == null) return Optional.empty();
        String id = nameToIdIndex.get(name.toLowerCase());
        return id != null ? Optional.ofNullable(cache.get(id)) : Optional.empty();
    }

    @Override
    public List<EnhancedToolDefinition> findAll() {
        return new ArrayList<>(cache.values());
    }

    @Override
    public List<EnhancedToolDefinition> findAllEnabled() {
        return cache.values().stream()
                .filter(EnhancedToolDefinition::isEnabled)
                .collect(Collectors.toList());
    }

    @Override
    public List<EnhancedToolDefinition> findByCategory(String category) {
        if (category == null) return Collections.emptyList();
        return cache.values().stream()
                .filter(d -> category.equalsIgnoreCase(d.getCategory()))
                .collect(Collectors.toList());
    }

    @Override
    public List<EnhancedToolDefinition> findBySource(EnhancedToolDefinition.ToolSource source) {
        if (source == null) return Collections.emptyList();
        return cache.values().stream()
                .filter(d -> source.equals(d.getSource()))
                .collect(Collectors.toList());
    }

    @Override
    public List<EnhancedToolDefinition> findByTag(String tag) {
        if (tag == null) return Collections.emptyList();
        String lowerTag = tag.toLowerCase();
        return cache.values().stream()
                .filter(d -> d.getTags() != null &&
                        d.getTags().stream().anyMatch(t -> t.toLowerCase().contains(lowerTag)))
                .collect(Collectors.toList());
    }

    @Override
    public List<EnhancedToolDefinition> search(String query) {
        if (query == null || query.isEmpty()) return findAll();
        String lowerQuery = query.toLowerCase();

        return cache.values().stream()
                .filter(d -> matchesSearch(d, lowerQuery))
                .collect(Collectors.toList());
    }

    private boolean matchesSearch(EnhancedToolDefinition def, String query) {
        // Search in name
        if (def.getName() != null && def.getName().toLowerCase().contains(query)) {
            return true;
        }
        // Search in display name
        if (def.getDisplayName() != null && def.getDisplayName().toLowerCase().contains(query)) {
            return true;
        }
        // Search in description
        if (def.getDescription() != null && def.getDescription().toLowerCase().contains(query)) {
            return true;
        }
        // Search in detailed description
        if (def.getDetailedDescription() != null && def.getDetailedDescription().toLowerCase().contains(query)) {
            return true;
        }
        // Search in category
        if (def.getCategory() != null && def.getCategory().toLowerCase().contains(query)) {
            return true;
        }
        // Search in tags
        if (def.getTags() != null) {
            for (String tag : def.getTags()) {
                if (tag.toLowerCase().contains(query)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean deleteById(String id) {
        EnhancedToolDefinition definition = cache.get(id);
        if (definition == null) {
            return false;
        }
        return deleteDefinition(definition);
    }

    @Override
    public boolean deleteByName(String name) {
        if (name == null) return false;
        String id = nameToIdIndex.get(name.toLowerCase());
        if (id == null) return false;
        return deleteById(id);
    }

    private boolean deleteDefinition(EnhancedToolDefinition definition) {
        Path filePath = getFilePath(definition.getName());
        try {
            boolean deleted = Files.deleteIfExists(filePath);
            if (deleted) {
                cache.remove(definition.getId());
                nameToIdIndex.remove(definition.getName().toLowerCase());
                logger.info("Deleted tool definition '{}' from {}", definition.getName(), filePath);
            }
            return deleted;
        } catch (IOException e) {
            logger.error("Failed to delete tool definition '{}': {}", definition.getName(), e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean existsByName(String name) {
        return name != null && nameToIdIndex.containsKey(name.toLowerCase());
    }

    @Override
    public long count() {
        return cache.size();
    }

    @Override
    public void deleteAll() {
        try (Stream<Path> files = Files.list(storageDirectory)) {
            files.filter(p -> p.toString().endsWith(JSON_EXTENSION))
                 .forEach(p -> {
                     try {
                         Files.delete(p);
                     } catch (IOException e) {
                         logger.warn("Failed to delete file: {}", p, e);
                     }
                 });
        } catch (IOException e) {
            logger.error("Failed to list files for deletion: {}", e.getMessage(), e);
        }
        cache.clear();
        nameToIdIndex.clear();
        logger.info("Deleted all tool definitions");
    }

    @Override
    public void refresh() {
        cache.clear();
        nameToIdIndex.clear();
        loadAllFromDisk();
        logger.info("Refreshed tool definitions cache, loaded {} definitions", cache.size());
    }

    private void loadAllFromDisk() {
        try (Stream<Path> files = Files.list(storageDirectory)) {
            files.filter(p -> p.toString().endsWith(JSON_EXTENSION))
                 .forEach(this::loadFromFile);
        } catch (IOException e) {
            logger.error("Failed to load tool definitions from disk: {}", e.getMessage(), e);
        }
    }

    private void loadFromFile(Path filePath) {
        try {
            EnhancedToolDefinition definition = objectMapper.readValue(filePath.toFile(), EnhancedToolDefinition.class);
            if (definition.getId() != null && definition.getName() != null) {
                cache.put(definition.getId(), definition);
                nameToIdIndex.put(definition.getName().toLowerCase(), definition.getId());
                logger.debug("Loaded tool definition '{}' from {}", definition.getName(), filePath);
            }
        } catch (IOException e) {
            logger.warn("Failed to load tool definition from {}: {}", filePath, e.getMessage());
        }
    }

    private Path getFilePath(String toolName) {
        // Sanitize the tool name for use as a filename
        String safeFileName = toolName.replaceAll("[^a-zA-Z0-9_-]", "_") + JSON_EXTENSION;
        return storageDirectory.resolve(safeFileName);
    }

    /**
     * Returns the storage directory path.
     */
    public Path getStorageDirectory() {
        return storageDirectory;
    }

    /**
     * Exports all tool definitions to a single JSON file.
     */
    public void exportAll(Path outputFile) throws IOException {
        objectMapper.writeValue(outputFile.toFile(), findAll());
        logger.info("Exported {} tool definitions to {}", cache.size(), outputFile);
    }

    /**
     * Imports tool definitions from a JSON file (array of definitions).
     */
    public int importAll(Path inputFile) throws IOException {
        List<EnhancedToolDefinition> definitions = objectMapper.readValue(
                inputFile.toFile(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, EnhancedToolDefinition.class)
        );
        int imported = 0;
        for (EnhancedToolDefinition def : definitions) {
            save(def);
            imported++;
        }
        logger.info("Imported {} tool definitions from {}", imported, inputFile);
        return imported;
    }
}
