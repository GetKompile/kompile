/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.services;

import ai.kompile.agent.graph.AgentConversationSession;
import ai.kompile.agent.graph.AgentGraphSnapshot;
import ai.kompile.agent.graph.AgentInstanceStore;
import ai.kompile.agent.graph.AgentPrincipal;
import ai.kompile.agent.graph.AgentPrivateGraphContextAssembler;
import ai.kompile.agent.graph.AgentPrivateGraphSession;
import ai.kompile.agent.graph.ConversationEvent;
import ai.kompile.agent.graph.ConversationEventDraft;
import ai.kompile.agent.graph.ConversationEventType;
import ai.kompile.agent.graph.ConversationLoadLimits;
import ai.kompile.agent.graph.ConversationRole;
import ai.kompile.agent.graph.LocalOwnerIdentity;
import ai.kompile.app.services.agent.ProvisionedAgentRuntime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** App-main implementation of the provider-neutral provisioned-agent runtime contract. */
public final class AdminProvisionedAgentRuntimeService implements ProvisionedAgentRuntime {

    public static final int MAX_HISTORY_EVENTS = ProvisionedAgentRuntime.MAX_CONTEXT_HISTORY_EVENTS;
    public static final int MAX_HISTORY_CONTENT_BYTES = ConversationEventDraft.MAX_CONTENT_BYTES;
    public static final int MAX_PROJECTED_EVENT_CONTENT_BYTES =
            ProvisionedAgentRuntime.MAX_CONTEXT_EVENT_CONTENT_BYTES;
    public static final int MAX_PROJECTED_METADATA_ENTRIES =
            ProvisionedAgentRuntime.MAX_CONTEXT_METADATA_ENTRIES;
    public static final int MAX_PROJECTED_METADATA_VALUE_BYTES =
            ProvisionedAgentRuntime.MAX_CONTEXT_METADATA_VALUE_BYTES;

    private static final Logger LOG = LoggerFactory.getLogger(
            AdminProvisionedAgentRuntimeService.class);

    private final AgentInstanceStore store;
    private final LocalOwnerIdentity localOwner;
    private final AgentPrivateGraphContextAssembler contextAssembler;
    private final AgentPrivateGraphToolFactory toolFactory;

    public AdminProvisionedAgentRuntimeService(
            AgentInstanceStore store,
            LocalOwnerIdentity localOwner,
            AgentPrivateGraphContextAssembler contextAssembler,
            AgentPrivateGraphToolFactory toolFactory) {
        this.store = Objects.requireNonNull(store, "store");
        this.localOwner = Objects.requireNonNull(localOwner, "localOwner");
        this.contextAssembler = Objects.requireNonNull(contextAssembler, "contextAssembler");
        this.toolFactory = Objects.requireNonNull(toolFactory, "toolFactory");
    }

    @Override
    public RuntimeContext prepare(PrepareRequest request) {
        Objects.requireNonNull(request, "request");
        AgentPrincipal principal = principal(request.provisionedAgentId());
        requireLocalAgent(principal);
        try {
            AgentPrivateGraphSession graphSession = store.open(principal);
            AgentGraphSnapshot snapshot = graphSession.read();
            AgentConversationSession conversation = store.openConversation(
                    principal, request.externalConversationKey());
            var tail = conversation.loadTail(new ConversationLoadLimits(
                    MAX_HISTORY_EVENTS, MAX_HISTORY_CONTENT_BYTES));
            return new RuntimeContext(
                    request.provisionedAgentId(),
                    request.externalConversationKey(),
                    contextAssembler.assemble(snapshot, principal, request.query()),
                    snapshot.revision().sha256(),
                    tail.events().stream().map(AdminProvisionedAgentRuntimeService::project).toList(),
                    tail.totalEvents(),
                    tail.truncated(),
                    toolFactory.descriptor());
        } catch (IOException failure) {
            throw internal("Could not prepare provisioned-agent runtime", failure);
        }
    }

