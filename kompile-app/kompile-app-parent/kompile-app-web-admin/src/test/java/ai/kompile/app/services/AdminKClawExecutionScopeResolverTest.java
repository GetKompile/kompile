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

import ai.kompile.agent.graph.AgentInstance;
import ai.kompile.agent.graph.AgentInstanceStore;
import ai.kompile.agent.graph.AgentPrincipal;
import ai.kompile.agent.graph.AgentPrivateGraphContextAssembler;
import ai.kompile.agent.graph.LocalOwnerIdentity;
import ai.kompile.gateway.core.model.AgentDefinition;
import ai.kompile.gateway.core.service.AgentRegistry;
import ai.kompile.gateway.core.service.SessionService;
import ai.kompile.kclaw.agent.KClawAgentService;
import ai.kompile.kclaw.agent.ToolkitRegistry;
import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.kclaw.agent.KClawExecutionScopeResolver.ResolvedExecutionScope;
import ai.kompile.kclaw.agent.KClawExecutionScopeResolver.ConversationEntry;
import ai.kompile.kclaw.agent.KClawExecutionScopeResolver.ConversationKind;
import ai.kompile.kclaw.model.KClawRequest;
import ai.kompile.react.context.AgentContext;
import ai.kompile.react.model.ReActResult;
import ai.kompile.react.model.TokenUsage;
import ai.kompile.react.service.ReActAgentService;
import ai.kompile.react.model.ToolDefinition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminKClawExecutionScopeResolverTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void resolvesOnlyExactLocalOwnerInstanceAndUsesServerSelectedTemplate() throws Exception {
        AgentInstanceStore store = new AgentInstanceStore(tempDir.resolve("agents"));
        UUID localOwner = UUID.randomUUID();
        AgentInstance local = store.provision(localOwner, "Display name is not policy");
        AgentInstance foreign = store.provision(UUID.randomUUID(), "Foreign");
        AdminKClawExecutionScopeResolver resolver = resolver(store, localOwner);

        ResolvedExecutionScope resolved = resolver.resolve(
                local.principal().agentId().toString(), "hello").orElseThrow();

        assertEquals(local.principal().agentId().toString(), resolved.stableAgentId());
        assertEquals("jarvis", resolved.runtimeAgentName());
        assertEquals(1, resolved.scopedTools().size());
        assertTrue(resolver.resolve(foreign.principal().agentId().toString(), "hello").isEmpty());
        assertTrue(resolver.resolve(UUID.randomUUID().toString(), "hello").isEmpty());
    }

    @Test
    void scopedToolHasNoSelectorsAndRoundTripsRevisionedMutationsWithoutCrossAgentAccess()
            throws Exception {
        AgentInstanceStore store = new AgentInstanceStore(tempDir.resolve("agents"));
        UUID owner = UUID.randomUUID();
        AgentInstance first = store.provision(owner, "First");
        AgentInstance second = store.provision(owner, "Second");
        AdminKClawExecutionScopeResolver resolver = resolver(store, owner);
        ToolDefinition firstTool = resolver.resolve(
                first.principal().agentId().toString(), "alpha").orElseThrow()
                .scopedTools().get(0);
        ToolDefinition secondTool = resolver.resolve(
                second.principal().agentId().toString(), "alpha").orElseThrow()
                .scopedTools().get(0);

        @SuppressWarnings("unchecked")
        Set<String> propertyNames = ((Map<String, Object>) firstTool.getParameters().get("properties"))
                .keySet();
        assertFalse(propertyNames.contains("ownerId"));
        assertFalse(propertyNames.contains("agentId"));
        assertFalse(propertyNames.contains("factSheetId"));
        assertFalse(propertyNames.contains("knowledgeBase"));
        assertFalse(propertyNames.contains("path"));

        String initialRevision = execute(firstTool, Map.of("action", "read"))
                .path("revision").asText();
        JsonNode firstEntity = execute(firstTool, Map.of(
                "action", "upsert_entity",
                "expectedRevision", initialRevision,
                "entityId", "alpha",
                "type", "PERSON",
                "label", "Alpha",
                "attributes", Map.of("team", "finance")));
        assertTrue(firstEntity.path("success").asBoolean());
        String revisionAfterFirst = firstEntity.path("revision").asText();
        JsonNode dangling = execute(firstTool, Map.of(
                "action", "upsert_relation",
                "expectedRevision", revisionAfterFirst,
                "relationId", "dangling",
                "sourceId", "alpha",
                "targetId", "missing",
                "type", "WORKS_AT"));
        assertFalse(dangling.path("success").asBoolean());
        assertEquals(revisionAfterFirst, dangling.path("currentRevision").asText());

        JsonNode secondEntity = execute(firstTool, Map.of(
                "action", "upsert_entity",
                "expectedRevision", revisionAfterFirst,
                "entityId", "acme",
                "type", "ORG",
                "label", "Acme"));
        String revisionAfterSecond = secondEntity.path("revision").asText();
        JsonNode relation = execute(firstTool, Map.of(
                "action", "upsert_relation",
                "expectedRevision", revisionAfterSecond,
                "relationId", "works",
                "sourceId", "alpha",
                "targetId", "acme",
                "type", "WORKS_AT",
                "weight", 0.9));
        assertTrue(relation.path("success").asBoolean());
        String revisionAfterRelation = relation.path("revision").asText();
        assertNotEquals(initialRevision, revisionAfterRelation);

        JsonNode search = execute(firstTool, Map.of("action", "search", "query", "Alpha"));
        assertEquals("alpha", search.path("entities").get(0).path("id").asText());
        assertEquals(0, search.path("relations").size(),
                "relations without both emitted endpoints must be omitted");
        JsonNode isolatedRead = execute(secondTool, Map.of("action", "read"));
        assertEquals(0, isolatedRead.path("entityCount").asInt());

        JsonNode stale = execute(firstTool, Map.of(
                "action", "retract_relation",
                "expectedRevision", initialRevision,
                "relationId", "works"));
        assertFalse(stale.path("success").asBoolean());
        assertEquals("stale_revision", stale.path("operation").asText());
        assertEquals(revisionAfterRelation, stale.path("currentRevision").asText());

        JsonNode retractedRelation = execute(firstTool, Map.of(
                "action", "retract_relation",
                "expectedRevision", revisionAfterRelation,
                "relationId", "works"));
        String revisionAfterRetract = retractedRelation.path("revision").asText();
        JsonNode retractedEntity = execute(firstTool, Map.of(
                "action", "retract_entity",
                "expectedRevision", revisionAfterRetract,
                "entityId", "alpha"));
        assertTrue(retractedEntity.path("success").asBoolean());
        JsonNode finalRead = execute(firstTool, Map.of("action", "read"));
        assertEquals(1, finalRead.path("entityCount").asInt());
        assertEquals(0, finalRead.path("relationCount").asInt());
    }

    @Test
    void semanticUpsertsInvalidateStaleEntityAndRelationAnalysis() throws Exception {
        AgentInstanceStore store = new AgentInstanceStore(tempDir.resolve("agents"));
        UUID owner = UUID.randomUUID();
        AgentInstance instance = store.provision(owner, "Analysis invalidation");
        ToolDefinition tool = resolver(store, owner).resolve(
                instance.principal().agentId().toString(), "alpha").orElseThrow()
                .scopedTools().get(0);

        String revision = execute(tool, Map.of("action", "read")).path("revision").asText();
        revision = execute(tool, Map.of(
                "action", "upsert_entity", "expectedRevision", revision,
                "entityId", "alpha", "type", "PERSON", "label", "Alpha"))
                .path("revision").asText();
        revision = execute(tool, Map.of(
                "action", "upsert_entity", "expectedRevision", revision,
                "entityId", "acme", "type", "ORG", "label", "Acme"))
                .path("revision").asText();
        revision = execute(tool, Map.of(
                "action", "upsert_relation", "expectedRevision", revision,
                "relationId", "works", "sourceId", "alpha", "targetId", "acme",
                "type", "WORKS_AT"))
                .path("revision").asText();

        var session = store.open(new AgentPrincipal(owner, instance.principal().agentId()));
        var enrichedRevision = session.mutate(new ai.kompile.agent.graph.AgentGraphRevision(revision), graph -> {
            graph.putEntityOpinion("alpha", Opinion.fromSoftTruth(0.9, 5));
            graph.putEntityVector("entity-analysis", "alpha", new double[] {1.0, 2.0});
            graph.putRelationOpinion("works", Opinion.fromSoftTruth(0.8, 5));
            graph.putRelationVector("relation-analysis", "works", new double[] {3.0, 4.0});
        });

        String afterEntity = execute(tool, Map.of(
                "action", "upsert_entity", "expectedRevision", enrichedRevision.sha256(),
                "entityId", "alpha", "type", "PERSON", "label", "Alpha Updated"))
                .path("revision").asText();
        String afterRelation = execute(tool, Map.of(
                "action", "upsert_relation", "expectedRevision", afterEntity,
                "relationId", "works", "sourceId", "alpha", "targetId", "acme",
                "type", "OWNS"))
                .path("revision").asText();

        var graph = session.read().graph();
        assertEquals(afterRelation, session.currentRevision().sha256());
        assertNull(graph.entityOpinion("alpha"));
        assertNull(graph.relationOpinion("works"));
        assertFalse(graph.vectorLayer("entity-analysis").contains("alpha"));
        assertFalse(graph.vectorLayer("relation-analysis").contains("works"));
    }

    @Test
    void readResultsEnforceAggregateCharacterBudget() throws Exception {
        AgentInstanceStore store = new AgentInstanceStore(tempDir.resolve("agents"));
        UUID owner = UUID.randomUUID();
        AgentInstance instance = store.provision(owner, "Bounded output");
        var session = store.open(instance.principal());
        session.mutate(session.currentRevision(), graph -> {
            for (int index = 0; index < 20; index++) {
                Map<String, Object> attributes = new LinkedHashMap<>();
                for (int attribute = 0; attribute < 16; attribute++) {
                    attributes.put("attribute" + attribute, "x".repeat(512));
                }
                graph.addEntity(new SimpleGraphEntity(
                        "entity-" + index, "THING", "Entity " + index,
                        1.0, 1.0, Set.of(), null, Instant.EPOCH, attributes));
            }
        });
        ToolDefinition tool = resolver(store, owner).resolve(
                instance.principal().agentId().toString(), "entity").orElseThrow()
                .scopedTools().get(0);

        String output = tool.execute(Map.of("action", "read", "limit", 20));
        JsonNode result = mapper.readTree(output);

        assertTrue(output.length() <= AgentPrivateGraphToolFactory.MAX_RESULT_CHARACTERS);
        assertTrue(result.path("truncated").asBoolean());
    }

    @Test
    void suppliesRestartDurableConversationAdapterIsolatedByAgentAndOwner() throws Exception {
        Path root = tempDir.resolve("agents-conversations");
        UUID owner = UUID.randomUUID();
        AgentInstanceStore store = new AgentInstanceStore(root);
        AgentInstance first = store.provision(owner, "First");
        AgentInstance second = store.provision(owner, "Second");
        AgentInstance foreign = store.provision(UUID.randomUUID(), "Foreign");
        String externalKey = "web:shared-key";
        var firstConversation = resolver(store, owner).resolveConversation(
                first.principal().agentId().toString(), externalKey).orElseThrow();

        firstConversation.appendAll(List.of(
                new ConversationEntry(ConversationKind.USER, "hello"),
                new ConversationEntry(ConversationKind.ASSISTANT, "hi")));

        var restartedResolver = resolver(new AgentInstanceStore(root), owner);
        var restartedHistory = restartedResolver.resolveConversation(
                        first.principal().agentId().toString(), externalKey)
                .orElseThrow()
                .loadTail(10, 4096);
        assertEquals(List.of(ConversationKind.USER, ConversationKind.ASSISTANT),
                restartedHistory.entries().stream().map(ConversationEntry::kind).toList());
        assertEquals(List.of("hello", "hi"),
                restartedHistory.entries().stream().map(ConversationEntry::content).toList());
        assertEquals(0, restartedResolver.resolveConversation(
                        second.principal().agentId().toString(), externalKey)
                .orElseThrow()
                .loadTail(10, 4096)
                .totalEvents());
        assertTrue(restartedResolver.resolveConversation(
                foreign.principal().agentId().toString(), externalKey).isEmpty());
        assertTrue(restartedResolver.resolveConversation(
                UUID.randomUUID().toString(), externalKey).isEmpty());
    }

    @Test
    void kclawTwoTurnRestartLoadsCanonicalHistoryWithoutReturningToLegacyJsonl()
            throws Exception {
        Path root = tempDir.resolve("agents-kclaw-restart");
        UUID owner = UUID.randomUUID();
        AgentInstanceStore firstStore = new AgentInstanceStore(root);
        AgentInstance instance = firstStore.provision(owner, "Durable KClaw");
        String agentId = instance.principal().agentId().toString();
        String externalKey = "telegram:restart";
        AgentRegistry registry = mock(AgentRegistry.class);
        AgentDefinition template = AgentDefinition.builder()
                .name("jarvis").systemPrompt("policy").maxSteps(4).build();
        when(registry.getAgent("jarvis")).thenReturn(Optional.of(template));
        SessionService legacy = mock(SessionService.class);

        ReActAgentService firstReact = mock(ReActAgentService.class);
        when(firstReact.run(any(AgentContext.class))).thenReturn(CompletableFuture.completedFuture(
                ReActResult.success("remembered", List.of(), 1, TokenUsage.empty(),
                        Instant.now(), Instant.now())));
        KClawAgentService firstService = new KClawAgentService(
                firstReact,
                registry,
                legacy,
                mock(ToolkitRegistry.class),
                resolver(firstStore, owner));

        var firstResponse = firstService.execute(KClawRequest.builder()
                .agentId(agentId)
                .sessionKey(externalKey)
                .message("remember me")
                .build());

        ReActAgentService restartedReact = mock(ReActAgentService.class);
        when(restartedReact.run(any(AgentContext.class))).thenReturn(
                CompletableFuture.completedFuture(ReActResult.success(
                        "still remembered", List.of(), 1, TokenUsage.empty(),
                        Instant.now(), Instant.now())));
        KClawAgentService restartedService = new KClawAgentService(
                restartedReact,
                registry,
                legacy,
                mock(ToolkitRegistry.class),
                resolver(new AgentInstanceStore(root), owner));
        var restartedResponse = restartedService.execute(KClawRequest.builder()
                .agentId(agentId)
                .sessionKey(externalKey)
                .message("what did I say?")
                .build());

        assertTrue(firstResponse.isSuccess());
        assertTrue(restartedResponse.isSuccess());
        assertEquals(externalKey, restartedResponse.getSessionKey());
        ArgumentCaptor<AgentContext> context = ArgumentCaptor.forClass(AgentContext.class);
        verify(restartedReact).run(context.capture());
        assertEquals(List.of("remember me", "remembered", "what did I say?"),
                context.getValue().getMessages().stream()
                        .map(message -> message.getContent())
                        .toList());
        verify(legacy, times(1)).loadSession(any());
    }

    private AdminKClawExecutionScopeResolver resolver(AgentInstanceStore store, UUID owner) {
        return new AdminKClawExecutionScopeResolver(
                store,
                new LocalOwnerIdentity(owner),
                new AgentPrivateGraphContextAssembler(),
                new AgentPrivateGraphToolFactory(mapper),
                "jarvis");
    }

    private JsonNode execute(ToolDefinition tool, Map<String, Object> arguments) throws Exception {
        return mapper.readTree(tool.execute(arguments));
    }
}
