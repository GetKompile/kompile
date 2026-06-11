/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.agent;

import ai.kompile.core.graphrag.agent.ExtractionLlmService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link ExtractionLlmService} backed directly by the {@code @Primary}
 * Spring AI {@link ChatModel} bean (typically {@code SameDiffLanguageModelImpl}
 * which proxies to the local CUDA serving subprocess).
 *
 * <p>Uses {@code ChatModel} directly rather than {@code LLMChat} to avoid
 * being intercepted by CLI-agent-based LLMChat implementations.
 */
@Component
@Slf4j
public class LlmChatExtractionLlmService implements ExtractionLlmService {

    public static final String ID = "llm-chat";

    private ChatModel chatModel;

    @Autowired(required = false)
    public void setChatModel(ChatModel chatModel) {
        this.chatModel = chatModel;
        if (chatModel != null) {
            log.info("LlmChatExtractionLlmService: ChatModel configured ({})", chatModel.getClass().getSimpleName());
        }
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDescription() {
        return "Local LLM via Spring AI ChatModel (SameDiff / configured provider)";
    }

    @Override
    public String complete(String prompt) {
        if (chatModel == null) {
            throw new ExtractionLlmException("ChatModel is not available");
        }
        try {
            ChatResponse response = chatModel.call(
                    new Prompt(List.of(new UserMessage(prompt))));
            if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
                return null;
            }
            return response.getResults().get(0).getOutput().getText();
        } catch (ExtractionLlmException e) {
            throw e;
        } catch (Exception e) {
            throw new ExtractionLlmException("ChatModel completion failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isAvailable() {
        return chatModel != null;
    }
}
