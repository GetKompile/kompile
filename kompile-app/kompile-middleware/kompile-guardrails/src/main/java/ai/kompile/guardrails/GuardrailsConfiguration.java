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

package ai.kompile.guardrails;

import ai.kompile.core.guardrails.GuardrailService;
import ai.kompile.core.guardrails.InputGuardrail;
import ai.kompile.core.guardrails.OutputGuardrail;
import ai.kompile.guardrails.input.*;
import ai.kompile.guardrails.output.*;
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
 * Configuration for guardrail beans.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GuardrailsProperties.class)
@ConditionalOnClass(name = "ai.kompile.guardrails.GuardrailsConfiguration")
@ConditionalOnProperty(name = "kompile.guardrails.enabled", havingValue = "true", matchIfMissing = false)
public class GuardrailsConfiguration {

    @Bean
    public GuardrailService guardrailService(
            @Autowired(required = false) List<InputGuardrail> inputGuardrails,
            @Autowired(required = false) List<OutputGuardrail> outputGuardrails,
            GuardrailsProperties properties) {
        return new DefaultGuardrailService(
                inputGuardrails != null ? inputGuardrails : List.of(),
                outputGuardrails != null ? outputGuardrails : List.of(),
                properties);
    }

    // Input Guardrails

    @Bean
    @ConditionalOnProperty(name = "kompile.guardrails.input.prompt-injection.enabled", havingValue = "true")
    @ConditionalOnBean(ChatClient.Builder.class)
    public InputGuardrail promptInjectionGuardrail(
            ChatClient.Builder chatClientBuilder,
            GuardrailsProperties properties) {
        return new PromptInjectionGuardrail(chatClientBuilder.build(), properties);
    }

    @Bean
    @ConditionalOnProperty(name = "kompile.guardrails.input.toxicity.enabled", havingValue = "true")
    @ConditionalOnBean(ChatClient.Builder.class)
    public InputGuardrail toxicityGuardrail(
            ChatClient.Builder chatClientBuilder,
            GuardrailsProperties properties) {
        return new ToxicityGuardrail(chatClientBuilder.build(), properties);
    }

    @Bean
    @ConditionalOnProperty(name = "kompile.guardrails.input.pii.enabled", havingValue = "true")
    public InputGuardrail piiGuardrail(GuardrailsProperties properties) {
        return new PiiDetectionGuardrail(properties);
    }

    @Bean
    @ConditionalOnProperty(name = "kompile.guardrails.input.topic.enabled", havingValue = "true")
    @ConditionalOnBean(ChatClient.Builder.class)
    public InputGuardrail topicGuardrail(
            ChatClient.Builder chatClientBuilder,
            GuardrailsProperties properties) {
        return new TopicGuardrail(chatClientBuilder.build(), properties);
    }

    // Output Guardrails

    @Bean
    @ConditionalOnProperty(name = "kompile.guardrails.output.hallucination.enabled", havingValue = "true")
    @ConditionalOnBean(ChatClient.Builder.class)
    public OutputGuardrail hallucinationGuardrail(
            ChatClient.Builder chatClientBuilder,
            GuardrailsProperties properties) {
        return new HallucinationGuardrail(chatClientBuilder.build(), properties);
    }

    @Bean
    @ConditionalOnProperty(name = "kompile.guardrails.output.format.enabled", havingValue = "true")
    public OutputGuardrail formatGuardrail(GuardrailsProperties properties) {
        return new FormatGuardrail(properties);
    }

    @Bean
    @ConditionalOnProperty(name = "kompile.guardrails.output.relevancy.enabled", havingValue = "true")
    @ConditionalOnBean(ChatClient.Builder.class)
    public OutputGuardrail relevancyGuardrail(
            ChatClient.Builder chatClientBuilder,
            GuardrailsProperties properties) {
        return new RelevancyGuardrail(chatClientBuilder.build(), properties);
    }
}
