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

package ai.kompile.core.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@ConditionalOnMissingBean(value = LanguageModel.class, ignored = NoOpLanguageModelImpl.class)
public class NoOpLanguageModelImpl implements LanguageModel {
    private static final Logger logger = LoggerFactory.getLogger(NoOpLanguageModelImpl.class);

    public NoOpLanguageModelImpl() {
        logger.warn("No specific LanguageModel implementation found. Initializing NoOpLanguageModelImpl. Invocations will fail until a real LanguageModel is configured.");
    }

    @Override
    public String generateResponse(String userQuery, List<String> context) {
        throw notConfigured();
    }

    @Override
    public ChatResponse generateResponseWithPotentialToolCalls(String userQuery, List<String> context) {
        throw notConfigured();
    }

    private IllegalStateException notConfigured() {
        return new IllegalStateException("LanguageModel is not configured");
    }
}