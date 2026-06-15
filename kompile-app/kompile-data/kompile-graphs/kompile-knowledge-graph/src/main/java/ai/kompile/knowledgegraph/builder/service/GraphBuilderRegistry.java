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
package ai.kompile.knowledgegraph.builder.service;

import ai.kompile.core.graphbuilder.GraphBuilderInfo;
import ai.kompile.core.graphbuilder.GraphBuilderType;
import ai.kompile.core.graphbuilder.KnowledgeGraphBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Registry for discovering and accessing available knowledge graph builders.
 *
 * <p>Automatically discovers all {@link KnowledgeGraphBuilder} implementations
 * registered as Spring beans and provides access by ID or type.
 */
@Service
@Slf4j
public class GraphBuilderRegistry {

    private final Map<String, KnowledgeGraphBuilder> buildersById = new LinkedHashMap<>();
    private final Map<GraphBuilderType, List<KnowledgeGraphBuilder>> buildersByType = new EnumMap<>(GraphBuilderType.class);

    private final List<KnowledgeGraphBuilder> allBuilders;

    @Autowired
    public GraphBuilderRegistry(List<KnowledgeGraphBuilder> builders) {
        this.allBuilders = builders != null ? builders : Collections.emptyList();
    }

    @PostConstruct
    public void init() {
        for (KnowledgeGraphBuilder builder : allBuilders) {
            String id = builder.getId();
            if (buildersById.containsKey(id)) {
                log.warn("Duplicate builder ID detected: {}. Skipping.", id);
                continue;
            }

            buildersById.put(id, builder);

            GraphBuilderType type = builder.getType();
            buildersByType.computeIfAbsent(type, k -> new ArrayList<>()).add(builder);

            log.info("Registered knowledge graph builder: id={}, type={}, name={}",
                    id, type, builder.getDisplayName());
        }

        log.info("GraphBuilderRegistry initialized with {} builders", buildersById.size());
    }

    /**
     * Get all available builders.
     */
    public List<KnowledgeGraphBuilder> getAllBuilders() {
        return new ArrayList<>(buildersById.values());
    }

    /**
     * Get builder by ID.
     */
    public Optional<KnowledgeGraphBuilder> getBuilder(String builderId) {
        return Optional.ofNullable(buildersById.get(builderId));
    }

    /**
     * Get builders by type.
     */
    public List<KnowledgeGraphBuilder> getBuildersByType(GraphBuilderType type) {
        return buildersByType.getOrDefault(type, Collections.emptyList());
    }

    /**
     * Get the default builder (first LLM builder, or first available).
     */
    public Optional<KnowledgeGraphBuilder> getDefaultBuilder() {
        // Prefer LLM builder as default
        List<KnowledgeGraphBuilder> llmBuilders = getBuildersByType(GraphBuilderType.LLM);
        if (!llmBuilders.isEmpty()) {
            return Optional.of(llmBuilders.get(0));
        }

        // Fall back to any available builder
        if (!buildersById.isEmpty()) {
            return Optional.of(buildersById.values().iterator().next());
        }

        return Optional.empty();
    }

    /**
     * Get builder info for all available builders.
     */
    public List<GraphBuilderInfo> getBuilderInfos() {
        return buildersById.values().stream()
                .map(KnowledgeGraphBuilder::getInfo)
                .collect(Collectors.toList());
    }

    /**
     * Get builder info by ID.
     */
    public Optional<GraphBuilderInfo> getBuilderInfo(String builderId) {
        return getBuilder(builderId).map(KnowledgeGraphBuilder::getInfo);
    }

    /**
     * Check if a builder exists.
     */
    public boolean hasBuilder(String builderId) {
        return buildersById.containsKey(builderId);
    }

    /**
     * Get count of registered builders.
     */
    public int getBuilderCount() {
        return buildersById.size();
    }

    /**
     * Get available builder IDs.
     */
    public Set<String> getBuilderIds() {
        return new LinkedHashSet<>(buildersById.keySet());
    }

    /**
     * Get builder by type string (e.g., "llm", "manual").
     * Returns the first builder of that type.
     */
    public Optional<KnowledgeGraphBuilder> getBuilderByTypeString(String typeString) {
        if (typeString == null || typeString.isEmpty()) {
            return getDefaultBuilder();
        }

        // First try to find by ID
        Optional<KnowledgeGraphBuilder> byId = getBuilder(typeString);
        if (byId.isPresent()) {
            return byId;
        }

        // Try to match by type
        try {
            GraphBuilderType type = GraphBuilderType.valueOf(typeString.toUpperCase());
            List<KnowledgeGraphBuilder> builders = getBuildersByType(type);
            if (!builders.isEmpty()) {
                return Optional.of(builders.get(0));
            }
        } catch (IllegalArgumentException e) {
            // Not a valid type, try partial matching
            for (Map.Entry<String, KnowledgeGraphBuilder> entry : buildersById.entrySet()) {
                if (entry.getKey().toLowerCase().contains(typeString.toLowerCase())) {
                    return Optional.of(entry.getValue());
                }
            }
        }

        return Optional.empty();
    }
}
