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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.render.CompactionService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Protocol-correct envelope replay of a portable conversation projection into a
 * {@link DirectLlmClient} wire history. Extracted from
 * {@link DirectSubagentCompactor} so Tier-2 compaction and subagent resume share
 * one implementation: dangling tool calls are closed with synthetic cancellation
 * results so the wire stays valid, and SYSTEM summaries replay as the established
 * user/assistant acknowledgement pair.
 */
final class ConversationReplay {

    private ConversationReplay() {
    }

    /** Replace the client's history with the projection, wire-valid. */
    static void replay(DirectLlmClient client, String modelOverride,
                       List<CompactionService.ConversationEntry> entries) {
        replay(client, modelOverride, entries, false);
    }

    static void replay(DirectLlmClient client, String modelOverride,
                       List<CompactionService.ConversationEntry> entries, boolean keepNativeProcess) {
        if (keepNativeProcess) client.clearHistoryForProviderSwitch();
        else client.clearHistory();
        Map<String, CompactionService.ConversationEntry> pendingCalls = new LinkedHashMap<>();
        int index = 0;
        while (index < entries.size()) {
            CompactionService.ConversationEntry entry = entries.get(index);
            if (entry == null || ((entry.content == null || entry.content.isBlank())
                    && entry.type != CompactionService.EntryType.TOOL_CALL
                    && entry.type != CompactionService.EntryType.TOOL_RESULT)) {
                index++;
                continue;
            }
            if (entry.type == CompactionService.EntryType.TOOL_CALL) {
                List<DirectLlmClient.ReplayedToolCallInput> calls = new ArrayList<>();
                while (index < entries.size()) {
                    CompactionService.ConversationEntry call = entries.get(index);
                    if (call == null || call.type != CompactionService.EntryType.TOOL_CALL) break;
                    calls.add(new DirectLlmClient.ReplayedToolCallInput(
                            call.toolName, call.toolCallId, call.content));
                    if (call.toolCallId != null && !call.toolCallId.isBlank()) {
                        pendingCalls.put(call.toolCallId, call);
                    }
                    index++;
                }
                client.addReplayedToolCalls(calls, modelOverride);
                continue;
            }
            if (entry.type == CompactionService.EntryType.TOOL_RESULT) {
                List<DirectLlmClient.ToolCallResultInput> results = new ArrayList<>();
                while (index < entries.size()) {
                    CompactionService.ConversationEntry result = entries.get(index);
                    if (result == null || result.type != CompactionService.EntryType.TOOL_RESULT) break;
                    results.add(new DirectLlmClient.ToolCallResultInput(
                            result.toolCallId, result.toolName, result.content, false));
                    pendingCalls.remove(result.toolCallId);
                    index++;
                }
                client.addReplayedToolResults(results, modelOverride);
                continue;
            }
            switch (entry.type) {
                case SYSTEM -> {
                    client.addToHistory("user", "[Conversation summary]\n" + entry.content);
                    client.addToHistory("assistant",
                            "Understood. I will continue from that conversation summary.");
                }
                case USER, ASSISTANT -> client.addToHistory(entry.role, entry.content);
                case TOOL_CALL, TOOL_RESULT -> { }
            }
            index++;
        }
        if (!pendingCalls.isEmpty()) {
            List<DirectLlmClient.ToolCallResultInput> cancellations = new ArrayList<>();
            for (CompactionService.ConversationEntry pending : pendingCalls.values()) {
                cancellations.add(new DirectLlmClient.ToolCallResultInput(
                        pending.toolCallId, pending.toolName,
                        "Tool call was cancelled before execution; no result was recorded.", true));
            }
            client.addReplayedToolResults(cancellations, modelOverride);
        }
    }
}
