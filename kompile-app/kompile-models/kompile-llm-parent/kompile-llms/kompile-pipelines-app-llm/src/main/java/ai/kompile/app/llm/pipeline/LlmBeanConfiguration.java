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

import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.core.llm.chat.LLMChatFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Creates a serving-endpoint-backed {@link ChatModel} when the app is configured to use
 * a separate local LLM serving endpoint.
 *
 * <ul>
 *   <li><b>App-main</b> ({@code kompile.llm.serving.url} is set): creates a
 *       {@link SubprocessLanguageModelImpl} — a thin HTTP client forwarding to the
 *       app-owned LLM serving subprocess.</li>
 *   <li><b>Subprocess</b> ({@code kompile.llm.serving.url} empty/absent):
 *       no staging-backed {@link ChatModel} is created here.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class LlmBeanConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(LlmBeanConfiguration.class);

    @Bean
    @ConditionalOnProperty(name = "kompile.llm.serving.url")
    public ChatModel stagingSubprocessChatModel(
            @Value("${kompile.llm.serving.url:}") String servingUrl) {
        if (servingUrl == null || servingUrl.isBlank()) {
            throw new IllegalStateException("kompile.llm.serving.url is set but blank");
        }
        logger.info("Creating serving-endpoint-backed ChatModel → {}", servingUrl);
        return new SubprocessLanguageModelImpl(servingUrl);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "kompile.llm.serving.url")
    public LLMChat stagingSubprocessLlmChat(ChatModel stagingSubprocessChatModel) {
        logger.info("Creating primary serving-endpoint-backed LLMChat");
        return LLMChatFactory.create(ChatClient.builder(stagingSubprocessChatModel));
    }
}
