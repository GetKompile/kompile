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

package ai.kompile.core.llm.memory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for Kompile chat memory components.
 * This configuration class automatically sets up chat memory beans when they are not
 * already defined in the application context.
 *
 * <p>By default, it provides an in-memory implementation with a message window strategy.
 * Users can override these beans to provide custom implementations for different
 * storage backends or memory strategies.</p>
 *
 * <p>Configuration is read from {@code ~/.kompile/config/chat-memory-config.json}
 * via {@link ChatMemoryConfigService}.</p>
 *
 * @author Kompile Inc.
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
public class KompileChatMemoryConfiguration {

    /**
     * Provides an in-memory chat memory repository if none is already configured.
     *
     * @return the in-memory repository
     */
    @Bean
    @ConditionalOnMissingBean
    public KompileChatMemoryRepository kompileChatMemoryRepository() {
        return new InMemoryKompileChatMemoryRepository();
    }

    /**
     * Provides a message window chat memory implementation if none is already configured.
     * Configuration is sourced from {@link ChatMemoryConfigService}.
     *
     * @param repository    the chat memory repository
     * @param configService the JSON-based chat memory config service
     * @return the message window chat memory
     */
    @Bean
    @ConditionalOnMissingBean
    public KompileChatMemory kompileChatMemory(
            KompileChatMemoryRepository repository,
            ChatMemoryConfigService configService) {
        return MessageWindowKompileChatMemory.builder()
                .repository(repository)
                .maxMessages(configService.getMaxMessages())
                .build();
    }

    /**
     * Provides a Spring AI ChatMemory adapter for interoperability.
     * This allows Kompile's chat memory to work with Spring AI's ChatClient and advisors.
     *
     * @param kompileChatMemory the Kompile chat memory implementation
     * @return the Spring AI ChatMemory adapter
     */
    @Bean
    @ConditionalOnMissingBean(ChatMemory.class)
    public ChatMemory springAiChatMemory(KompileChatMemory kompileChatMemory) {
        return new SpringAiChatMemoryAdapter(kompileChatMemory);
    }
}
