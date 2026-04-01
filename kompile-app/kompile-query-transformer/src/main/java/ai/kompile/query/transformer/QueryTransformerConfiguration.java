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

package ai.kompile.query.transformer;

import ai.kompile.core.query.QueryTransformer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Configuration for query transformer beans.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(QueryTransformerProperties.class)
@ConditionalOnClass(name = "ai.kompile.query.transformer.QueryTransformerConfiguration")
public class QueryTransformerConfiguration {

    @Bean
    @ConditionalOnProperty(name = "kompile.query.transformer.type", havingValue = "compression")
    @ConditionalOnBean(ChatClient.Builder.class)
    public QueryTransformer compressingQueryTransformer(
            ChatClient.Builder chatClientBuilder,
            QueryTransformerProperties properties) {
        return new CompressingQueryTransformer(chatClientBuilder.build(), properties);
    }

    @Bean
    @ConditionalOnProperty(name = "kompile.query.transformer.type", havingValue = "expansion")
    @ConditionalOnBean(ChatClient.Builder.class)
    public QueryTransformer expansionQueryTransformer(
            ChatClient.Builder chatClientBuilder,
            QueryTransformerProperties properties) {
        return new ExpansionQueryTransformer(chatClientBuilder.build(), properties);
    }

    @Bean
    @ConditionalOnProperty(name = "kompile.query.transformer.type", havingValue = "hyde")
    @ConditionalOnBean(ChatClient.Builder.class)
    public QueryTransformer hydeQueryTransformer(
            ChatClient.Builder chatClientBuilder,
            QueryTransformerProperties properties) {
        return new HyDEQueryTransformer(chatClientBuilder.build(), properties);
    }

    @Bean
    @ConditionalOnProperty(name = "kompile.query.transformer.type", havingValue = "step-back")
    @ConditionalOnBean(ChatClient.Builder.class)
    public QueryTransformer stepBackQueryTransformer(
            ChatClient.Builder chatClientBuilder,
            QueryTransformerProperties properties) {
        return new StepBackQueryTransformer(chatClientBuilder.build(), properties);
    }

    @Bean
    @ConditionalOnProperty(name = "kompile.query.transformer.type", havingValue = "multi-query")
    @ConditionalOnBean(ChatClient.Builder.class)
    public QueryTransformer multiQueryTransformer(
            ChatClient.Builder chatClientBuilder,
            QueryTransformerProperties properties) {
        return new MultiQueryTransformer(chatClientBuilder.build(), properties);
    }
}
