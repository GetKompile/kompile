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

package ai.kompile.vectorstore.vespa;

import ai.kompile.core.embeddings.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for Vespa VectorStore.
 *
 * <p>This configuration is only activated when:</p>
 * <ul>
 *   <li>{@code kompile.vectorstore.vespa.enabled=true} is set in properties</li>
 * </ul>
 *
 * <p>Requires a running Vespa instance with an appropriate schema deployed.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "ai.kompile.vectorstore.vespa.VespaVectorStoreAutoConfiguration")
@ConditionalOnProperty(name = "kompile.vectorstore.vespa.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(VespaVectorStoreProperties.class)
public class VespaVectorStoreAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(VespaVectorStoreAutoConfiguration.class);

    @Bean
    public VespaVectorStoreImpl vespaVectorStore(
            VespaVectorStoreProperties properties,
            @Autowired(required = false) EmbeddingModel embeddingModel) {

        log.info("=== VespaVectorStoreAutoConfiguration ACTIVATED ===");
        log.info("Creating VespaVectorStoreImpl bean:");
        log.info("  - endpoint: {}", properties.getEndpoint());
        log.info("  - namespace: {}", properties.getNamespace());
        log.info("  - document type: {}", properties.getDocumentType());
        log.info("  - vector field: {}", properties.getVectorField());
        log.info("  - dimensions: {}", properties.getDimensions());
        log.info("  - distance metric: {}", properties.getDistanceMetric());
        log.info("  - hybrid search enabled: {}", properties.getHybridSearch().isEnabled());
        log.info("  - embedding model: {}", embeddingModel != null ? embeddingModel.getClass().getSimpleName() : "NULL");

        if (embeddingModel == null) {
            log.warn("No EmbeddingModel configured - VespaVectorStore will require pre-computed embeddings");
        }

        return new VespaVectorStoreImpl(properties, embeddingModel);
    }
}
