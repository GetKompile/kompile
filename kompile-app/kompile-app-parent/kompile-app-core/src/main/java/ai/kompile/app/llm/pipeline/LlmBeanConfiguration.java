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
package ai.kompile.app.llm.pipeline;

import ai.kompile.cli.common.routing.ServiceEndpointsConfigManager;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.core.llm.chat.LLMChatFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Objects;

/**
 * Creates a serving-endpoint-backed {@link ChatModel} for application personas. The endpoint is
 * resolved from the UI/CLI-managed {@code service-endpoints.json} on every request, so changing
 * {@code servingUrl} does not require rebuilding or restarting a component.
 *
 * <ul>
 *   <li><b>Application persona</b> (direct-serving marker absent/false): creates a
 *       {@link SubprocessLanguageModelImpl} — a thin HTTP client forwarding to the
 *       configured LLM serving component.</li>
 *   <li><b>Serving child</b> ({@code kompile.llm.direct-serving.enabled=true}): does not create
 *       the proxy, preventing the serving process from forwarding to itself.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class LlmBeanConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(LlmBeanConfiguration.class);
    private final ServiceEndpointsConfigManager endpointConfigManager;

    public LlmBeanConfiguration() {
        this(ServiceEndpointsConfigManager.shared());
    }

    LlmBeanConfiguration(ServiceEndpointsConfigManager endpointConfigManager) {
        this.endpointConfigManager = Objects.requireNonNull(endpointConfigManager);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "kompile.llm.direct-serving.enabled", havingValue = "false", matchIfMissing = true)
    public SubprocessLanguageModelImpl managedServingChatModel() {
        logger.info("Creating managed serving-endpoint ChatModel → {}",
                endpointConfigManager.current().effectiveServingUrl());
        return new SubprocessLanguageModelImpl(
                () -> endpointConfigManager.current().effectiveServingUrl());
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "kompile.llm.direct-serving.enabled", havingValue = "false", matchIfMissing = true)
    public LLMChat managedServingLlmChat(
            @Qualifier("managedServingChatModel") ChatModel managedServingChatModel) {
        logger.info("Creating primary managed serving-endpoint LLMChat");
        return LLMChatFactory.create(ChatClient.builder(managedServingChatModel));
    }
}
