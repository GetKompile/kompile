/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.services;

import ai.kompile.agent.graph.AgentInstance;
import ai.kompile.agent.graph.AgentInstanceStore;
import ai.kompile.agent.graph.AgentPrivateGraphContextAssembler;
import ai.kompile.agent.graph.LocalOwnerIdentity;
import ai.kompile.app.services.agent.ProvisionedAgentRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminProvisionedAgentRuntimeServiceTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void preparesBoundedContextCanonicalTailAndSelectorFreeToolForLocalOwnerOnly()
            throws Exception {
        UUID owner = UUID.randomUUID();
        AgentInstanceStore store = new AgentInstanceStore(tempDir.resolve("agents"));
        AgentInstance local = store.provision(owner, "Local");
        AgentInstance second = store.provision(owner, "Second");
        AgentInstance foreign = store.provision(UUID.randomUUID(), "Foreign");
        AdminProvisionedAgentRuntimeService runtime = runtime(store, owner);
        String localId = local.principal().agentId().toString();

        JsonNode rejectedSelector = mapper.readTree(runtime.executeTool(
                new ProvisionedAgentRuntime.ToolExecutionRequest(
                        localId, Map.of("action", "read", "ownerId", UUID.randomUUID().toString())))
                .result());
        assertFalse(rejectedSelector.path("success").asBoolean());
        assertEquals("invalid_arguments", rejectedSelector.path("operation").asText());

        JsonNode initial = mapper.readTree(runtime.executeTool(
                new ProvisionedAgentRuntime.ToolExecutionRequest(
                        localId, Map.of("action", "read"))).result());
        JsonNode mutated = mapper.readTree(runtime.executeTool(
                new ProvisionedAgentRuntime.ToolExecutionRequest(localId, Map.of(
                        "action", "upsert_entity",
                        "expectedRevision", initial.path("revision").asText(),
                        "entityId", "alpha",
                        "type", "PERSON",
                        "label", "Alpha"))).result());
        assertTrue(mutated.path("success").asBoolean());

        for (int index = 0; index < 45; index++) {
            runtime.append(new ProvisionedAgentRuntime.AppendEventsRequest(
                    localId,
                    "web:window",
                    new ProvisionedAgentRuntime.EventDraft(
                            ProvisionedAgentRuntime.EventKind.MESSAGE,
                            index % 2 == 0
                                    ? ProvisionedAgentRuntime.EventRole.USER
                                    : ProvisionedAgentRuntime.EventRole.ASSISTANT,
                            "message-" + index,
                            Map.of("index", Integer.toString(index)),
                            "history:event:" + index)));
        }

        ProvisionedAgentRuntime.RuntimeContext context = runtime.prepare(
                new ProvisionedAgentRuntime.PrepareRequest(
                        localId, "web:window", "alpha"));

        assertEquals(localId, context.provisionedAgentId());
        assertTrue(context.automaticContext().contains("Alpha"));
        assertTrue(context.automaticContext().contains(context.graphRevision()));
        assertEquals(ProvisionedAgentRuntime.MAX_CONTEXT_HISTORY_EVENTS, context.history().size());
        assertEquals(45, context.totalHistoryEvents());
        assertTrue(context.historyTruncated());
        assertEquals("message-13", context.history().get(0).content());
        assertEquals("agent_private_graph", context.privateGraphTool().name());

        @SuppressWarnings("unchecked")
        Set<String> schemaFields = ((Map<String, Object>) context.privateGraphTool()
                .inputSchema().get("properties")).keySet();
        assertFalse(schemaFields.contains("ownerId"));
        assertFalse(schemaFields.contains("provisionedAgentId"));
        assertFalse(schemaFields.contains("agentId"));
        assertFalse(schemaFields.contains("factSheetId"));
        assertFalse(schemaFields.contains("knowledgeBase"));
        assertFalse(schemaFields.contains("path"));

        JsonNode secondRead = mapper.readTree(runtime.executeTool(
                new ProvisionedAgentRuntime.ToolExecutionRequest(
                        second.principal().agentId().toString(), Map.of("action", "read"))).result());
        assertEquals(0, secondRead.path("entityCount").asInt());

        ProvisionedAgentRuntime.RuntimeException denied = assertThrows(
                ProvisionedAgentRuntime.RuntimeException.class,
                () -> runtime.prepare(new ProvisionedAgentRuntime.PrepareRequest(
                        foreign.principal().agentId().toString(), "web:window", "alpha")));
        assertEquals(404, denied.statusCode());
    }

    @Test
    void appendsOnlyExplicitOutcomesAndNeverPersistsPreparedGraphContext() throws Exception {
        UUID owner = UUID.randomUUID();
        AgentInstanceStore store = new AgentInstanceStore(tempDir.resolve("outcomes"));
        AgentInstance local = store.provision(owner, "Local");
        AdminProvisionedAgentRuntimeService runtime = runtime(store, owner);
        String agentId = local.principal().agentId().toString();

        runtime.prepare(new ProvisionedAgentRuntime.PrepareRequest(
                agentId, "api:conversation", "empty graph query"));
        runtime.append(new ProvisionedAgentRuntime.AppendEventsRequest(
                agentId,
                "api:conversation",
                new ProvisionedAgentRuntime.EventDraft(
                        ProvisionedAgentRuntime.EventKind.MESSAGE,
                        ProvisionedAgentRuntime.EventRole.USER,
                        "question",
                        Map.of(),
                        "turn:11111111-2222-3333-4444-555555555555:user")));
        runtime.append(new ProvisionedAgentRuntime.AppendEventsRequest(
                agentId,
                "api:conversation",
                new ProvisionedAgentRuntime.EventDraft(
                        ProvisionedAgentRuntime.EventKind.ERROR,
                        ProvisionedAgentRuntime.EventRole.SYSTEM,
                        "provider unavailable",
                        Map.of(),
                        "turn:11111111-2222-3333-4444-555555555555:terminal")));

        ProvisionedAgentRuntime.RuntimeContext after = runtime.prepare(
                new ProvisionedAgentRuntime.PrepareRequest(
                        agentId, "api:conversation", "retry"));
        assertEquals(List.of(
                        ProvisionedAgentRuntime.EventKind.MESSAGE,
                        ProvisionedAgentRuntime.EventKind.ERROR),
                after.history().stream().map(ProvisionedAgentRuntime.CanonicalEvent::kind).toList());
        assertFalse(after.history().stream().anyMatch(event ->
                event.kind() == ProvisionedAgentRuntime.EventKind.CONTEXT));
    }

    @Test
    void sameTurnRetryBeforeAndAfterRestartDeduplicatesUserAndTerminal() throws Exception {
        UUID owner = UUID.randomUUID();
        Path root = tempDir.resolve("retry");
        AgentInstanceStore store = new AgentInstanceStore(root);
        AgentInstance local = store.provision(owner, "Local");
        String agentId = local.principal().agentId().toString();
        String turn = "turn:11111111-2222-3333-4444-555555555555:";
        AdminProvisionedAgentRuntimeService first = runtime(store, owner);
        ProvisionedAgentRuntime.EventDraft user = new ProvisionedAgentRuntime.EventDraft(
                ProvisionedAgentRuntime.EventKind.MESSAGE,
                ProvisionedAgentRuntime.EventRole.USER,
                "question", Map.of(), turn + "user");
        ProvisionedAgentRuntime.EventDraft terminal = new ProvisionedAgentRuntime.EventDraft(
                ProvisionedAgentRuntime.EventKind.MESSAGE,
                ProvisionedAgentRuntime.EventRole.ASSISTANT,
                "answer", Map.of(), turn + "terminal");

        first.append(new ProvisionedAgentRuntime.AppendEventsRequest(agentId, "web:retry", user));
        first.append(new ProvisionedAgentRuntime.AppendEventsRequest(agentId, "web:retry", user));
        first.append(new ProvisionedAgentRuntime.AppendEventsRequest(agentId, "web:retry", terminal));

        AdminProvisionedAgentRuntimeService restarted = runtime(new AgentInstanceStore(root), owner);
        restarted.append(new ProvisionedAgentRuntime.AppendEventsRequest(agentId, "web:retry", user));
        restarted.append(new ProvisionedAgentRuntime.AppendEventsRequest(
                agentId,
                "web:retry",
                new ProvisionedAgentRuntime.EventDraft(
                        ProvisionedAgentRuntime.EventKind.ERROR,
                        ProvisionedAgentRuntime.EventRole.SYSTEM,
                        "losing retry", Map.of(), turn + "terminal")));

        ProvisionedAgentRuntime.RuntimeContext context = restarted.prepare(
                new ProvisionedAgentRuntime.PrepareRequest(agentId, "web:retry", "retry"));
        assertEquals(2, context.totalHistoryEvents());
        assertEquals(List.of(turn + "user", turn + "terminal"), context.history().stream()
                .map(ProvisionedAgentRuntime.CanonicalEvent::idempotencyKey).toList());
        assertEquals("answer", context.history().get(1).content());
    }

    private AdminProvisionedAgentRuntimeService runtime(AgentInstanceStore store, UUID owner) {
        return new AdminProvisionedAgentRuntimeService(
                store,
                new LocalOwnerIdentity(owner),
                new AgentPrivateGraphContextAssembler(),
                new AgentPrivateGraphToolFactory(mapper));
    }
}
