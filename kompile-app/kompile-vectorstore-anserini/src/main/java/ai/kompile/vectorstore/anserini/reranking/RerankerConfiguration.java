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

package ai.kompile.vectorstore.anserini.reranking;

import ai.kompile.core.reranking.RerankerConfig;
import ai.kompile.core.reranking.RerankerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for reranking functionality.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "ai.kompile.vectorstore.anserini.AnseriniVectorStoreImpl")
@EnableConfigurationProperties(RerankerProperties.class)
public class RerankerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RerankerConfiguration.class);

    /**
     * Create the RerankerService bean when reranking is enabled.
     */
    @Bean
    @ConditionalOnProperty(name = "kompile.reranker.enabled", havingValue = "true")
    public RerankerService rerankerService(RerankerProperties properties) {
        RerankerConfig defaultConfig = properties.toRerankerConfig();
        log.info("Creating AnseriniRerankerService with config: type={}, fbDocs={}, fbTerms={}",
                properties.getType(), properties.getFbDocs(), properties.getFbTerms());
        return new AnseriniRerankerService(defaultConfig);
    }

    /**
     * Expose the default RerankerConfig as a bean.
     */
    @Bean
    @ConditionalOnProperty(name = "kompile.reranker.enabled", havingValue = "true")
    public RerankerConfig defaultRerankerConfig(RerankerProperties properties) {
        return properties.toRerankerConfig();
    }
}
