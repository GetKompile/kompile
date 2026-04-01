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

import lombok.Getter;

/**
 * Event published when an LLM token is received during streaming.
 * Used for real-time token-by-token streaming to connected clients.
 */
@Getter
public class LlmTokenEvent extends OrchestratorEvent {

    private final Long sessionId;
    private final String token;
    private final int tokenIndex;
    private final boolean isComplete;
    private final String providerId;

    private LlmTokenEvent(Object source, String orchestratorInstanceId,
                          Long sessionId, String token, int tokenIndex,
                          boolean isComplete, String providerId) {
        super(source, orchestratorInstanceId, OrchestratorEventType.LLM_TOKEN_RECEIVED,
                isComplete ? "Stream complete" : null);
        this.sessionId = sessionId;
        this.token = token;
        this.tokenIndex = tokenIndex;
        this.isComplete = isComplete;
        this.providerId = providerId;
    }

    /**
     * Create a token event.
     */
    public static LlmTokenEvent token(Object source, String orchestratorInstanceId,
                                       Long sessionId, String token, int tokenIndex,
                                       String providerId) {
        return new LlmTokenEvent(source, orchestratorInstanceId, sessionId,
                token, tokenIndex, false, providerId);
    }

    /**
     * Create a stream complete event.
     */
    public static LlmTokenEvent complete(Object source, String orchestratorInstanceId,
                                          Long sessionId, int totalTokens, String providerId) {
        return new LlmTokenEvent(source, orchestratorInstanceId, sessionId,
                null, totalTokens, true, providerId);
    }

    /**
     * Create an error event during streaming.
     */
    public static LlmTokenEvent error(Object source, String orchestratorInstanceId,
                                       Long sessionId, String errorMessage, String providerId) {
        LlmTokenEvent event = new LlmTokenEvent(source, orchestratorInstanceId, sessionId,
                null, -1, true, providerId);
        return event;
    }
}
