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

import ai.kompile.modelmanager.KompileModelManager;
import ai.kompile.ocr.models.factory.OcrModelFactory;
import ai.kompile.ocr.models.pipeline.DefaultOcrPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

/**
 * Minimal Spring configuration for the VLM test subprocess.
 *
 * This configuration creates a lightweight context with ONLY the beans
 * necessary for VLM document processing:
 * - OcrPipelineService (orchestrates VLM pipeline)
 * - OcrModelFactory (creates VLM model instances)
 * - DefaultOcrPipeline / VlmDocumentPipeline
 * - KompileModelManager (downloads/caches models)
 *
 * ARCHITECTURE:
 * Uses whitelist approach like SubprocessIngestConfiguration - explicitly
 * imports only needed auto-configurations and excludes web/scheduling beans.
 * This prevents loading REST controllers, document processors, and other
 * unrelated components that have no business in a VLM subprocess.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "kompile.subprocess.vlmtest.mode", havingValue = "true", matchIfMissing = false)
@ComponentScan(
    basePackages = {
        // OCR pipeline service
        "ai.kompile.ocr.integration",
        // OCR models (VLM pipeline, model factory)
        "ai.kompile.ocr.models",
        // Model manager (download/cache)
        "ai.kompile.modelmanager"
    },
    excludeFilters = {
        // Exclude web-related components - not needed in subprocess
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Controller"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*WebSocket.*"),
        // Exclude document processor - it's for ingest pipelines, not VLM test
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*OcrDocumentProcessor"),
        // Exclude auto-configurations that would trigger their own @ComponentScan
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*AutoConfiguration")
    }
)
@Import({
    JacksonAutoConfiguration.class  // Provides ObjectMapper
})
public class SubprocessVlmTestConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessVlmTestConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public KompileModelManager kompileModelManager() {
        logger.info("Creating KompileModelManager for VLM subprocess");
        return new KompileModelManager();
    }

    @Bean
    @ConditionalOnMissingBean
    public OcrModelFactory ocrModelFactory(KompileModelManager modelManager) {
        logger.info("Creating OcrModelFactory for VLM subprocess");
        return new OcrModelFactory(modelManager, null);
    }

    @Bean
    @ConditionalOnMissingBean
    public DefaultOcrPipeline defaultOcrPipeline(OcrModelFactory modelFactory) {
        logger.info("Creating DefaultOcrPipeline for VLM subprocess");
        return new DefaultOcrPipeline(modelFactory);
    }
}
