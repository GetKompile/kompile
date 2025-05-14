/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.core.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Collections;

@Service
public class NoOpLanguageModelImpl implements LanguageModel {
    private static final Logger logger = LoggerFactory.getLogger(NoOpLanguageModelImpl.class);

    public NoOpLanguageModelImpl() {
        logger.warn("No specific LanguageModel implementation found. Initializing NoOpLanguageModelImpl. LLM functionality will be disabled.");
    }

    @Override
    public String generateResponse(String userQuery, List<String> context) {
        String message = "Language Model is not configured. Cannot generate response.";
        logger.warn(message + " Query: " + userQuery);
        return "Error: " + message;
    }

    @Override
    public ChatResponse generateResponseWithPotentialToolCalls(String userQuery, List<String> context) {
        String message = "Language Model is not configured. Cannot generate response with tool calls.";
        logger.warn(message + " Query: " + userQuery);
        // Return a dummy ChatResponse indicating an error or no-op
        Generation generation = new Generation(new AssistantMessage("Error: " + message), null);
        return new ChatResponse(Collections.singletonList(generation));
    }
}