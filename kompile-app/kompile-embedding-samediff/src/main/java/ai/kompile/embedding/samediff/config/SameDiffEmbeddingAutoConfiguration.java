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

package ai.kompile.embedding.samediff.config;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.embedding.samediff.SameDiffEmbeddingModelImpl;
import ai.kompile.embedding.samediff.pipeline.SameDiffEmbeddingStepRunnerFactory;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration // Spring Boot 3+ style, for 2.x use @Configuration
@ConditionalOnClass({EmbeddingModel.class, SameDiff.class, Nd4j.class})
@EnableConfigurationProperties(SameDiffEmbeddingProperties.class)
@ConditionalOnProperty(prefix = "kompile.embedding.samediff", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SameDiffEmbeddingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SameDiffEmbeddingAutoConfiguration.class);

    static {
        // Eagerly load a class from ND4J to ensure backend is initialized early if needed.
        // This can sometimes help prevent issues with backend selection in complex classpath scenarios.
        try {
            Nd4j.empty();
        } catch (Throwable t) {
            log.warn("ND4J eager initialization failed (backend may not be available yet): {}", t.getMessage());
        }
    }


    @Bean
    @ConditionalOnMissingBean(name = "sameDiffEmbeddingModel")
    public EmbeddingModel sameDiffEmbeddingModel(SameDiffEmbeddingProperties properties) {
        return new SameDiffEmbeddingModelImpl(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "kompile.embedding.samediff.pipeline-step", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SameDiffEmbeddingStepRunnerFactory sameDiffEmbeddingStepRunnerFactory() {
        return new SameDiffEmbeddingStepRunnerFactory();
    }
}