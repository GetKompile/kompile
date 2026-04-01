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

package ai.kompile.evaluation;

import ai.kompile.core.evaluation.EvaluationService;
import ai.kompile.core.evaluation.RagEvaluator;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configuration for evaluation beans.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EvaluationProperties.class)
@ConditionalOnClass(name = "ai.kompile.evaluation.EvaluationConfiguration")
@ConditionalOnProperty(name = "kompile.evaluation.enabled", havingValue = "true", matchIfMissing = false)
public class EvaluationConfiguration {

    @Bean
    public EvaluationService evaluationService(
            @Autowired(required = false) List<RagEvaluator> evaluators,
            EvaluationProperties properties) {
        return new DefaultEvaluationService(
                evaluators != null ? evaluators : List.of(),
                properties);
    }

    // Evaluators

    @Bean
    @ConditionalOnProperty(name = "kompile.evaluation.relevancy.enabled", havingValue = "true")
    @ConditionalOnBean(ChatClient.Builder.class)
    public RagEvaluator relevancyEvaluator(
            ChatClient.Builder chatClientBuilder,
            EvaluationProperties properties) {
        return new RelevancyEvaluator(chatClientBuilder.build(), properties);
    }

    @Bean
    @ConditionalOnProperty(name = "kompile.evaluation.faithfulness.enabled", havingValue = "true")
    @ConditionalOnBean(ChatClient.Builder.class)
    public RagEvaluator faithfulnessEvaluator(
            ChatClient.Builder chatClientBuilder,
            EvaluationProperties properties) {
        return new FaithfulnessEvaluator(chatClientBuilder.build(), properties);
    }

    @Bean
    @ConditionalOnProperty(name = "kompile.evaluation.answer-correctness.enabled", havingValue = "true")
    @ConditionalOnBean(ChatClient.Builder.class)
    public RagEvaluator answerCorrectnessEvaluator(
            ChatClient.Builder chatClientBuilder,
            EvaluationProperties properties) {
        return new AnswerCorrectnessEvaluator(chatClientBuilder.build(), properties);
    }

    @Bean
    @ConditionalOnProperty(name = "kompile.evaluation.context-relevancy.enabled", havingValue = "true")
    @ConditionalOnBean(ChatClient.Builder.class)
    public RagEvaluator contextRelevancyEvaluator(
            ChatClient.Builder chatClientBuilder,
            EvaluationProperties properties) {
        return new ContextRelevancyEvaluator(chatClientBuilder.build(), properties);
    }

    @Bean
    @ConditionalOnProperty(name = "kompile.evaluation.hallucination.enabled", havingValue = "true")
    @ConditionalOnBean(ChatClient.Builder.class)
    public RagEvaluator hallucinationEvaluator(
            ChatClient.Builder chatClientBuilder,
            EvaluationProperties properties) {
        return new HallucinationEvaluator(chatClientBuilder.build(), properties);
    }
}
