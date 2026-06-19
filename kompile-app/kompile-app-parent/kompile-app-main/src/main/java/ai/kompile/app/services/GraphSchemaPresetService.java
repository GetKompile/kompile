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

package ai.kompile.app.services;

import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.cli.common.KompileHome;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages named {@link GraphSchema} presets as JSON files.
 *
 * <p>Presets are stored in {@code ~/.kompile/config/schema-presets/} and can be
 * created, read, updated, and deleted via the REST API. Built-in presets are
 * seeded from classpath resources on first run.
 *
 * <p>Schema presets are pure data — no hardcoded enums. Domain-specific entity
 * and relationship types live in the JSON, not in Java code.
 */
@Service
public class GraphSchemaPresetService {

    private static final Logger logger = LoggerFactory.getLogger(GraphSchemaPresetService.class);
    private static final String PRESETS_DIR = "schema-presets";
    private static final String CLASSPATH_PRESETS = "classpath:schema-presets/*.json";

    private final ObjectMapper objectMapper;
    private final Path presetsPath;
    private final ConcurrentHashMap<String, PresetEntry> presets = new ConcurrentHashMap<>();

    public GraphSchemaPresetService() {
        this(KompileHome.dataDir().toPath());
    }

    public GraphSchemaPresetService(String dataDir) {
        this(Paths.get(dataDir));
    }

