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

package ai.kompile.ocr.models;

import ai.kompile.modelmanager.KompileModelManager;
import ai.kompile.ocr.models.factory.OcrModelFactory;
import ai.kompile.ocr.models.pipeline.DefaultOcrPipeline;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for OCR models.
 * Always enabled - runtime control via UI/API.
 */
@Configuration
@ComponentScan(basePackages = "ai.kompile.ocr.models")
public class OcrModelsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OcrModelFactory ocrModelFactory(KompileModelManager modelManager) {
        // OcrModelFactory uses @Autowired constructor injection for RegistryService
        // so Spring will handle the optional dependency injection automatically
        return new OcrModelFactory(modelManager, null);
    }

    @Bean
    @ConditionalOnMissingBean
    public DefaultOcrPipeline defaultOcrPipeline(OcrModelFactory modelFactory) {
        return new DefaultOcrPipeline(modelFactory);
    }
}
