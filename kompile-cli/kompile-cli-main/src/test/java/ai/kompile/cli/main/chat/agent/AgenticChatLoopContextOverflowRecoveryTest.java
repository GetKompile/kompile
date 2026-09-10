/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatCompleter;
import ai.kompile.cli.main.chat.ReminderManager;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.context.ConversationLedger;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.CompactionService;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class AgenticChatLoopContextOverflowRecoveryTest {

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
        ChatCompleter.setTranscriptBlockOutput(null);
        if (previousUserHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", previousUserHome);
    }

    @Test
    void replaySafeOverflowCompactsAndRetriesTheExactLogicalRequestOnce() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ScriptedClient client = new ScriptedClient(mapper, Scenario.FIRST_OVERFLOW);
        AgenticChatLoop loop = newLoop(mapper, client, new ToolRegistry(mapper));
        String session = "overflow-user-" + UUID.randomUUID();
        seedOldHistory(loop, session);
        client.replayedMessages.clear();
        List<String> notices = captureNotices(loop);

        loop.setReminderManager(ReminderManager.inMemory(
                List.of("keep this reminder on the retry"), List.of()));
        DirectLlmClient.AttachmentInput attachment =
                new DirectLlmClient.AttachmentInput("evidence.txt", "text/plain");
        loop.setPendingAttachments(List.of(attachment));

        String output = loop.chat(
                "continue the implementation", session, "coder", "default", false);

        assertEquals("recovered", output);
        assertEquals(2, client.chatCalls);
        assertEquals(1, client.summaryCalls);
        assertEquals(client.prompts.get(0), client.prompts.get(1),
                "reminders must be decorated once and reused verbatim");
        assertTrue(client.prompts.get(0).contains("keep this reminder on the retry"));
        assertEquals(List.of(attachment), client.attachments.get(0));
        assertEquals(List.of(attachment), client.attachments.get(1),
                "one-shot attachments must survive the transparent retry");
        assertFalse(client.replayedMessages.stream().anyMatch(
                        message -> message.endsWith("continue the implementation")),
                "the pending user message must not be replayed and then sent twice");

        long activeUsers = ledgerOf(loop).snapshot().activeEntries().stream()
                .filter(entry -> entry.type == CompactionService.EntryType.USER)
                .filter(entry -> "continue the implementation".equals(entry.content))
                .count();
        assertEquals(1, activeUsers,
                "context recovery must not append a second durable user event");
        assertOneCompactionNotice(notices);
    }

    @Test
    void pendingToolResultIsResentOnceWithoutReexecutingTheTool() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        AtomicInteger executions = new AtomicInteger();
        ToolRegistry tools = new ToolRegistry(mapper);
        tools.register(countingTool(mapper, executions));
        ScriptedClient client = new ScriptedClient(mapper, Scenario.TOOL_FOLLOWUP_OVERFLOW);
        AgenticChatLoop loop = newLoop(mapper, client, tools);
        String session = "overflow-tool-" + UUID.randomUUID();
        seedOldHistory(loop, session);
        client.replayedMessages.clear();
        client.replayedToolCalls.clear();
        client.replayedToolResults.clear();

        String output = loop.chat("run the tool", session, "coder", "default", false);

        assertEquals("done", output);
        assertEquals(3, client.chatCalls);
        assertEquals(1, client.summaryCalls);
        assertEquals(1, executions.get(), "recovery must never execute the tool twice");
        assertEquals(1, client.toolResultsByRequest.get(1).size());
        assertEquals(1, client.toolResultsByRequest.get(2).size());
        assertEquals("call-1", client.toolResultsByRequest.get(1).get(0).callId);
        assertEquals("call-1", client.toolResultsByRequest.get(2).get(0).callId);
        assertTrue(client.replayedToolCalls.contains("call-1"),
                "the accepted assistant tool-call envelope must be restored");
        assertFalse(client.replayedToolResults.contains("call-1"),
                "the pending result must be supplied on the retry, not replayed too");
    }

    @Test
    void irreducibleFirstTurnDoesNotLoopOrRetryUnchanged() {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ScriptedClient client = new ScriptedClient(mapper, Scenario.ALWAYS_OVERFLOW);
        AgenticChatLoop loop = newLoop(mapper, client, new ToolRegistry(mapper));

        RuntimeException failure = assertThrows(RuntimeException.class, () -> loop.chat(
                "x".repeat(20_000), "overflow-irreducible-" + UUID.randomUUID(),
                "coder", "default", false));

        assertTrue(failure.getMessage().toLowerCase().contains("context"));
        assertEquals(1, client.chatCalls,
                "an irreducible request must be reported instead of resent unchanged");
        assertEquals(0, client.summaryCalls);
    }

    @Test
    void secondOverflowStopsAfterOneRetryAndRestoresCanonicalHistory() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ScriptedClient client = new ScriptedClient(mapper, Scenario.ALWAYS_OVERFLOW);
        AgenticChatLoop loop = newLoop(mapper, client, new ToolRegistry(mapper));
        String session = "overflow-twice-" + UUID.randomUUID();
        seedOldHistory(loop, session);
        client.replayedMessages.clear();

        assertThrows(RuntimeException.class, () -> loop.chat(
                "retry me once", session, "coder", "default", false));

        assertEquals(2, client.chatCalls);
        assertEquals(1, client.summaryCalls);
        assertEquals(1, client.replayedMessages.stream()
                        .filter(message -> message.endsWith("retry me once")).count(),
                "a failed retry must restore the canonical pending user turn");
    }

    @Test
    void noProgressSummaryAndDigestDoNotCommitOrRetry() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ScriptedClient client = new ScriptedClient(mapper, Scenario.FIRST_OVERFLOW);
        AgenticChatLoop loop = newLoop(mapper, client, new ToolRegistry(mapper));
        String session = "overflow-no-progress-" + UUID.randomUUID();
        loop.configureConversationSession(session);
        ConversationLedger ledger = ledgerOf(loop);
        ledger.append(CompactionService.ConversationEntry.user("a"));
        ledger.append(CompactionService.ConversationEntry.assistant("b"));
        loop.rebuildDirectHistoryForProviderSwitch();

        assertThrows(RuntimeException.class, () -> loop.chat(
                "x".repeat(20_000), session, "coder", "default", false));

        assertEquals(1, client.chatCalls);
        assertEquals(1, client.summaryCalls);
        assertTrue(ledger.snapshot().checkpoint() == null,
                "a non-reducing candidate must leave the ledger checkpoint unchanged");
    }

    @Test
    void rejectedAppliedNativeCompactionRestoresCanonicalProviderHistory() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ScriptedClient client = new ScriptedClient(mapper, Scenario.FIRST_OVERFLOW);
        client.nativeCompaction = new DirectLlmClient.NativeCompactionResult(
                true, true, "oversized native summary " + "z".repeat(2_000),
                null, null);
        AgenticChatLoop loop = newLoop(mapper, client, new ToolRegistry(mapper));
        String session = "native-no-progress-" + UUID.randomUUID();
        loop.configureConversationSession(session);
        ConversationLedger ledger = ledgerOf(loop);
        ledger.append(CompactionService.ConversationEntry.user("a"));
        ledger.append(CompactionService.ConversationEntry.assistant("b"));
        client.replayedMessages.clear();

        AgenticChatLoop.ForceCompactResult result = loop.forceCompact(null);

        assertEquals(AgenticChatLoop.ForceCompactResult.Status.FAILED, result.getStatus());
        assertTrue(client.replayedMessages.contains("user:a"));
        assertTrue(client.replayedMessages.contains("assistant:b"));
        assertTrue(ledger.snapshot().checkpoint() == null);
    }

    @Test
    void disablingAutoCompactionClearsProviderNativeTrigger() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ChatConfig config = new ChatConfig("openai", null, "gpt-4o", "https://api.openai.com/v1");
        config.setAutoCompactEnabled(false);
        DirectLlmClient client = new DirectLlmClient(config, mapper);
        AgenticChatLoop loop = newLoop(mapper, client, new ToolRegistry(mapper));
        Field trigger = DirectLlmClient.class.getDeclaredField("nativeCompactionTriggerTokens");
        trigger.setAccessible(true);

        loop.refreshCompactionPolicy();
        assertEquals(0, trigger.getInt(client));
        config.setAutoCompactEnabled(true);
        loop.refreshCompactionPolicy();
        assertEquals(0, trigger.getInt(client), "Empty history must not enable native compaction");
        loop.configureConversationSession("native-policy-" + UUID.randomUUID());
        ConversationLedger ledger = ledgerOf(loop);
        ledger.append(CompactionService.ConversationEntry.assistant("Small checkpoint summary"));
        loop.refreshCompactionPolicy();
        assertEquals(0, trigger.getInt(client), "A tiny summary must not enable native compaction");
        for (int i = 0; i < 3; i++) {
            ledger.append(CompactionService.ConversationEntry.user("u".repeat(80_000)));
            ledger.append(CompactionService.ConversationEntry.assistant("a".repeat(80_000)));
        }
        loop.refreshCompactionPolicy();
        assertTrue(trigger.getInt(client) > 0);
        config.setAutoCompactEnabled(false);
        loop.refreshCompactionPolicy();
        assertEquals(0, trigger.getInt(client));
    }

    @Test
    void tinyHistorySkipsFullRequestCompactionProbe() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ScriptedClient client = new ScriptedClient(mapper, Scenario.SUCCESS);
        client.exactInputTokens = 200_000L;
        AgenticChatLoop loop = newLoop(mapper, client, new ToolRegistry(mapper));
        String session = "tiny-history-" + UUID.randomUUID();
        loop.configureConversationSession(session);
        ConversationLedger ledger = ledgerOf(loop);
        ledger.append(CompactionService.ConversationEntry.user("old request"));
        ledger.append(CompactionService.ConversationEntry.assistant("old response"));
        ConversationLedger.Snapshot beforeCheckpoint = ledger.snapshot();
        assertTrue(ledger.commitCompaction(
                beforeCheckpoint.version(),
                beforeCheckpoint.coveredThroughForPrefix(beforeCheckpoint.activeEntries().size()),
                "s".repeat(1_024), "test", "custom", "overflow-test", 512, 256));
        loop.rebuildDirectHistoryForProviderSwitch();

        assertEquals("done", loop.chat("continue", session, "coder", "default", false));
        assertEquals(0, client.tokenCountCalls,
                "irreducible request overhead must not trigger a tiny-history compaction probe");
        assertEquals(List.of(0), client.nativeTriggersByRequest,
                "a tiny checkpoint must not arm provider-native compaction");
        assertEquals(0, client.summaryCalls);
        assertTrue(ledger.snapshot().checkpoint() != null);
    }

    @Test
    void nativeCheckpointClearsPreCompactionUsageAndTrigger() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ScriptedClient client = new ScriptedClient(mapper, Scenario.NATIVE_COMPACTION);
        AgenticChatLoop loop = newLoop(mapper, client, new ToolRegistry(mapper));
        String session = "native-usage-" + UUID.randomUUID();
        seedNativeThresholdHistory(loop, session);
        loop.refreshCompactionPolicy();
        Field trigger = DirectLlmClient.class.getDeclaredField("nativeCompactionTriggerTokens");
        trigger.setAccessible(true);
        assertTrue(trigger.getInt(client) > 0,
                "the test must begin with native compaction enabled for the large history");

        assertEquals("done", loop.chat("continue", session, "coder", "default", false));
        assertTrue(ledgerOf(loop).snapshot().checkpoint() != null);
        for (String name : List.of("lastReportedInputTokens", "lastReportedHistoryTokens")) {
            Field field = AgenticChatLoop.class.getDeclaredField(name);
            field.setAccessible(true);
            assertEquals(0, ((Number) field.get(loop)).intValue(), name);
        }
        assertTrue(client.nativeTriggersByRequest.get(0) > 0,
                "the first provider request must carry the native compaction policy");
        assertEquals("done", loop.chat("thanks", session, "coder", "default", false));
        assertEquals(List.of(client.nativeTriggersByRequest.get(0), 0),
                client.nativeTriggersByRequest,
                "the next provider request must not inherit the pre-checkpoint trigger");
        assertEquals(0, client.summaryCalls,
                "A small turn after a native checkpoint must not recompact stale usage");
    }

    @Test
    void millionTokenBudgetKeepsTheNativeThresholdHighAcrossOrdinaryTurns() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ScriptedClient client = millionTokenClient(mapper);
        AgenticChatLoop loop = newLoop(mapper, client, new ToolRegistry(mapper));
        String session = "million-context-" + UUID.randomUUID();
        loop.configureConversationSession(session);
        ConversationLedger ledger = ledgerOf(loop);
        ledger.append(CompactionService.ConversationEntry.user("u".repeat(2_000)));
        ledger.append(CompactionService.ConversationEntry.assistant("a".repeat(2_000)));
        // Leave a 1K prefix outside the four recent exchanges retained by the 40K floor.
        for (int i = 0; i < 4; i++) {
            ledger.append(CompactionService.ConversationEntry.user("u".repeat(20_500)));
            ledger.append(CompactionService.ConversationEntry.assistant("a".repeat(20_500)));
        }
        loop.rebuildDirectHistoryForProviderSwitch();
        assertEquals(42_000, loop.estimateConversationTokens());

        assertEquals("done", loop.chat("continue", session, "coder", "default", false));
        assertEquals("done", loop.chat("continue again", session, "coder", "default", false));

        assertEquals(1_050_000, loop.contextWindowTokens());
        assertEquals(892_500, loop.compactionTriggerTokens());
        assertEquals(List.of(892_500, 892_500), client.nativeTriggersByRequest,
                "passing the 40K preservation span is not a 40K compaction trigger");
        assertEquals(0, client.summaryCalls);
        assertEquals(0, client.toolCompactionCalls);
        assertTrue(ledgerOf(loop).snapshot().checkpoint() == null);
    }

    @Test
    void exactLowerCountReplacesStaleUsageAndAnchorsMidLoopGrowth() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ScriptedClient client = millionTokenClient(mapper);
        client.exactInputTokens = 100_000;
        AgenticChatLoop loop = newLoop(mapper, client, new ToolRegistry(mapper));
        String session = "exact-lower-" + UUID.randomUUID();
        seedHistoryOfTokens(loop, session, 90_000);
        setUsageAnchor(loop, 900_000, 90_000);
        loop.setReminderManager(ReminderManager.inMemory(List.of("preserve this reminder"), List.of()));

        assertEquals("done", loop.chat("continue".repeat(25_000), session, "coder", "default", false));

        assertEquals(1, client.tokenCountCalls);
        assertEquals(client.prompts, client.countedPrompts,
                "the exact count must use the same reminder-decorated request");
        assertTrue(client.countedPrompts.get(0).contains("preserve this reminder"));
        assertEquals(0, client.summaryCalls, "old 900K usage cannot override the new exact 100K count");
        assertEquals(0, client.toolCompactionCalls, "mid-loop pruning must not reuse the stale anchor");
        assertEquals(100_000, loop.lastReportedInputTokens());
        var projection = AgenticChatLoop.class.getDeclaredMethod("projectedInputTokens", String.class);
        projection.setAccessible(true);
        assertEquals(100_001L, ((Number) projection.invoke(loop, (Object) null)).longValue(),
                "only the new assistant reply adds growth, not the already-counted pending user turn");
        assertTrue(ledgerOf(loop).snapshot().checkpoint() == null);
    }

    @Test
    void exactHigherCountTriggersCompactionOnceAndResetsItsAnchor() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ScriptedClient client = millionTokenClient(mapper);
        client.exactInputTokens = 900_000;
        AgenticChatLoop loop = newLoop(mapper, client, new ToolRegistry(mapper));
        String session = "exact-higher-" + UUID.randomUUID();
        seedHistoryOfTokens(loop, session, 90_000);
        setUsageAnchor(loop, 100_000, 90_000);
        List<String> notices = captureNotices(loop);

        assertEquals("done", loop.chat("continue", session, "coder", "default", false));

        assertEquals(1, client.summaryCalls);
        assertEquals(0, client.toolCompactionCalls);
        assertEquals(0, loop.lastReportedInputTokens(), "pre-checkpoint counts must be invalidated");
        assertTrue(ledgerOf(loop).snapshot().checkpoint() != null);
        assertOneCompactionNotice(notices);
    }

    @Test
    void unsupportedCountKeepsTheUsagePlusGrowthFallback() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ScriptedClient client = millionTokenClient(mapper);
        AgenticChatLoop loop = newLoop(mapper, client, new ToolRegistry(mapper));
        String session = "count-fallback-" + UUID.randomUUID();
        seedHistoryOfTokens(loop, session, 90_000);
        setUsageAnchor(loop, 900_000, 90_000);

        assertEquals("done", loop.chat("continue", session, "coder", "default", false));

        assertEquals(1, client.tokenCountCalls);
        assertEquals(1, client.summaryCalls);
        assertTrue(ledgerOf(loop).snapshot().checkpoint() != null);
    }

    @Test
    void textOnlyCountCannotReplaceUsageForAPendingAttachmentRequest() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ScriptedClient client = millionTokenClient(mapper);
        client.exactInputTokens = 100_000;
        AgenticChatLoop loop = newLoop(mapper, client, new ToolRegistry(mapper));
        String session = "attachment-count-" + UUID.randomUUID();
        seedHistoryOfTokens(loop, session, 90_000);
        setUsageAnchor(loop, 900_000, 90_000);
        DirectLlmClient.AttachmentInput attachment =
                new DirectLlmClient.AttachmentInput("evidence.pdf", "application/pdf");
        loop.setPendingAttachments(List.of(attachment));

        assertEquals("done", loop.chat("continue", session, "coder", "default", false));

        assertEquals(0, client.tokenCountCalls, "the counting API does not receive attachments");
        assertEquals(1, client.summaryCalls, "retain the usage-based fallback rather than undercounting");
        assertEquals(List.of(attachment), client.attachments.get(0));
    }

    @Test
    void manualSingleExchangeCompactionCommitsTheEntireSummarizedRange() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ScriptedClient client = millionTokenClient(mapper);
        AgenticChatLoop loop = newLoop(mapper, client, new ToolRegistry(mapper));
        String session = "compact-single-" + UUID.randomUUID();
        loop.configureConversationSession(session);
        ConversationLedger ledger = ledgerOf(loop);
        ledger.append(CompactionService.ConversationEntry.user("u".repeat(84_000)));
        ledger.append(CompactionService.ConversationEntry.assistant("a".repeat(84_000)));
        loop.rebuildDirectHistoryForProviderSwitch();

        AgenticChatLoop.ForceCompactResult result = loop.forceCompact(null);

        assertTrue(result.isSuccess(), result.getMessage());
        assertEquals(42_000, result.getTokensBefore());
        assertEquals(0, result.getPreservedTurns());
        assertEquals(2, ledger.snapshot().checkpoint().coveredThroughSequence());
        assertTrue(ledger.snapshot().activeEvents().isEmpty(), "covered events must leave active history");
        assertEquals(2, ledger.snapshot().allEvents().size(), "raw audit events must survive");
        assertEquals(result.getTokensAfter(), loop.estimateConversationTokens(),
                "reported after-count must match the committed projection, not an uncommitted candidate");

        // Resume must use the same compacted projection, not resurrect the 42K exchange.
        ConversationLedger restored = new ConversationLedger(mapper);
        restored.configureSession(session);
        // Jackson restores a nullable JsonNode payload as NullNode; compare persisted data.
        assertEquals(mapper.valueToTree(ledger.snapshot().checkpoint()),
                mapper.valueToTree(restored.snapshot().checkpoint()));
        assertTrue(restored.snapshot().activeEvents().isEmpty());
        assertEquals(1, restored.snapshot().activeEntries().size());

        // Cover a provider-only tail after an existing checkpoint (summary offsets matter).
        ledger.append(CompactionService.ConversationEntry.assistant("b".repeat(84_000)));
        AgenticChatLoop.ForceCompactResult second = loop.forceCompact(null);
        assertTrue(second.isSuccess(), second.getMessage());
        assertEquals(3, ledger.snapshot().checkpoint().coveredThroughSequence());
        assertTrue(ledger.snapshot().activeEvents().isEmpty());
        assertEquals(3, ledger.snapshot().allEvents().size());
        assertEquals(second.getTokensAfter(), loop.estimateConversationTokens());
    }

    private ScriptedClient millionTokenClient(ObjectMapper mapper) {
        ScriptedClient client = new ScriptedClient(mapper, Scenario.SUCCESS);
        client.getChatConfig().setContextWindowTokens(1_050_000);
        client.getChatConfig().setMaxOutputTokens(128_000);
        return client;
    }

    private static void setUsageAnchor(AgenticChatLoop loop, long input, long history) throws Exception {
        Field usage = AgenticChatLoop.class.getDeclaredField("lastReportedInputTokens");
        usage.setAccessible(true);
        usage.setLong(loop, input);
        Field baseline = AgenticChatLoop.class.getDeclaredField("lastReportedHistoryTokens");
        baseline.setAccessible(true);
        baseline.setLong(loop, history);
    }

    private static List<String> captureNotices(AgenticChatLoop loop) {
        List<String> notices = new ArrayList<>();
        ChatCompleter.setTranscriptBlockOutput((key, content) -> false);
        loop.backgroundActiveTurn(notices::add);
        return notices;
    }

    private static void assertOneCompactionNotice(List<String> notices) {
        assertEquals(1, notices.stream().filter(
                line -> line.toLowerCase(java.util.Locale.ROOT).contains("context compacted")).count(),
                "compaction progress completion must not be followed by a duplicate divider: " + notices);
        assertTrue(notices.stream().anyMatch(line -> line.contains("portable history estimate")));
    }

    private AgenticChatLoop newLoop(
            ObjectMapper mapper, DirectLlmClient client, ToolRegistry tools) {
        return new AgenticChatLoop(
                null, mapper, tools, new PermissionService(), new AgentRegistry(),
                workingDirectory, client, null);
    }

    private void seedOldHistory(AgenticChatLoop loop, String session) throws Exception {
        loop.configureConversationSession(session);
        ConversationLedger ledger = ledgerOf(loop);
        for (int i = 0; i < 3; i++) {
            ledger.append(CompactionService.ConversationEntry.user(
                    "old request " + i + " " + "u".repeat(4_000)));
            ledger.append(CompactionService.ConversationEntry.assistant(
                    "old response " + i + " " + "a".repeat(4_000)));
        }
        loop.rebuildDirectHistoryForProviderSwitch();
    }

    private void seedNativeThresholdHistory(
            AgenticChatLoop loop, String session) throws Exception {
        seedHistoryOfTokens(loop, session, 60_000);
    }

    private void seedHistoryOfTokens(
            AgenticChatLoop loop, String session, int tokens) throws Exception {
        loop.configureConversationSession(session);
        ConversationLedger ledger = ledgerOf(loop);
        for (int i = 0; i < 4; i++) {
            ledger.append(CompactionService.ConversationEntry.user("u".repeat(tokens / 2)));
            ledger.append(CompactionService.ConversationEntry.assistant("a".repeat(tokens / 2)));
        }
        loop.rebuildDirectHistoryForProviderSwitch();
    }

    private ConversationLedger ledgerOf(AgenticChatLoop loop) throws Exception {
        Field field = AgenticChatLoop.class.getDeclaredField("conversationLedger");
        field.setAccessible(true);
        return (ConversationLedger) field.get(loop);
    }

    private static CliTool countingTool(ObjectMapper mapper, AtomicInteger executions) {
        return new CliTool() {
            @Override public String id() { return "counting_tool"; }
            @Override public String description() { return "counts executions"; }
            @Override public JsonNode parameterSchema() {
                return mapper.createObjectNode().put("type", "object");
            }
            @Override public String permissionKey() { return "read"; }
            @Override public ToolResult execute(JsonNode params, ToolContext context) {
                executions.incrementAndGet();
                return ToolResult.success("tool output");
            }
        };
    }

    private enum Scenario {
        SUCCESS,
        NATIVE_COMPACTION,
        FIRST_OVERFLOW,
        TOOL_FOLLOWUP_OVERFLOW,
        ALWAYS_OVERFLOW
    }

    private static final class ScriptedClient extends DirectLlmClient {
        private final ObjectMapper mapper;
        private final Scenario scenario;
        private int chatCalls;
        private int summaryCalls;
        private int tokenCountCalls;
        private int toolCompactionCalls;
        private final List<String> countedPrompts = new ArrayList<>();
        private long exactInputTokens;
        private int configuredNativeTrigger;
        private final List<Integer> nativeTriggersByRequest = new ArrayList<>();
        private final List<String> prompts = new ArrayList<>();
        private final List<List<AttachmentInput>> attachments = new ArrayList<>();
        private final List<List<ToolCallResultInput>> toolResultsByRequest = new ArrayList<>();
        private final List<String> replayedMessages = new ArrayList<>();
        private final List<String> replayedToolCalls = new ArrayList<>();
        private final List<String> replayedToolResults = new ArrayList<>();
        private NativeCompactionResult nativeCompaction = NativeCompactionResult.unsupported();

        private ScriptedClient(ObjectMapper mapper, Scenario scenario) {
            super(new ChatConfig(
                    "custom", null, "overflow-test", "http://unused.invalid"), mapper);
            this.mapper = mapper;
            this.scenario = scenario;
        }

        @Override
        public void setNativeCompactionTriggerTokens(int tokens) {
            configuredNativeTrigger = Math.max(0, tokens);
            super.setNativeCompactionTriggerTokens(tokens);
        }

        @Override
        public StreamResult streamChat(
                String userMessage,
                String systemPrompt,
                ArrayNode toolDefs,
                List<ToolCallResultInput> toolResults,
                String modelOverride,
                List<AttachmentInput> requestAttachments) {
            chatCalls++;
            nativeTriggersByRequest.add(configuredNativeTrigger);
            prompts.add(userMessage);
            attachments.add(requestAttachments == null
                    ? List.of() : List.copyOf(requestAttachments));
            toolResultsByRequest.add(copyResults(toolResults));

            if (scenario == Scenario.TOOL_FOLLOWUP_OVERFLOW && chatCalls == 1) {
                StreamResult result = new StreamResult();
                ToolCallOutput call = new ToolCallOutput();
                call.id = "call-1";
                call.name = "counting_tool";
                call.arguments = mapper.createObjectNode();
                result.toolCalls.add(call);
                return result;
            }
            if (scenario == Scenario.ALWAYS_OVERFLOW
                    || scenario == Scenario.FIRST_OVERFLOW && chatCalls == 1
                    || scenario == Scenario.TOOL_FOLLOWUP_OVERFLOW && chatCalls == 2) {
                return overflow();
            }

            StreamResult result = new StreamResult();
            result.text = scenario == Scenario.FIRST_OVERFLOW ? "recovered" : "done";
            if (scenario == Scenario.NATIVE_COMPACTION && chatCalls == 1) {
                result.nativeCompactionSummary = "Earlier requests completed successfully.";
                result.nativeCompactionStrategy = "test";
                result.inputTokens = 120_000;
            }
            if (getOutputConsumer() != null) getOutputConsumer().accept(result.text);
            return result;
        }

        @Override
        public StreamResult streamOneShot(
                String prompt, String systemPrompt, String modelOverride) {
            summaryCalls++;
            StreamResult result = new StreamResult();
            result.text = "## Compacted context\nOlder exchanges were summarized safely.";
            result.inputTokens = 2_000;
            result.outputTokens = 20;
            return result;
        }

        @Override
        public TokenCountResult countInputTokens(
                String userMessage, String systemPrompt, ArrayNode toolDefs,
                List<ToolCallResultInput> toolResults, String modelOverride) {
            tokenCountCalls++;
            countedPrompts.add(userMessage);
            return exactInputTokens > 0L
                    ? new TokenCountResult(true, true, exactInputTokens, "test", null)
                    : TokenCountResult.unsupported();
        }

        @Override
        public int compactToolHistory(int preserveRecentMessages,
                                      java.util.function.BinaryOperator<String> summarizer) {
            toolCompactionCalls++;
            return super.compactToolHistory(preserveRecentMessages, summarizer);
        }

        @Override
        public NativeCompactionResult tryNativeCompact(String modelOverride) {
            return nativeCompaction;
        }

        @Override
        public void addToHistory(String role, String content) {
            replayedMessages.add(role + ":" + content);
            super.addToHistory(role, content);
        }

        @Override
        public void addReplayedToolCalls(
                List<ReplayedToolCallInput> calls, String modelOverride) {
            for (ReplayedToolCallInput call : calls) {
                replayedToolCalls.add(call.callId());
            }
            super.addReplayedToolCalls(calls, modelOverride);
        }

        @Override
        public void addReplayedToolResults(
                List<ToolCallResultInput> results, String modelOverride) {
            for (ToolCallResultInput result : results) {
                replayedToolResults.add(result.callId);
            }
            super.addReplayedToolResults(results, modelOverride);
        }

        private StreamResult overflow() {
            StreamResult result = new StreamResult();
            result.failed = true;
            result.failureKind = FailureKind.CONTEXT_OVERFLOW;
            result.failureStatusCode = 400;
            result.failureMessage = "[LLM API error 400: Context window exceeded]";
            result.text = result.failureMessage;
            return result;
        }

        private static List<ToolCallResultInput> copyResults(
                List<ToolCallResultInput> results) {
            if (results == null) return List.of();
            List<ToolCallResultInput> copy = new ArrayList<>();
            for (ToolCallResultInput result : results) {
                copy.add(new ToolCallResultInput(
                        result.callId, result.name, result.output, result.isError));
            }
            return copy;
        }
    }
}
