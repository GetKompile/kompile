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

package ai.kompile.app.web.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Request DTO for manually compacting a chat window's conversation history
 * ({@code POST /api/agents/chat/compact}). The history is summarized through the same
 * lane the agent chats on, bounded by that model's real context window.
 */
@Getter
@Setter
public class AgentChatCompactRequest {

    /** Agent the session chats with — determines the summarization lane and context budget. */
    private String agentName;

    /** Full conversation history of the chat window, oldest first. */
    private List<AgentChatRequest.ChatHistoryEntry> chatHistory;

    /** Optional user hint about what the summary must preserve. */
    private String focusInstruction;
}