    private GraphSchemaPresetService(Path dataDir) {
        this.objectMapper = JsonUtils.newStandardMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.presetsPath = dataDir.resolve("config").resolve(PRESETS_DIR);
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(presetsPath);
        } catch (IOException e) {
            logger.error("Could not create schema-presets directory: {}", e.getMessage());
        }
        seedBuiltInPresets();
        loadPresetsFromDisk();
    }

    /**
     * Lists all available preset names with descriptions.
     */
    public List<PresetSummary> listPresets() {
        List<PresetSummary> summaries = new ArrayList<>();
        for (Map.Entry<String, PresetEntry> entry : presets.entrySet()) {
            PresetEntry pe = entry.getValue();
            summaries.add(new PresetSummary(
                    pe.id, pe.name, pe.description, pe.version,
                    pe.schema.getNodeTypes() != null ? pe.schema.getNodeTypes().size() : 0,
                    pe.schema.getRelationshipTypes() != null ? pe.schema.getRelationshipTypes().size() : 0
            ));
        }
        summaries.sort(Comparator.comparing(s -> s.id));
        return summaries;
    }

    /**
     * Gets a preset by ID.
     */
    public Optional<PresetEntry> getPreset(String presetId) {
        return Optional.ofNullable(presets.get(presetId));
    }

    /**
     * Gets the GraphSchema for a preset.
     */
    public Optional<GraphSchema> getSchema(String presetId) {
        return getPreset(presetId).map(p -> p.schema);
    }

    /**
     * Saves or updates a preset.
     */
    public PresetEntry savePreset(String presetId, PresetEntry entry) throws IOException {
        entry.id = presetId;
        Path file = presetsPath.resolve(presetId + ".json");
        objectMapper.writeValue(file.toFile(), entry);
        presets.put(presetId, entry);
        logger.info("Saved schema preset '{}' to {}", presetId, file);
        return entry;
    }

    /**
     * Deletes a preset.
     */
    public boolean deletePreset(String presetId) {
        Path file = presetsPath.resolve(presetId + ".json");
        try {
            Files.deleteIfExists(file);
            presets.remove(presetId);
            logger.info("Deleted schema preset '{}'", presetId);
            return true;
        } catch (IOException e) {
            logger.error("Failed to delete preset '{}': {}", presetId, e.getMessage());
            return false;
        }
    }

    /**
     * Extracts entity type labels and relationship type names from a preset
     * for use in GraphExtractionConfig.
     */
    public Optional<Map<String, List<String>>> getPresetTypeNames(String presetId) {
        return getPreset(presetId).map(p -> {
            List<String> entityTypes = new ArrayList<>();
            List<String> relationshipTypes = new ArrayList<>();
            if (p.schema.getNodeTypes() != null) {
                for (NodeType nt : p.schema.getNodeTypes()) {
                    entityTypes.add(nt.getLabel());
                }
            }
            if (p.schema.getRelationshipTypes() != null) {
                for (RelationshipType rt : p.schema.getRelationshipTypes()) {
                    relationshipTypes.add(rt.getType());
                }
            }
            Map<String, List<String>> result = new HashMap<>();
            result.put("entityTypes", entityTypes);
            result.put("relationshipTypes", relationshipTypes);
            return result;
        });
    }

    // =========================================================================
    // Initialization
    // =========================================================================

    private void seedBuiltInPresets() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(CLASSPATH_PRESETS);
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) continue;
                Path target = presetsPath.resolve(filename);
                if (!Files.exists(target)) {
                    try (InputStream is = resource.getInputStream()) {
                        Files.copy(is, target);
                        logger.info("Seeded built-in schema preset: {}", filename);
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Could not seed built-in presets: {}", e.getMessage());
        }
    }

    private void loadPresetsFromDisk() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(presetsPath, "*.json")) {
            for (Path file : stream) {
                try {
                    PresetEntry entry = objectMapper.readValue(file.toFile(), PresetEntry.class);
                    if (entry.id == null || entry.id.isEmpty()) {
                        String fileName = file.getFileName().toString();
                        entry.id = fileName.substring(0, fileName.length() - 5); // strip .json
                    }
                    presets.put(entry.id, entry);
                    logger.debug("Loaded schema preset: {} ({} node types, {} relationship types)",
                            entry.id,
                            entry.schema != null && entry.schema.getNodeTypes() != null
                                    ? entry.schema.getNodeTypes().size() : 0,
                            entry.schema != null && entry.schema.getRelationshipTypes() != null
                                    ? entry.schema.getRelationshipTypes().size() : 0);
                } catch (IOException e) {
                    logger.warn("Could not load preset from {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.warn("Could not scan presets directory: {}", e.getMessage());
        }
        logger.info("Loaded {} schema presets", presets.size());
    }

    // =========================================================================
    // Data classes
    // =========================================================================

    /**
     * Full preset entry including the schema.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PresetEntry {
        public String id;
        public String name;
        public String description;
        public int version;
        public GraphSchema schema;

        // Jackson needs these
        public PresetEntry() {
            this.schema = new GraphSchema();
        }

        // For the JSON file format, nodeTypes/relationshipTypes/patterns are at the
        // top level — map them into the embedded GraphSchema on deserialization.
        @com.fasterxml.jackson.annotation.JsonSetter("nodeTypes")
        public void setNodeTypes(List<NodeType> nodeTypes) {
            if (this.schema == null) this.schema = new GraphSchema();
            this.schema.setNodeTypes(nodeTypes);
        }

        @com.fasterxml.jackson.annotation.JsonSetter("relationshipTypes")
        public void setRelationshipTypes(List<RelationshipType> relationshipTypes) {
            if (this.schema == null) this.schema = new GraphSchema();
            this.schema.setRelationshipTypes(relationshipTypes);
        }

        @com.fasterxml.jackson.annotation.JsonSetter("patterns")
        public void setPatterns(List<String> patterns) {
            if (this.schema == null) this.schema = new GraphSchema();
            this.schema.setPatterns(patterns);
        }

        @com.fasterxml.jackson.annotation.JsonGetter("nodeTypes")
        public List<NodeType> getNodeTypes() {
            return schema != null ? schema.getNodeTypes() : null;
        }

        @com.fasterxml.jackson.annotation.JsonGetter("relationshipTypes")
        public List<RelationshipType> getRelationshipTypes() {
            return schema != null ? schema.getRelationshipTypes() : null;
        }

        @com.fasterxml.jackson.annotation.JsonGetter("patterns")
        public List<String> getPatterns() {
            return schema != null ? schema.getPatterns() : null;
        }
    }

    /**
     * Summary for listing presets without full schema payload.
     */
    public record PresetSummary(
            String id,
            String name,
            String description,
            int version,
            int nodeTypeCount,
            int relationshipTypeCount
    ) {}
}
