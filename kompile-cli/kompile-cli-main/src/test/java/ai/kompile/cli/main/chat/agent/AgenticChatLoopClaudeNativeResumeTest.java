package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatCompleter;
import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.FakeClaudeCode;
import ai.kompile.cli.main.chat.context.ConversationLedger;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Restarting Kompile Chat on the Claude subscription route continues the Claude
 * Code session that already holds the conversation: the first turn after the
 * restart uses {@code --resume} and restores no history. Drives the real ledger,
 * DirectLlmClient and AgenticChatLoop in two successive "processes" against a
 * fake {@code claude} that logs its argv and each message it takes in.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class AgenticChatLoopClaudeNativeResumeTest {
    private static final String SESSION = "claude-native-resume-test";

    @TempDir Path directory;
    private FakeClaudeCode fake;

    @Test
    void aRestartedChatResumesTheClaudeCodeSessionInsteadOfRestoringHistory() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("CLAUDE_CONFIG_DIR") == null,
                "integration fixture must not write into a real configured Claude account");
        String home = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectories(directory.resolve("home")).toString());
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        ChatCompleter.setContentOutput(lines::add);
        try {
            assertEquals("done", chatInNewProcess("first question").strip(), String.valueOf(lines));
            assertEquals("done", chatInNewProcess("second question").strip(), String.valueOf(lines));

            List<String> argv = fake.argv();
            assertEquals(2, argv.size(), String.valueOf(argv));
            assertEquals(2, fake.messages().size(), String.valueOf(fake.messages()));
            Matcher created = Pattern.compile("--session-id (\\S+)").matcher(argv.get(0));
            assertTrue(created.find(), argv.get(0));
            String nativeSession = created.group(1);
            assertTrue(argv.get(1).contains("--resume " + nativeSession),
                    "the restarted chat must continue the saved session\n" + argv);

            String resumed = prompt(2);
            assertTrue(prompt(1).contains("first question"), prompt(1));
            assertTrue(resumed.contains("second question"), resumed);
            assertFalse(resumed.contains("[Earlier conversation restored by Kompile"),
                    "the resumed session already holds the conversation\n" + resumed);
            assertFalse(resumed.contains("first question"), resumed);
            assertFalse(resumed.contains("[Updated Kompile Chat system instructions"),
                    "unchanged instructions are not delivered again\n" + resumed);

            ConversationLedger ledger = new ConversationLedger(JsonUtils.standardMapper());
            ledger.configureSession(SESSION);
            ConversationLedger.NativeSession saved = ledger.resumableNativeSession("claude-cli");
            assertNotNull(saved, "the next restart can resume the same session again");
            assertEquals(nativeSession, saved.sessionId());
            Path link = KompileHome.homeDirectory().toPath().resolve("conversations")
                    .resolve(SESSION).resolve("claude-code").resolve(nativeSession + ".jsonl");
            assertTrue(Files.isSymbolicLink(link), "the running transport exposes the native transcript");
            assertEquals(2, Files.readString(link).lines().count(), "resume appends through the same link");
            assertEquals(nativeSession, ChatHistory.resolveNativeSessionId(SESSION, "claude"));
        } finally {
            ChatCompleter.setContentOutput(null);
            ChatCompleter.setTranscriptBlockOutput(null);
            ChatCompleter.setActivity(null);
            if (home == null) System.clearProperty("user.home");
            else System.setProperty("user.home", home);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void acceptedUnfinishedTurnRemainsResumableAfterRestart(boolean cancelled) throws Exception {
        String home = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectories(directory.resolve("home")).toString());
        var signal = new java.util.concurrent.atomic.AtomicBoolean();
        ChatCompleter.setContentOutput(text -> {
            if (cancelled && text.contains("partial")) signal.set(true);
        });
        fake = new FakeClaudeCode(directory.resolve("claude"), """
                turn() {
                  say_init
                  if [ -n "$RESUMED" ]; then
                    say_text done
                    say_result
                  else
                    say_text partial
                    emit '{"type":"result","subtype":"error_during_execution","is_error":true,"num_turns":1,"errors":["temporary failure"]}'
                  fi
                }
                """);
        try {
            if (cancelled) {
                assertTrue(chatInNewProcess("first question", signal).contains("Interrupted by user"));
            } else {
                assertThrows(RuntimeException.class, () -> chatInNewProcess("first question", signal));
            }
            ConversationLedger ledger = new ConversationLedger(JsonUtils.standardMapper());
            ledger.configureSession(SESSION);
            ConversationLedger.NativeSession saved = ledger.resumableNativeSession("claude-cli");
            assertNotNull(saved, "accepted turns hold context even after cancellation or failure");
            assertTrue(ledger.snapshot().activeEntries().stream()
                    .anyMatch(entry -> entry.content != null && entry.content.startsWith("partial")),
                    "partial output stays durable");

            assertEquals("done", chatInNewProcess("second question").strip());
            assertTrue(fake.argv().get(1).contains("--resume " + saved.sessionId()), fake.argv().toString());
            assertFalse(prompt(2).contains("[Earlier conversation restored by Kompile"), prompt(2));
            assertFalse(prompt(2).contains("first question"), prompt(2));
        } finally {
            ChatCompleter.setContentOutput(null);
            ChatCompleter.setTranscriptBlockOutput(null);
            ChatCompleter.setActivity(null);
            if (home == null) System.clearProperty("user.home");
            else System.setProperty("user.home", home);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false,false", "true,false,false", "false,true,false", "true,true,false",
            "false,false,true", "true,false,true", "false,true,true", "true,true,true"})
    void restartedChatShowsMeasuredContextBeforeItsFirstPrompt(boolean metricsFirst, boolean compacted,
                                                              boolean configuredWindow) throws Exception {
        String home = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectories(directory.resolve("home")).toString());
        if (configuredWindow) {
            Path settings = Files.createDirectories(Path.of(System.getProperty("user.home")).resolve(".claude"));
            Files.writeString(settings.resolve("settings.json"), """
                    {"env":{"CLAUDE_CODE_AUTO_COMPACT_WINDOW":"256000","CLAUDE_CODE_DISABLE_1M_CONTEXT":"0"}}
                    """);
        }
        ChatCompleter.setContentOutput(ignored -> { });
        fake = new FakeClaudeCode(directory.resolve("claude"), """
                COMPACT_AFTER_FIRST=%s
                turn() {
                  say_init
                  if [ "$1" -gt 1 ]; then
                    if [ "$COMPACT_AFTER_FIRST" = 1 ]; then
                      emit '{"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"auto","pre_tokens":80000}}'
                    fi
                    say_text done
                    emit '{"type":"result","subtype":"success","num_turns":1,"usage":{}}'
                    return
                  fi
                  emit '{"type":"stream_event","event":{"type":"message_start","message":{"id":"msg-1","usage":{"input_tokens":100,"cache_read_input_tokens":9900}}}}'
                  emit '{"type":"stream_event","event":{"type":"message_start","message":{"id":"msg-2","usage":{"input_tokens":100,"cache_read_input_tokens":79000,"cache_creation_input_tokens":900}}}}'
                  say_text done
                  emit '{"type":"result","subtype":"success","num_turns":2,"usage":{"input_tokens":200,"output_tokens":10,"cache_read_input_tokens":88900,"cache_creation_input_tokens":900},"modelUsage":{"claude-opus-5-5":{"contextWindow":200000,"maxOutputTokens":32000}}}'
                }
                """.formatted(compacted ? "1" : "0").replace("\"contextWindow\":200000",
                        "\"contextWindow\":" + (configuredWindow ? "1000000" : "200000")));
        var mapper = JsonUtils.standardMapper();
        Path project = Files.createDirectories(directory.resolve("project"));
        String measured = configuredWindow ? "80,000/256,000 31% last input · compact window"
                : "80,000/200,000 40% last input";
        String unknown = configuredWindow ? "?/256,000 last input · compact window" : "?/200,000 last input";
        String expected = compacted ? unknown : measured;
        long expectedInput = compacted ? 0 : 80_000;
        try {
            try (var client = new DirectLlmClient(contextConfig(), mapper, project)) {
                var loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                        new PermissionService(), new AgentRegistry(), project, client, null);
                loop.configureConversationSession(SESSION);
                installFakeClaude(client, project);
                var metrics = new ai.kompile.cli.main.chat.ChatSessionMetrics(SESSION);
                loop.setSessionMetrics(metrics);
                assertEquals("done", loop.chat("first question", SESSION, "coder", "default", false));
                assertEquals(measured, metrics.contextTokenSummary(), "native model limits must not overwrite the configured window");
                assertTrue(metrics.getInputTokens() + metrics.getCacheReadTokens()
                        + metrics.getCacheCreationTokens() > 80_000, "billing totals are not context");
                assertEquals("done", loop.chat("turn without new usage", SESSION, "coder", "default", false));
                assertEquals(expected, metrics.contextTokenSummary());
            }
            try (var client = new DirectLlmClient(contextConfig(), mapper, project)) {
                var loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                        new PermissionService(), new AgentRegistry(), project, client, null);
                var metrics = new ai.kompile.cli.main.chat.ChatSessionMetrics(SESSION);
                if (metricsFirst) loop.setSessionMetrics(metrics);
                loop.configureConversationSession(SESSION);
                if (!metricsFirst) loop.setSessionMetrics(metrics);
                assertEquals(expected, metrics.contextTokenSummary(),
                        "restore the last measurement unless native compaction invalidated it");
                assertEquals(expectedInput, loop.lastReportedInputTokens());
                assertEquals(configuredWindow ? 256_000 : 200_000, loop.resolvedContextWindowTokens());
                assertEquals(configuredWindow ? 256_000 : 200_000, loop.contextWindowTokens());
                assertEquals(32_000, loop.maxOutputTokens());
                assertEquals(32_000, client.claudeReportedLimits(null).maxOutputTokens());
                assertEquals(0, metrics.getTotalTokens(), "restored context is not new billed usage");
                loop.restoreHistory(List.of());
                assertEquals(expected, metrics.contextTokenSummary(),
                        "the lifecycle's redundant history replay must not erase the native measurement");
                assertEquals(expectedInput, loop.lastReportedInputTokens());
                assertEquals(1, fake.argv().size(), "restoring counters must not call Claude");
            }
        } finally {
            ChatCompleter.setContentOutput(null);
            ChatCompleter.setTranscriptBlockOutput(null);
            ChatCompleter.setActivity(null);
            if (home == null) System.clearProperty("user.home");
            else System.setProperty("user.home", home);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"model", "stale", "compacted", "replaced", "legacy"})
    void contextIsNotRestoredFromAnUnrelatedOrInvalidNativeReceipt(String reason) throws Exception {
        String home = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectories(directory.resolve("home")).toString());
        try {
            var ledger = measuredLedger(80_000);
            ChatConfig config = contextConfig();
            switch (reason) {
                case "model" -> config.setModel("claude-sonnet-different");
                case "stale" -> ledger.append(ai.kompile.cli.main.chat.render.CompactionService.ConversationEntry.user("unseen turn"));
                case "compacted" -> {
                    var before = ledger.snapshot();
                    assertTrue(ledger.commitCompaction(before.version(), before.coveredThroughForPrefix(2),
                            "portable summary", "generic", "anthropic", config.getModel(), 1000, 100));
                }
                case "replaced" -> ledger.recordNativeSession("claude-cli", "different-native", "digest");
                case "legacy" -> ledger.recordNativeSession("claude-cli", "native-measured", "digest");
            }
            var mapper = JsonUtils.standardMapper();
            try (var client = new DirectLlmClient(config, mapper, directory)) {
                var loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                        new PermissionService(), new AgentRegistry(), directory, client, null);
                var metrics = new ai.kompile.cli.main.chat.ChatSessionMetrics(SESSION);
                loop.configureConversationSession(SESSION);
                loop.setSessionMetrics(metrics);
                assertEquals(0, loop.lastReportedInputTokens(), reason);
                assertNull(client.claudeReportedLimits(null), reason);
                assertTrue(metrics.contextTokenSummary().startsWith("?/"), metrics.contextTokenSummary());
            }
        } finally {
            if (home == null) System.clearProperty("user.home");
            else System.setProperty("user.home", home);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void restoredWindowRespectsExplicitCapsAndDoesNotInventPostCompactionUsage(boolean explicitCap) throws Exception {
        String home = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectories(directory.resolve("home")).toString());
        try {
            measuredLedger(explicitCap ? 80_000 : 0);
            ChatConfig config = contextConfig();
            if (explicitCap) {
                config.setContextWindowTokens(100_000);
                config.setMaxOutputTokens(4096);
            }
            var mapper = JsonUtils.standardMapper();
            try (var client = new DirectLlmClient(config, mapper, directory)) {
                var loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                        new PermissionService(), new AgentRegistry(), directory, client, null);
                var metrics = new ai.kompile.cli.main.chat.ChatSessionMetrics(SESSION);
                loop.configureConversationSession(SESSION);
                loop.setSessionMetrics(metrics);
                assertEquals(explicitCap ? "80,000/100,000 80% last input" : "?/200,000 last input",
                        metrics.contextTokenSummary());
                assertEquals(explicitCap ? 100_000 : 200_000, loop.contextWindowTokens());
                assertEquals(explicitCap ? 4096 : 32_000, loop.maxOutputTokens());
                assertEquals(200_000, client.claudeReportedLimits(null).contextWindow());
            }
        } finally {
            if (home == null) System.clearProperty("user.home");
            else System.setProperty("user.home", home);
        }
    }

    @Test
    void restartedChatUsesConfiguredCompactWindowInsteadOfNativeModelMaximum() throws Exception {
        String priorHome = System.getProperty("user.home");
        Path home = Files.createDirectories(directory.resolve("policy-home"));
        System.setProperty("user.home", home.toString());
        try {
            Files.createDirectories(home.resolve(".claude"));
            Files.writeString(home.resolve(".claude/settings.json"), """
                    {"env":{"CLAUDE_CODE_AUTO_COMPACT_WINDOW":"256000","CLAUDE_CODE_DISABLE_1M_CONTEXT":"0"}}
                    """);
            var ledger = new ConversationLedger(JsonUtils.standardMapper());
            ledger.configureSession(SESSION);
            ledger.append(ai.kompile.cli.main.chat.render.CompactionService.ConversationEntry.user("request"));
            ledger.append(ai.kompile.cli.main.chat.render.CompactionService.ConversationEntry.assistant("response"));
            ledger.recordNativeSession("claude-cli", "native-context", "rules",
                    new ConversationLedger.NativeContextUsage("claude-opus-5-5", 197_000, 10, 1_000_000, 32_000));
            var mapper = JsonUtils.standardMapper();
            try (var client = new DirectLlmClient(contextConfig(), mapper, directory)) {
                var loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper), new PermissionService(),
                        new AgentRegistry(), directory, client, null);
                var metrics = new ai.kompile.cli.main.chat.ChatSessionMetrics(SESSION);
                loop.configureConversationSession(SESSION);
                loop.setSessionMetrics(metrics);
                assertEquals(256_000, loop.resolvedContextWindowTokens());
                assertEquals(256_000, loop.contextWindowTokens());
                assertEquals("197,000/256,000 77% last input · compact window", metrics.contextTokenSummary());
                assertEquals(1_000_000, client.claudeReportedLimits(null).contextWindow());
                assertTrue(loop.claudeCompactionPolicyDescription().contains("Trigger/headroom are native-managed"));
            }
        } finally {
            if (priorHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", priorHome);
        }
    }

    private ConversationLedger measuredLedger(long input) {
        var ledger = new ConversationLedger(JsonUtils.standardMapper());
        ledger.configureSession(SESSION);
        ledger.append(ai.kompile.cli.main.chat.render.CompactionService.ConversationEntry.user("request"));
        ledger.append(ai.kompile.cli.main.chat.render.CompactionService.ConversationEntry.assistant("response"));
        ledger.recordNativeSession("claude-cli", "native-measured", "digest",
                new ConversationLedger.NativeContextUsage("claude-opus-5-5", input, 10, 200_000, 32_000));
        return ledger;
    }

    private ChatConfig contextConfig() {
        var config = new ChatConfig("anthropic", null, "claude-opus-5-5", null);
        config.setAuthenticationMethod("oauth");
        config.setDefaultMemory(false);
        return config;
    }

    /** One Kompile process: a new client and loop bound to the same chat session. */
    private String chatInNewProcess(String message) throws Exception {
        return chatInNewProcess(message, new java.util.concurrent.atomic.AtomicBoolean());
    }

    private String chatInNewProcess(String message, java.util.concurrent.atomic.AtomicBoolean signal) throws Exception {
        var mapper = JsonUtils.standardMapper();
        ChatConfig config = new ChatConfig("anthropic", null, "claude-opus-5-5", null);
        config.setAuthenticationMethod("oauth");
        config.setDefaultMemory(false);
        config.setContextWindowTokens(200_000);
        config.setMaxOutputTokens(4_096);
        Path project = Files.createDirectories(directory.resolve("project"));
        ChatHistory history = new ChatHistory(SESSION);
        history.open("(local)", "claude", false, project);
        history.logUserMessage(message);
        history.close();
        try (DirectLlmClient client = new DirectLlmClient(config, mapper, project)) {
            AgenticChatLoop loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                    new PermissionService(), new AgentRegistry(), project, client, null);
            loop.configureConversationSession(SESSION);
            loop.setCancelSignal(signal);
            // Restoring a durable conversation resets the transport, so install after it.
            installFakeClaude(client, project);
            return loop.chat(message, SESSION, "coder", "default", false);
        }
    }

    /** The text of the message the given turn sent to Claude Code, counting from 1. */
    private String prompt(int turn) throws Exception {
        return fake.messages().get(turn - 1);
    }

    /** Both processes start the same fake, so its logs cover the whole chat. */
    private void installFakeClaude(DirectLlmClient client, Path project) throws Exception {
        if (fake == null) {
            Path nativeProject = Files.createDirectories(Path.of(System.getProperty("user.home"))
                    .resolve(".claude/projects/test-project"));
            fake = new FakeClaudeCode(directory.resolve("claude"), """
                    ANSWER=done
                    turn() {
                      printf '%%s\\n' '{"type":"assistant","message":{"role":"assistant","content":"done"}}' >> '%s/'"$SESSION"'.jsonl'
                      say_init
                      say_text "$ANSWER"
                      say_result
                    }
                    """.formatted(nativeProject));
        }
        fake.install(client, project, null);
    }
}
