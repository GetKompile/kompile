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

package ai.kompile.app.subprocess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;

/**
 * Minimal Spring configuration for the graph subprocess.
 *
 * <p>Creates a lightweight context with only the beans needed for
 * graph extraction and matrix graph algorithms:</p>
 * <ul>
 *   <li>GraphConstructor (MatrixGraphConstructor)</li>
 *   <li>MatrixGraphStore</li>
 *   <li>EmbeddingModel (for entity embeddings, optional)</li>
 *   <li>LLMChat / ExtractionLlmService (for LLM-based extraction)</li>
 *   <li>MatrixGraphAlgorithms (static utility)</li>
 * </ul>
 *
 * <p>This is the graph subprocess counterpart to SubprocessIngestConfiguration.
 * It uses a whitelist approach — only explicitly imported auto-configurations
 * are loaded, preventing JPA/web modules from being scanned.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "kompile.subprocess.mode", havingValue = "true", matchIfMissing = false)
@ComponentScan(
    basePackages = {
        // Core interfaces (EmbeddingModel, LLMChat, GraphConstructor, etc.)
        "ai.kompile.core",
        // Knowledge graph module (MatrixGraphConstructor, MatrixGraphStore, algorithms)
        "ai.kompile.knowledgegraph",
        // Anserini embedding (for entity embedding generation)
        "ai.kompile.embedding.anserini",
        // Model manager (for model download/caching)
        "ai.kompile.modelmanager",
        // CLI agent registry (for ExtractionLlmService resolution)
        "ai.kompile.core.agent"
    },
    excludeFilters = {
        // Exclude web-related components
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Controller"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*WebSocket.*"),
        // Exclude scheduling
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Scheduler.*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Scheduled.*"),
        // Exclude JPA repositories and services that need a database
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Repository"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*JpaRepository"),
        // Exclude services not needed in subprocess
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*BroadcasterService.*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*DocumentRetriever.*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*AnseriniSearchService.*"),
        // Exclude KG embedding controllers and services that need JPA
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*KGEmbeddingController.*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*KnowledgeGraphController.*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*FactSheetGraphController.*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*GraphIOController.*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*KnowledgeGraphBuilderController.*")
    }
)
@EnableConfigurationProperties
@PropertySource(value = "classpath:application.properties", ignoreResourceNotFound = true)
@Import({
    JacksonAutoConfiguration.class
})
public class SubprocessGraphConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessGraphConfiguration.class);
}
