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

package ai.kompile.app.pgml.indexer.config;

import ai.kompile.app.pgml.indexer.PgmlIndexerServiceImpl;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.loaders.DocumentLoader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({IndexerService.class, PgmlIndexerProperties.class})
@EnableConfigurationProperties(PgmlIndexerProperties.class)
@ConditionalOnProperty(prefix = "kompile.indexer.pgml", name = "enabled", havingValue = "true")
public class PgmlIndexerAutoConfiguration {

    private List<DocumentLoader> loaders;

    private List<VectorStore> vectorStore;
    @Bean("pgmlIndexerService")
    public IndexerService pgmlIndexerService(PgmlIndexerProperties pgmlIndexerProperties,
                                             ApplicationContext applicationContext) {
        return new PgmlIndexerServiceImpl(pgmlIndexerProperties, applicationContext,loaders,vectorStore);
    }
}