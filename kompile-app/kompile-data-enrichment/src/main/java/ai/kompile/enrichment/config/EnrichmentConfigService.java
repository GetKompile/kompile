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
package ai.kompile.enrichment.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;

/**
 * Reads enrichment configuration from the "enrichment" block
 * of ~/.kompile/config/graph-extraction-config.json.
 */
@Service
public class EnrichmentConfigService {
    private static final Logger log = LoggerFactory.getLogger(EnrichmentConfigService.class);
    private static final String CONFIG_DIR = System.getProperty("user.home") + "/.kompile/config";
    private static final String CONFIG_FILE = "graph-extraction-config.json";

    private final ObjectMapper objectMapper;

    public EnrichmentConfigService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Load enrichment config from the JSON config file.
     * Returns defaults if the file or section doesn't exist.
     */
    public EnrichmentConfig loadConfig() {
        File configFile = new File(CONFIG_DIR, CONFIG_FILE);
        if (!configFile.exists()) {
            return EnrichmentConfig.builder().build();
        }
        try {
            JsonNode root = objectMapper.readTree(configFile);
            JsonNode enrichmentNode = root.path("enrichment");
            if (enrichmentNode.isMissingNode() || enrichmentNode.isNull()) {
                return EnrichmentConfig.builder().build();
            }
            return objectMapper.treeToValue(enrichmentNode, EnrichmentConfig.class);
        } catch (Exception e) {
            log.warn("Failed to read enrichment config from {}, using defaults: {}", configFile, e.getMessage());
            return EnrichmentConfig.builder().build();
        }
    }

    /**
     * Merge a request-provided config with the file-based defaults.
     * Request values override file defaults where non-null.
     */
    public EnrichmentConfig mergeWithDefaults(EnrichmentConfig requestConfig) {
        if (requestConfig == null) {
            return loadConfig();
        }
        return requestConfig;
    }
}
