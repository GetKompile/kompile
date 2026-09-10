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
package ai.kompile.kclaw.agent;

import ai.kompile.react.model.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Optional server-persona bridge from a stable provisioned-agent UUID to one KClaw execution.
 *
 * <p>The contract deliberately contains only neutral strings and ReAct tool definitions. KClaw,
 * gateway-core, and crawl personas therefore do not need the private graph implementation on their
 * classpaths. Implementations are responsible for deriving any owner scope from trusted server
 * state rather than from request or model-selected graph selectors.</p>
 */
public interface KClawExecutionScopeResolver {

    /** Resolve one canonical lowercase UUID, or return empty when it is not in the trusted scope. */
    Optional<ResolvedExecutionScope> resolve(String canonicalAgentId, String query) throws Exception;

    /**
     * Resolve only the selector-free canonical conversation capability. Implementations must not
     * assemble graph context or tools for this lightweight execution/session-management path.
     */
    default Optional<ScopedConversation> resolveConversation(
            String canonicalAgentId,
            String externalConversationKey) throws Exception {
        return Optional.empty();
    }

    /** Lightweight existence check for session-management operations that need no graph context/tools. */
    default boolean exists(String canonicalAgentId) throws Exception {
        return resolve(canonicalAgentId, "").isPresent();
    }

    /** Provider-neutral event projection used by KClaw without depending on agent-graph. */
    enum ConversationKind {
        USER,
        ASSISTANT,
        SYSTEM,
        CONTEXT,
        TOOL_CALL,
        TOOL_RESULT,
        ERROR,
        CANCELLED,
        COMPACTION,
        MIGRATION
    }

    record ConversationEntry(
            ConversationKind kind,
            String content,
            Map<String, String> metadata) {

        public ConversationEntry {
            Objects.requireNonNull(kind, "kind");
            content = content == null ? "" : content;
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }

        public ConversationEntry(ConversationKind kind, String content) {
            this(kind, content, Map.of());
        }
    }

    record ConversationHistory(
            List<ConversationEntry> entries,
            long totalEvents,
            boolean truncated) {

        public ConversationHistory {
            entries = entries == null ? List.of() : List.copyOf(entries);
            if (totalEvents < entries.size()) {
                throw new IllegalArgumentException(
                        "totalEvents cannot be smaller than returned entries");
            }
        }
    }

    /** Selector-free, agent/key-bound durable conversation operations. */
    interface ScopedConversation {
        ConversationHistory loadTail(int maxEvents, int maxContentBytes) throws Exception;

        void appendAll(List<ConversationEntry> entries) throws Exception;

        boolean appendMigrationIfEmpty(
                String migrationSource,
                List<ConversationEntry> importedEntries) throws Exception;

        void clear() throws Exception;
    }

    /** Per-request values that KClaw can safely merge without retaining global mutable tool state. */
    record ResolvedExecutionScope(
            String stableAgentId,
            String runtimeAgentName,
            String scopedContext,
            List<ToolDefinition> scopedTools) {

        public ResolvedExecutionScope {
            Objects.requireNonNull(stableAgentId, "stableAgentId");
            Objects.requireNonNull(runtimeAgentName, "runtimeAgentName");
            scopedContext = scopedContext == null ? "" : scopedContext;
            scopedTools = scopedTools == null ? List.of() : List.copyOf(scopedTools);
        }
    }
}
