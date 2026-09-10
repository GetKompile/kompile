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
package ai.kompile.app.services;

import ai.kompile.agent.graph.AgentInstanceStore;
import ai.kompile.agent.graph.AgentConversationSession;
import ai.kompile.agent.graph.AgentPrincipal;
import ai.kompile.agent.graph.AgentPrivateGraphContextAssembler;
import ai.kompile.agent.graph.AgentPrivateGraphSession;
import ai.kompile.agent.graph.ConversationEvent;
import ai.kompile.agent.graph.ConversationEventDraft;
import ai.kompile.agent.graph.ConversationEventType;
import ai.kompile.agent.graph.ConversationLoadLimits;
import ai.kompile.agent.graph.ConversationRole;
import ai.kompile.agent.graph.LocalOwnerIdentity;
import ai.kompile.kclaw.agent.KClawExecutionScopeResolver;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Admin-persona implementation that binds KClaw to the server-owned local agent scope. */
public final class AdminKClawExecutionScopeResolver implements KClawExecutionScopeResolver {

    private final AgentInstanceStore store;
    private final LocalOwnerIdentity localOwner;
    private final AgentPrivateGraphContextAssembler contextAssembler;
    private final AgentPrivateGraphToolFactory toolFactory;
    private final String runtimeTemplateName;

    public AdminKClawExecutionScopeResolver(
            AgentInstanceStore store,
            LocalOwnerIdentity localOwner,
            AgentPrivateGraphContextAssembler contextAssembler,
            AgentPrivateGraphToolFactory toolFactory,
            String runtimeTemplateName) {
        this.store = Objects.requireNonNull(store, "store");
        this.localOwner = Objects.requireNonNull(localOwner, "localOwner");
        this.contextAssembler = Objects.requireNonNull(contextAssembler, "contextAssembler");
        this.toolFactory = Objects.requireNonNull(toolFactory, "toolFactory");
        if (runtimeTemplateName == null || runtimeTemplateName.isBlank()) {
            throw new IllegalArgumentException("runtimeTemplateName is required");
        }
        this.runtimeTemplateName = runtimeTemplateName;
    }

    @Override
    public Optional<ResolvedExecutionScope> resolve(String canonicalAgentId, String query)
            throws Exception {
        UUID agentId = parseCanonicalUuid(canonicalAgentId);
        AgentPrincipal principal = new AgentPrincipal(localOwner.ownerId(), agentId);
        if (store.find(principal).isEmpty()) {
            return Optional.empty();
        }
        AgentPrivateGraphSession session = store.open(principal);
        return Optional.of(new ResolvedExecutionScope(
                canonicalAgentId,
                runtimeTemplateName,
                contextAssembler.assemble(session, query),
                java.util.List.of(toolFactory.create(session))));
    }

    @Override
    public Optional<ScopedConversation> resolveConversation(
            String canonicalAgentId,
            String externalConversationKey) throws Exception {
        UUID agentId = parseCanonicalUuid(canonicalAgentId);
        AgentPrincipal principal = new AgentPrincipal(localOwner.ownerId(), agentId);
        if (store.find(principal).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new StoreBackedConversation(
                store.openConversation(principal, externalConversationKey)));
    }

    @Override
    public boolean exists(String canonicalAgentId) throws Exception {
        UUID agentId = parseCanonicalUuid(canonicalAgentId);
        return store.find(new AgentPrincipal(localOwner.ownerId(), agentId)).isPresent();
    }

    private static UUID parseCanonicalUuid(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Agent UUID is required");
        }
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new IllegalArgumentException("Agent UUID must use canonical lowercase form");
            }
            return parsed;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid canonical agent UUID", invalid);
        }
    }

    private static final class StoreBackedConversation implements ScopedConversation {

        private final AgentConversationSession session;

        private StoreBackedConversation(AgentConversationSession session) {
            this.session = session;
        }

        @Override
        public ConversationHistory loadTail(int maxEvents, int maxContentBytes) throws Exception {
            var tail = session.loadTail(new ConversationLoadLimits(maxEvents, maxContentBytes));
            return new ConversationHistory(
                    tail.events().stream().map(StoreBackedConversation::toEntry).toList(),
                    tail.totalEvents(),
                    tail.truncated());
        }

        @Override
        public void appendAll(List<ConversationEntry> entries) throws Exception {
            session.appendAll(entries.stream().map(StoreBackedConversation::toDraft).toList());
        }

        @Override
        public boolean appendMigrationIfEmpty(
                String migrationSource,
                List<ConversationEntry> importedEntries) throws Exception {
            return session.appendMigrationIfEmpty(
                    migrationSource,
                    importedEntries.stream().map(StoreBackedConversation::toDraft).toList());
        }

        @Override
        public void clear() throws Exception {
            session.clear();
        }

        private static ConversationEntry toEntry(ConversationEvent event) {
            ConversationKind kind = switch (event.type()) {
                case MESSAGE -> switch (event.role()) {
                    case USER -> ConversationKind.USER;
                    case ASSISTANT -> ConversationKind.ASSISTANT;
                    case SYSTEM -> ConversationKind.SYSTEM;
                    case CONTEXT -> ConversationKind.CONTEXT;
                    case TOOL -> ConversationKind.TOOL_RESULT;
                };
                case CONTEXT -> ConversationKind.CONTEXT;
                case TOOL_CALL -> ConversationKind.TOOL_CALL;
                case TOOL_RESULT -> ConversationKind.TOOL_RESULT;
                case ERROR -> ConversationKind.ERROR;
                case CANCELLED -> ConversationKind.CANCELLED;
                case COMPACTION -> ConversationKind.COMPACTION;
                case MIGRATION -> ConversationKind.MIGRATION;
            };
            return new ConversationEntry(kind, event.content(), event.metadata());
        }

        private static ConversationEventDraft toDraft(ConversationEntry entry) {
            return switch (entry.kind()) {
                case USER -> draft(
                        ConversationEventType.MESSAGE, ConversationRole.USER, entry);
                case ASSISTANT -> draft(
                        ConversationEventType.MESSAGE, ConversationRole.ASSISTANT, entry);
                case SYSTEM -> draft(
                        ConversationEventType.MESSAGE, ConversationRole.SYSTEM, entry);
                case CONTEXT -> draft(
                        ConversationEventType.CONTEXT, ConversationRole.CONTEXT, entry);
                case TOOL_CALL -> draft(
                        ConversationEventType.TOOL_CALL, ConversationRole.TOOL, entry);
                case TOOL_RESULT -> draft(
                        ConversationEventType.TOOL_RESULT, ConversationRole.TOOL, entry);
                case ERROR -> draft(
                        ConversationEventType.ERROR, ConversationRole.SYSTEM, entry);
                case CANCELLED -> draft(
                        ConversationEventType.CANCELLED, ConversationRole.SYSTEM, entry);
                case COMPACTION -> draft(
                        ConversationEventType.COMPACTION, ConversationRole.SYSTEM, entry);
                case MIGRATION -> draft(
                        ConversationEventType.MIGRATION, ConversationRole.SYSTEM, entry);
            };
        }

        private static ConversationEventDraft draft(
                ConversationEventType type,
                ConversationRole role,
                ConversationEntry entry) {
            return new ConversationEventDraft(type, role, entry.content(), entry.metadata());
        }
    }
}
