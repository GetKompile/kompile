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

package ai.kompile.staging.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Service for loading and providing the model catalog.
 */
@Service
public class CatalogService {

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);
    private static final String CATALOG_FILE = "model-sources.yml";

    private ModelCatalog catalog;
    private final ObjectMapper yamlMapper;

    public CatalogService() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    @PostConstruct
    public void init() {
        loadCatalog();
    }

    /**
     * Load the catalog from the YAML file.
     */
    @SuppressWarnings("unchecked")
    private void loadCatalog() {
        try {
            ClassPathResource resource = new ClassPathResource(CATALOG_FILE);
            if (!resource.exists()) {
                log.warn("Catalog file not found: {}", CATALOG_FILE);
                catalog = ModelCatalog.builder()
                        .sources(new HashMap<>())
                        .encoders(new ArrayList<>())
                        .crossEncoders(new ArrayList<>())
                        .vlm(new ArrayList<>())
                        .build();
                return;
            }

            try (InputStream is = resource.getInputStream()) {
                Map<String, Object> root = yamlMapper.readValue(is, Map.class);

                // Parse sources
                Map<String, ModelCatalog.SourceConfig> sources = new HashMap<>();
                Map<String, Object> sourcesMap = (Map<String, Object>) root.get("sources");
                if (sourcesMap != null) {
                    for (Map.Entry<String, Object> entry : sourcesMap.entrySet()) {
                        Map<String, Object> sourceData = (Map<String, Object>) entry.getValue();
                        sources.put(entry.getKey(), ModelCatalog.SourceConfig.builder()
                                .baseUrl((String) sourceData.get("base_url"))
                                .enabled(Boolean.TRUE.equals(sourceData.get("enabled")))
                                .build());
                    }
                }

                // Parse model catalog
                Map<String, Object> modelCatalogMap = (Map<String, Object>) root.get("model_catalog");
                List<CatalogModel> encoders = new ArrayList<>();
                List<CatalogModel> crossEncoders = new ArrayList<>();
                List<CatalogModel> vlm = new ArrayList<>();

                if (modelCatalogMap != null) {
                    // Parse encoders
                    List<Map<String, Object>> encodersList = (List<Map<String, Object>>) modelCatalogMap.get("encoders");
                    if (encodersList != null) {
                        for (Map<String, Object> modelData : encodersList) {
                            encoders.add(parseModel(modelData));
                        }
                    }

                    // Parse cross-encoders
                    List<Map<String, Object>> crossEncodersList = (List<Map<String, Object>>) modelCatalogMap.get("cross_encoders");
                    if (crossEncodersList != null) {
                        for (Map<String, Object> modelData : crossEncodersList) {
                            crossEncoders.add(parseModel(modelData));
                        }
                    }

                    // Parse VLM models
                    List<Map<String, Object>> vlmList = (List<Map<String, Object>>) modelCatalogMap.get("vlm");
                    if (vlmList != null) {
                        for (Map<String, Object> modelData : vlmList) {
                            vlm.add(parseModel(modelData));
                        }
                    }
                }

                catalog = ModelCatalog.builder()
                        .sources(sources)
                        .encoders(encoders)
                        .crossEncoders(crossEncoders)
                        .vlm(vlm)
                        .build();

                log.info("Loaded model catalog: {} encoders, {} cross-encoders, {} vlm",
                        encoders.size(), crossEncoders.size(), vlm.size());
            }
        } catch (IOException e) {
            log.error("Failed to load catalog", e);
            catalog = ModelCatalog.builder()
                    .sources(new HashMap<>())
                    .encoders(new ArrayList<>())
                    .crossEncoders(new ArrayList<>())
                    .vlm(new ArrayList<>())
                    .build();
        }
    }

    @SuppressWarnings("unchecked")
    private CatalogModel parseModel(Map<String, Object> modelData) {
        CatalogModel.CatalogModelMetadata metadata = null;
        Map<String, Object> metadataMap = (Map<String, Object>) modelData.get("metadata");
        if (metadataMap != null) {
            metadata = CatalogModel.CatalogModelMetadata.builder()
                    .embeddingDim((Integer) metadataMap.get("embedding_dim"))
                    .hiddenSize((Integer) metadataMap.get("hidden_size"))
                    .numLayers((Integer) metadataMap.get("num_layers"))
                    .maxSequenceLength((Integer) metadataMap.get("max_sequence_length"))
                    .trainingData((String) metadataMap.get("training_data"))
                    .description((String) metadataMap.get("description"))
                    .build();
        }

        Map<String, String> files = new HashMap<>();
        Map<String, Object> filesMap = (Map<String, Object>) modelData.get("files");
        if (filesMap != null) {
            for (Map.Entry<String, Object> entry : filesMap.entrySet()) {
                files.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }

        return CatalogModel.builder()
                .id((String) modelData.get("id"))
                .source((String) modelData.get("source"))
                .repo((String) modelData.get("repo"))
                .format((String) modelData.get("format"))
                .files(files)
                .metadata(metadata)
                .build();
    }

    /**
     * Get the full catalog.
     */
    public ModelCatalog getCatalog() {
        return catalog;
    }

    /**
     * Get all encoders.
     */
    public List<CatalogModel> getEncoders() {
        return catalog.getEncoders();
    }

    /**
     * Get all cross-encoders.
     */
    public List<CatalogModel> getCrossEncoders() {
        return catalog.getCrossEncoders();
    }

    /**
     * Get all VLM models.
     */
    public List<CatalogModel> getVlm() {
        return catalog.getVlm() != null ? catalog.getVlm() : new ArrayList<>();
    }

    /**
     * Get a model by ID.
     */
    public Optional<CatalogModel> getModel(String modelId) {
        // Search in encoders
        for (CatalogModel model : catalog.getEncoders()) {
            if (model.getId().equals(modelId)) {
                return Optional.of(model);
            }
        }
        // Search in cross-encoders
        for (CatalogModel model : catalog.getCrossEncoders()) {
            if (model.getId().equals(modelId)) {
                return Optional.of(model);
            }
        }
        // Search in VLM models
        for (CatalogModel model : getVlm()) {
            if (model.getId().equals(modelId)) {
                return Optional.of(model);
            }
        }
        return Optional.empty();
    }

    /**
     * Reload the catalog from disk.
     */
    public void reload() {
        loadCatalog();
    }
}