    @Override
    public CanonicalEvent append(AppendEventsRequest request) {
        Objects.requireNonNull(request, "request");
        AgentPrincipal principal = principal(request.provisionedAgentId());
        requireLocalAgent(principal);
        try {
            return project(store.openConversation(principal, request.externalConversationKey())
                    .appendIdempotent(draft(request.event())));
        } catch (IOException failure) {
            throw internal("Could not append provisioned-agent conversation events", failure);
        }
    }

    @Override
    public ToolExecutionResult executeTool(ToolExecutionRequest request) {
        Objects.requireNonNull(request, "request");
        AgentPrincipal principal = principal(request.provisionedAgentId());
        requireLocalAgent(principal);
        try {
            AgentPrivateGraphSession session = store.open(principal);
            String result = toolFactory.executeBound(session, request.arguments());
            return new ToolExecutionResult(result, session.currentRevision().sha256());
        } catch (IOException failure) {
            throw internal("Could not execute provisioned-agent graph tool", failure);
        }
    }

    private AgentPrincipal principal(String canonicalAgentId) {
        try {
            UUID agentId = AgentProvisioningService.parseCanonicalAgentId(canonicalAgentId);
            return new AgentPrincipal(localOwner.ownerId(), agentId);
        } catch (IllegalArgumentException invalid) {
            throw new ProvisionedAgentRuntime.RuntimeException(400, invalid.getMessage(), invalid);
        }
    }

    private void requireLocalAgent(AgentPrincipal principal) {
        try {
            if (store.find(principal).isEmpty()) {
                throw new ProvisionedAgentRuntime.RuntimeException(
                        404, "Provisioned agent was not found");
            }
        } catch (IOException failure) {
            throw internal("Could not resolve provisioned agent", failure);
        }
    }

    private static CanonicalEvent project(ConversationEvent event) {
        String content = boundedUtf8(event.content(), MAX_PROJECTED_EVENT_CONTENT_BYTES);
        Map<String, String> metadata = new LinkedHashMap<>();
        int metadataLimit = content.equals(event.content())
                ? MAX_PROJECTED_METADATA_ENTRIES
                : MAX_PROJECTED_METADATA_ENTRIES - 1;
        event.metadata().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(metadataLimit)
                .forEach(entry -> metadata.put(
                        entry.getKey(),
                        boundedUtf8(entry.getValue(), MAX_PROJECTED_METADATA_VALUE_BYTES)));
        if (!content.equals(event.content())) {
            metadata.put("contentTruncated", "true");
        }
        return new CanonicalEvent(
                event.sequence(),
                event.timestamp(),
                EventKind.valueOf(event.type().name()),
                EventRole.valueOf(event.role().name()),
                content,
                metadata,
                event.idempotencyKey());
    }

    private static ConversationEventDraft draft(EventDraft event) {
        return new ConversationEventDraft(
                ConversationEventType.valueOf(event.kind().name()),
                ConversationRole.valueOf(event.role().name()),
                event.content(),
                event.metadata(),
                event.idempotencyKey());
    }

    private static String boundedUtf8(String value, int maximumBytes) {
        if (value.getBytes(StandardCharsets.UTF_8).length <= maximumBytes) {
            return value;
        }
        String suffix = "\n[canonical event content truncated]";
        int contentBudget = maximumBytes - suffix.getBytes(StandardCharsets.UTF_8).length;
        int end = Math.min(value.length(), maximumBytes / 2);
        String bounded = value.substring(0, end);
        while (bounded.getBytes(StandardCharsets.UTF_8).length > contentBudget && end > 0) {
            end = Math.max(0, end - 256);
            bounded = value.substring(0, end);
        }
        return bounded + suffix;
    }

    private static ProvisionedAgentRuntime.RuntimeException internal(
            String message,
            Exception failure) {
        String reference = UUID.randomUUID().toString();
        LOG.warn("{} reference={}", message, reference, failure);
        return new ProvisionedAgentRuntime.RuntimeException(
                500, message + "; reference=" + reference, failure);
    }
}
