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

package ai.kompile.core.guardrails;

import lombok.Builder;
import lombok.Data;
import org.springframework.ai.chat.messages.Message;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Context for guardrail validation operations.
 */
@Data
@Builder
public class GuardrailContext {

    /**
     * Conversation history for context-aware validation.
     */
    @Builder.Default
    private List<Message> conversationHistory = Collections.emptyList();

    /**
     * User identifier for user-specific rules.
     */
    private String userId;

    /**
     * Session identifier.
     */
    private String sessionId;

    /**
     * Custom blocked terms/phrases.
     */
    @Builder.Default
    private Set<String> blockedTerms = Collections.emptySet();

    /**
     * Custom allowed terms that override default blocks.
     */
    @Builder.Default
    private Set<String> allowedTerms = Collections.emptySet();

    /**
     * The domain or topic context.
     */
    private String domain;

    /**
     * Sensitivity level for detection (0.0 to 1.0).
     * Higher = more sensitive (more false positives).
     */
    @Builder.Default
    private double sensitivityLevel = 0.5;

    /**
     * Additional metadata.
     */
    @Builder.Default
    private Map<String, Object> metadata = Collections.emptyMap();

    /**
     * Whether to include detailed violation information.
     */
    @Builder.Default
    private boolean includeDetails = true;

    /**
     * Create an empty context.
     *
     * @return An empty GuardrailContext
     */
    public static GuardrailContext empty() {
        return GuardrailContext.builder().build();
    }

    /**
     * Create a context with a user ID.
     *
     * @param userId The user identifier
     * @return A GuardrailContext with the user ID set
     */
    public static GuardrailContext forUser(String userId) {
        return GuardrailContext.builder()
                .userId(userId)
                .build();
    }

    /**
     * Create a context with conversation history.
     *
     * @param history The conversation history
     * @return A GuardrailContext with history
     */
    public static GuardrailContext withHistory(List<Message> history) {
        return GuardrailContext.builder()
                .conversationHistory(history != null ? history : Collections.emptyList())
                .build();
    }
}
