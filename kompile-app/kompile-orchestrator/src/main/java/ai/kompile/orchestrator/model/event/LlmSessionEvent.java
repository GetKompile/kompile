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
package ai.kompile.orchestrator.model.event;

import ai.kompile.orchestrator.model.llm.LlmSession;
import ai.kompile.orchestrator.model.llm.LlmSessionStatus;
import lombok.Getter;

/**
 * Event fired for LLM session lifecycle changes.
 */
@Getter
public class LlmSessionEvent extends OrchestratorEvent {

    private final Long sessionId;
    private final String providerId;
    private final LlmSessionStatus status;
    private final String triggerId;
    private final Integer tokensUsed;

    public LlmSessionEvent(Object source, String orchestratorInstanceId,
                           OrchestratorEventType eventType, Long sessionId,
                           String providerId, LlmSessionStatus status,
                           String triggerId, Integer tokensUsed) {
        super(source, orchestratorInstanceId, eventType,
                String.format("LLM session %d (%s): %s", sessionId, providerId, status));
        this.sessionId = sessionId;
        this.providerId = providerId;
        this.status = status;
        this.triggerId = triggerId;
        this.tokensUsed = tokensUsed;
    }

    /**
     * Create from an LlmSession.
     */
    public static LlmSessionEvent from(Object source, LlmSession session, OrchestratorEventType eventType) {
        return new LlmSessionEvent(source, session.getOrchestratorInstanceId(),
                eventType, session.getId(), session.getProviderId(),
                session.getStatus(), session.getTriggerId(), session.getTotalTokens());
    }

    /**
     * Create a session started event.
     */
    public static LlmSessionEvent started(Object source, LlmSession session) {
        return from(source, session, OrchestratorEventType.LLM_SESSION_STARTED);
    }

    /**
     * Create a session completed event.
     */
    public static LlmSessionEvent completed(Object source, LlmSession session) {
        return from(source, session, OrchestratorEventType.LLM_SESSION_COMPLETED);
    }

    /**
     * Create a session failed event.
     */
    public static LlmSessionEvent failed(Object source, LlmSession session) {
        return from(source, session, OrchestratorEventType.LLM_SESSION_FAILED);
    }

    /**
     * Create a session failed event with error message.
     */
    public static LlmSessionEvent failed(Object source, LlmSession session, String errorMessage) {
        return from(source, session, OrchestratorEventType.LLM_SESSION_FAILED);
    }

    /**
     * Create a session cancelled event.
     */
    public static LlmSessionEvent cancelled(Object source, LlmSession session) {
        return from(source, session, OrchestratorEventType.LLM_SESSION_FAILED);
    }
}
