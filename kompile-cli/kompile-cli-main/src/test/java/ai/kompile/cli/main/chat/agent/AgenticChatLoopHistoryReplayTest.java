/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.context.ConversationLedger;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.CompactionService;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * History re-projection (provider switch, resume, compaction, post-cancel)
 * must replay executed tool exchanges as protocol-correct envelopes and must
 * close interrupted tool calls with synthetic results so the next provider
 * request is valid. Prose-form replay previously made models imitate the
 * "[Tool call ...]" text shape instead of issuing real tool calls.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class AgenticChatLoopHistoryReplayTest {

    @TempDir
    Path workingDirectory;
    private String previousUserHome;

    @BeforeEach
    void isolateKompileHome() {
        previousUserHome = System.getProperty("user.home");
        System.setProperty("user.home", workingDirectory.toString());
    }

    @AfterEach
    void restoreUserHome() {
        if (previousUserHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", previousUserHome);
    }

    @Test
    void replayedToolExchangesUseEnvelopesAndCloseDanglingCalls() throws Exception {
        ObjectMapper objectMapper = JsonUtils.standardMapper();
        RecordingClient client = new RecordingClient(objectMapper);
        AgenticChatLoop loop = new AgenticChatLoop(
                null, objectMapper, new ToolRegistry(objectMapper),
                new PermissionService(), new AgentRegistry(),
                workingDirectory, client, null);
        loop.configureConversationSession("replay-" + UUID.randomUUID());
        ConversationLedger ledger = ledgerOf(loop);

        ledger.append(CompactionService.ConversationEntry.user("do the work"));
        ledger.append(CompactionService.ConversationEntry.toolCall(
                "bash", "call_a", "{\"command\":\"ls\"}"));
        ledger.append(CompactionService.ConversationEntry.toolResult(
                "bash", "call_a", "done"));
        ledger.append(CompactionService.ConversationEntry.toolCall(
                "read", "call_b", "{\"file_path\":\"x.txt\"}")); // cancelled: never executed

        int replayed = invokeRebuild(loop);

        assertEquals(5, replayed, "4 entries + 1 synthetic result for the dangling call");
        assertEquals(2, client.addedToolCalls.size());
        assertEquals(2, client.addedToolResults.size());

        assertEquals("call_a", client.addedToolCalls.get(0).path("id").asText());
        assertEquals("bash", client.addedToolCalls.get(0).path("name").asText());
        assertEquals("{\"command\":\"ls\"}",
                client.addedToolCalls.get(0).path("function").asText());
        assertEquals("call_a", client.addedToolResults.get(0).path("tool_call_id").asText());
        assertEquals("done", client.addedToolResults.get(0).path("content").asText());

        // The dangling call is the LAST call envelope and its synthetic result follows.
        assertEquals("call_b",
                client.addedToolCalls.get(client.addedToolCalls.size() - 1).path("id").asText());
        String closing = client.addedToolResults.get(client.addedToolResults.size() - 1)
                .path("content").asText();
        assertTrue(closing.contains("cancelled"),
                "dangling tool calls must be closed with a cancellation result");
    }

    @Test
    void parallelToolCallsAndResultsReplayAsSingleProviderTurns() throws Exception {
        ObjectMapper objectMapper = JsonUtils.standardMapper();
        RecordingClient client = new RecordingClient(objectMapper);
        AgenticChatLoop loop = new AgenticChatLoop(
                null, objectMapper, new ToolRegistry(objectMapper),
                new PermissionService(), new AgentRegistry(),
                workingDirectory, client, null);
        loop.configureConversationSession("replay-parallel-" + UUID.randomUUID());
        ConversationLedger ledger = ledgerOf(loop);
        ledger.append(CompactionService.ConversationEntry.user("run both"));
        ledger.append(CompactionService.ConversationEntry.toolCall(
                "read", "call_a", "{\"file_path\":\"a\"}"));
        ledger.append(CompactionService.ConversationEntry.toolCall(
                "read", "call_b", "{\"file_path\":\"b\"}"));
        ledger.append(CompactionService.ConversationEntry.toolResult(
                "read", "call_a", "a-result"));
        ledger.append(CompactionService.ConversationEntry.toolResult(
                "read", "call_b", "b-result"));

        invokeRebuild(loop, "claude-3-7-sonnet");

        assertEquals(List.of(2), client.toolCallBatchSizes);
        assertEquals(List.of(2), client.toolResultBatchSizes);
        assertTrue(client.replayModels.stream().allMatch("claude-3-7-sonnet"::equals),
                "the active turn's model override must select the replay protocol");
    }

    @Test
    void emptySuccessfulToolResultIsNotReplacedWithSyntheticCancellation() throws Exception {
        ObjectMapper objectMapper = JsonUtils.standardMapper();
        RecordingClient client = new RecordingClient(objectMapper);
        AgenticChatLoop loop = new AgenticChatLoop(
                null, objectMapper, new ToolRegistry(objectMapper),
                new PermissionService(), new AgentRegistry(),
                workingDirectory, client, null);
        loop.configureConversationSession("replay-empty-" + UUID.randomUUID());
        ConversationLedger ledger = ledgerOf(loop);
        ledger.append(CompactionService.ConversationEntry.user("run it"));
        ledger.append(CompactionService.ConversationEntry.toolCall(
                "read", "call_empty", "{}"));
        ledger.append(CompactionService.ConversationEntry.toolResult(
                "read", "call_empty", ""));

        invokeRebuild(loop);

        assertEquals(1, client.addedToolResults.size());
        assertEquals("", client.addedToolResults.get(0).path("content").asText());
    }

    private int invokeRebuild(AgenticChatLoop loop) throws Exception {
        Method method = AgenticChatLoop.class.getDeclaredMethod(
                "rebuildDirectHistory", List.class, boolean.class, boolean.class);
        method.setAccessible(true);
        return (int) method.invoke(loop, ledgerOf(loop).snapshot().activeEntries(),
                true, false);
    }

    private int invokeRebuild(AgenticChatLoop loop, String modelOverride) throws Exception {
        Method method = AgenticChatLoop.class.getDeclaredMethod(
                "rebuildDirectHistory", List.class, boolean.class, boolean.class,
                boolean.class, String.class);
        method.setAccessible(true);
        return (int) method.invoke(loop, ledgerOf(loop).snapshot().activeEntries(),
                true, false, true, modelOverride);
    }

    private ConversationLedger ledgerOf(AgenticChatLoop loop) throws Exception {
        java.lang.reflect.Field field = AgenticChatLoop.class.getDeclaredField("conversationLedger");
        field.setAccessible(true);
        return (ConversationLedger) field.get(loop);
    }

    /** Captures the envelopes the loop projects into the client's wire history. */
    private static final class RecordingClient extends ai.kompile.cli.main.chat.config.DirectLlmClient {
        private final ObjectMapper objectMapper;
        final List<com.fasterxml.jackson.databind.node.ObjectNode> addedToolCalls =
                new java.util.ArrayList<>();
        final List<com.fasterxml.jackson.databind.node.ObjectNode> addedToolResults =
                new java.util.ArrayList<>();
        final List<Integer> toolCallBatchSizes = new java.util.ArrayList<>();
        final List<Integer> toolResultBatchSizes = new java.util.ArrayList<>();
        final List<String> replayModels = new java.util.ArrayList<>();

        RecordingClient(ObjectMapper objectMapper) {
            super(new ai.kompile.cli.main.chat.config.ChatConfig(
                    "openai", "test-key", "fixture-model", "http://127.0.0.1:1/v1"),
                    objectMapper);
            this.objectMapper = objectMapper;
        }

        @Override
        public void addReplayedToolCalls(
                List<ai.kompile.cli.main.chat.config.DirectLlmClient.ReplayedToolCallInput> calls,
                String modelOverride) {
            toolCallBatchSizes.add(calls.size());
            replayModels.add(modelOverride);
            for (var replayed : calls) {
                com.fasterxml.jackson.databind.node.ObjectNode call =
                        objectMapper.createObjectNode();
                call.put("id", replayed.callId());
                call.put("function", replayed.argumentsJson());
                call.put("name", replayed.toolName());
                addedToolCalls.add(call);
            }
        }

        @Override
        public void addReplayedToolResults(
                List<ai.kompile.cli.main.chat.config.DirectLlmClient.ToolCallResultInput> results,
                String modelOverride) {
            toolResultBatchSizes.add(results.size());
            replayModels.add(modelOverride);
            for (var replayed : results) {
                com.fasterxml.jackson.databind.node.ObjectNode result =
                        objectMapper.createObjectNode();
                result.put("tool_call_id", replayed.callId);
                result.put("content", replayed.output);
                addedToolResults.add(result);
            }
        }
    }

    private static final class UnusedTypes {
        private UnusedTypes() { }
        @SuppressWarnings("unused")
        private static void refs() {
            ConversationLedger ledger = null;
            ArrayNode node = null;
        }
    }
}
