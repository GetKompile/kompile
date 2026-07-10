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

package ai.kompile.app.facts.service;

import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.app.facts.repository.FactSheetRepository;
import ai.kompile.knowledgegraph.embedding.config.GraphBuildingConfigProvider;
import ai.kompile.knowledgegraph.embedding.config.KGEmbeddingConfigService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * JPA-backed implementation of {@link GraphBuildingConfigProvider}.
 * Lives in kompile-app-facts to keep JPA access out of the graphs subtree,
 * satisfying the "Graphs NEVER touch JPA" project rule.
 */
@Service
public class GraphBuildingConfigProviderImpl implements GraphBuildingConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(GraphBuildingConfigProviderImpl.class);

    private static final List<String> DEFAULT_ENTITY_TYPES = Arrays.asList(
            "PERSON", "ORGANIZATION", "LOCATION", "CONCEPT", "EVENT", "PRODUCT", "TECHNOLOGY"
    );

    private final FactSheetRepository factSheetRepository;
    private final ObjectMapper objectMapper;

    public GraphBuildingConfigProviderImpl(FactSheetRepository factSheetRepository,
                                           ObjectMapper objectMapper) {
        this.factSheetRepository = factSheetRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public KGEmbeddingConfigService.GraphBuildingConfig getGraphBuildingConfig(Long factSheetId) {
        try {
            Optional<FactSheet> optSheet = factSheetRepository.findById(factSheetId);
            if (optSheet.isEmpty()) {
                log.warn("FactSheet {} not found; returning default graph building config", factSheetId);
                return KGEmbeddingConfigService.GraphBuildingConfig.defaults();
            }

            FactSheet factSheet = optSheet.get();
            // getEnableGraphBuilding() always returns true per the entity override
            boolean enabled = Boolean.TRUE.equals(factSheet.getEnableGraphBuilding());
            String builderType = factSheet.getGraphBuilderType() != null ? factSheet.getGraphBuilderType() : "llm";
            String storageType = factSheet.getGraphStorageType() != null ? factSheet.getGraphStorageType() : "jpa";
            String configJson = factSheet.getGraphBuilderConfigJson();

            if (configJson != null && !configJson.isBlank()) {
                try {
                    KGEmbeddingConfigService.GraphBuildingConfig parsed =
                            objectMapper.readValue(configJson, KGEmbeddingConfigService.GraphBuildingConfig.class);
                    // Overlay top-level DB fields on top of the JSON config
                    return new KGEmbeddingConfigService.GraphBuildingConfig(
                            enabled,
                            builderType,
                            storageType,
                            parsed.autoAccept(),
                            parsed.autoAcceptThreshold(),
                            parsed.entityTypes(),
                            parsed.modelProvider(),
                            parsed.modelName(),
                            parsed.temperature(),
                            parsed.maxTokens(),
                            parsed.batchSize(),
                            parsed.customPrompt()
                    );
                } catch (JsonProcessingException e) {
                    log.warn("Failed to parse graph builder config JSON for fact sheet {}: {}", factSheetId, e.getMessage());
                }
            }

            // Return defaults with DB values for enabled, builderType, storageType
            return KGEmbeddingConfigService.GraphBuildingConfig.withDefaults(enabled, builderType, storageType);

        } catch (Exception e) {
            log.error("Failed to get graph building config for fact sheet {}: {}", factSheetId, e.getMessage());
            return KGEmbeddingConfigService.GraphBuildingConfig.defaults();
        }
    }

    @Override
    @Transactional
    public KGEmbeddingConfigService.GraphBuildingConfig updateGraphBuildingConfig(
            Long factSheetId, KGEmbeddingConfigService.GraphBuildingConfig config) {
        try {
            Optional<FactSheet> optSheet = factSheetRepository.findById(factSheetId);
            if (optSheet.isEmpty()) {
                log.error("Cannot update graph building config: FactSheet {} not found", factSheetId);
                throw new RuntimeException("FactSheet not found: " + factSheetId);
            }

            FactSheet factSheet = optSheet.get();
            String configJson = objectMapper.writeValueAsString(config);

            factSheet.setGraphBuilderType(config.builderType());
            factSheet.setGraphBuilderConfigJson(configJson);
            factSheet.setGraphStorageType(config.storageType());
            // setEnableGraphBuilding is a no-op (always true), so we skip it
            factSheetRepository.save(factSheet);

            log.info("Updated graph building config for fact sheet {}", factSheetId);
            return config;

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize graph building config: {}", e.getMessage());
            throw new RuntimeException("Failed to serialize graph building config", e);
        }
    }
}
