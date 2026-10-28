package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatCompleter;
import ai.kompile.cli.main.chat.ChatUiSession;
import ai.kompile.cli.main.chat.BackgroundTaskManager;
import ai.kompile.cli.main.chat.MessageQueue;
import ai.kompile.cli.main.chat.config.ChatUiConfig;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tui.KompileTui;
import ai.kompile.cli.main.chat.tui.VirtualTerminal;
import org.jline.terminal.Size;
import org.jline.terminal.impl.LineDisciplineTerminal;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.ChatDisplayBlock;
import ai.kompile.cli.main.chat.render.StreamingMarkdownRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.render.ThinkingStreamRenderer;
import ai.kompile.cli.main.chat.tools.SidePanelTool;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tui.SidePanelManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Presentation-only fixtures: no provider requests or tool executions. */
class AgenticChatLoopDisplayBlockTest {
    @TempDir Path directory;

    @Test
    void typedToolReplacesRunningStreamedAndCompletedSnapshotsAndNeverEmitsLegacyTwice() throws Exception {
        try (ChatUiSession session = new ChatUiSession(); var ignored = session.bind()) {
            Map<String, ChatDisplayBlock> cards = Collections.synchronizedMap(new LinkedHashMap<>());
            List<String> legacy = Collections.synchronizedList(new ArrayList<>());
            ChatCompleter.setDisplayBlockOutput(block -> { cards.put(block.key(), block); return true; });
            ChatCompleter.setTranscriptBlockOutput((key, content) -> { legacy.add(content); return true; });
            ChatCompleter.setContentOutput(legacy::add);
            AgenticChatLoop loop = loop(null);
            Object tool = toolBlock(loop, "tool:stable", "read", "{}");
            assertTrue(cards.get("tool:stable").running());
            invoke(tool, "updateInput", String.class, "{\"file_path\":\"A.txt\"}");
            String output = "source line\n".repeat(100) + "retained tail";
            invoke(tool, "appendOutput", String.class, output);
            invoke(tool, "publishManagedUpdate");
            ChatDisplayBlock streamed = cards.get("tool:stable");
            assertTrue(streamed.running());
            assertTrue(section(streamed, "arguments").content().contains("A.txt"));
            assertTrue(section(streamed, "output").content().contains("retained tail"));
            ToolResult result = ToolResult.success("read A.txt", output, Map.of("outputStreamed", true));
            invoke(tool, "complete", ToolResult.class, result);
            ChatDisplayBlock completed = cards.get("tool:stable");
            assertFalse(completed.running());
            assertFalse(completed.error());
            assertEquals(output, section(completed, "output").content());
            assertEquals(invoke(tool, "renderBlock"), completed.classic(), "classic renderBlock must be exact");
            assertFalse(completed.sections().stream().anyMatch(value -> value.id().equals("live-output")));
            assertEquals(1, cards.size());
            assertTrue(legacy.isEmpty(), "accepted typed snapshots must not also enter legacy sinks");
        }
    }

    @Test
    void monitoredActivityKeepsChildTranscriptsOutOfTypedAndAppendOnlyChat() throws Exception {
        try (ChatUiSession session = new ChatUiSession(); var ignored = session.bind()) {
            Map<String, ChatDisplayBlock> cards = new LinkedHashMap<>();
            List<String> lines = new ArrayList<>();
            ChatCompleter.setDisplayBlockOutput(block -> { cards.put(block.key(), block); return true; });
            ChatCompleter.setContentOutput(lines::add);
            AgenticChatLoop loop = loop(null);
            String transcript = "PRIVATE CHILD TRANSCRIPT\n".repeat(30);
            for (String name : List.of("process", "task", "multi_task", "quorum_task", "poll",
                    "mcp__kompile__task", "mcp__kompile__process", "Agent", "TaskOutput")) {
                Object tool = toolBlock(loop, name, name, "{}");
                ChatDisplayBlock started = cards.get(name);
                invoke(tool, "appendOutput", String.class, transcript);
                assertSame(started, cards.get(name), "child deltas must not repaint Main");
                invoke(tool, "complete", ToolResult.class, ToolResult.success("done", transcript, Map.of()));
                ChatDisplayBlock completed = cards.get(name);
                assertFalse(completed.running());
                assertTrue(completed.sections().isEmpty(), "no expandable child transcript in Main");
                assertFalse(completed.classic().contains("PRIVATE CHILD TRANSCRIPT"));
                assertTrue(completed.classic().contains("/activity"));
            }
            assertTrue(lines.isEmpty());
            ChatCompleter.setDisplayBlockOutput(null);
            ChatCompleter.setTranscriptBlockOutput(null);
            Object fallback = toolBlock(loop, "fallback-monitor", "process", "{}");
            invoke(fallback, "appendOutput", String.class, transcript);
            invoke(fallback, "complete", ToolResult.class, ToolResult.success("done", transcript, Map.of()));
            assertFalse(String.join("\n", lines).contains("PRIVATE CHILD TRANSCRIPT"));
            assertFalse(new TerminalRenderer(false).renderToolCallComplete("task", "{}",
                    ToolResult.success("done", transcript, Map.of())).contains("PRIVATE CHILD TRANSCRIPT"));
        }
    }

    @Test
    void localAndProviderTodosOwnOneListCardAndLegacyFallbackUsesSameList() throws Exception {
        try (ChatUiSession session = new ChatUiSession(); var ignored = session.bind()) {
            Map<String, ChatDisplayBlock> cards = new LinkedHashMap<>();
            List<String> lines = new ArrayList<>();
            ChatCompleter.setDisplayBlockOutput(block -> { cards.put(block.key(), block); return true; });
            ChatCompleter.setContentOutput(lines::add);
            AgenticChatLoop loop = loop(null);
            ToolResult result = ToolResult.success("updated", "generic output", Map.of("todos", List.of(
                    Map.of("id", "1", "subject", "Working", "status", "in_progress"))));
            for (String name : List.of("todowrite", "todoread", "mcp__kompile__todowrite", "mcp__kompile__todoread", "TodoWrite")) {
                Object tool = toolBlock(loop, name, name, "{}");
                invoke(tool, "complete", ToolResult.class, result);
                ChatDisplayBlock card = cards.get(name);
                assertEquals("todos", card.kind());
                assertFalse(card.running());
                assertEquals(1, card.sections().size());
                assertEquals("todos", card.sections().get(0).kind());
                String plain = TerminalRenderer.printableToolOutput(card.classic());
                assertEquals(1, plain.split("Tasks \\[0/1\\]", -1).length - 1, plain);
                assertTrue(plain.contains("▶ #1 Working [current]"), plain);
                assertFalse(plain.contains("generic output"), plain);
            }
            assertEquals(5, cards.size());
            assertTrue(lines.isEmpty(), "List cards must not also emit a duplicate inline list");
            ChatCompleter.setDisplayBlockOutput(null);
            Map<String, String> legacy = new LinkedHashMap<>();
            ChatCompleter.setTranscriptBlockOutput((key, content) -> { legacy.put(key, content); return true; });
            Object fallback = toolBlock(loop, "fallback", "todoread", "{}");
            invoke(fallback, "complete", ToolResult.class, result);
            assertTrue(TerminalRenderer.printableToolOutput(legacy.get("fallback")).contains("▶ #1 Working [current]"));
        }
    }

    @Test
    void missingOrFailingTypedSinkKeepsExistingTranscriptRenderingAndDeniedErrorsAreTyped() throws Exception {
        try (ChatUiSession session = new ChatUiSession(); var ignored = session.bind()) {
            Map<String, String> legacy = Collections.synchronizedMap(new LinkedHashMap<>());
            ChatCompleter.setTranscriptBlockOutput((key, content) -> { legacy.put(key, content); return true; });
            ChatCompleter.setDisplayBlockOutput(block -> { throw new IllegalStateException("viewer failure"); });
            AgenticChatLoop loop = loop(null);
            Object tool = toolBlock(loop, "tool:fallback", "bash", "{}");
            invoke(tool, "appendOutput", String.class, "live");
            invoke(tool, "complete", ToolResult.class, ToolResult.success("done"));
            assertEquals(invoke(tool, "renderBlock"), legacy.get("tool:fallback"));
            Map<String, ChatDisplayBlock> cards = Collections.synchronizedMap(new LinkedHashMap<>());
            ChatCompleter.setDisplayBlockOutput(block -> { cards.put(block.key(), block); return true; });
            Object denied = toolBlock(loop, "tool:denied", "bash", "{}");
            invoke(denied, "completeDenied", String.class, "permission denied");
            ChatDisplayBlock error = cards.get("tool:denied");
            assertTrue(error.error());
            assertFalse(error.running());
            assertTrue(section(error, "output").content().contains("permission denied"));
            assertEquals(invoke(denied, "renderBlock"), error.classic());
            assertFalse(legacy.containsKey("tool:denied"));
        }
    }

    @Test
    void assistantAndThinkingCoalesceByBurstAndFlushTailBeforeOpeningNextIdentity() throws Exception {
        try (ChatUiSession session = new ChatUiSession(); var ignored = session.bind()) {
            Map<String, ChatDisplayBlock> cards = new LinkedHashMap<>();
            List<String> legacy = new ArrayList<>();
            ChatCompleter.setDisplayBlockOutput(block -> { cards.put(block.key(), block); return true; });
            ChatCompleter.setContentOutput(legacy::add);
            AgenticChatLoop loop = loop(null);
            StreamingMarkdownRenderer markdown = (StreamingMarkdownRenderer)
                    invoke(loop, "presentationMarkdownRenderer");
            markdown.accept("hello ");
            String first = cards.keySet().iterator().next();
            assertEquals("hello ", TerminalRenderer.printableToolOutput(cards.get(first).classic()),
                    "A redraw must show a pending assistant line before its newline/flush");
            markdown.accept("world");
            assertEquals(1, cards.size());
            assertEquals("hello world", section(cards.get(first), "body").content());
            assertEquals("hello world", TerminalRenderer.printableToolOutput(cards.get(first).classic()));
            markdown.flush();
            assertFalse(cards.get(first).running());
            assertEquals("hello world", TerminalRenderer.printableToolOutput(cards.get(first).classic()));
            markdown.accept("next burst\npartial tail");
            ChatDisplayBlock second = cards.values().stream().filter(card -> !card.key().equals(first)).findFirst().orElseThrow();
            assertEquals("next burst\npartial tail", TerminalRenderer.printableToolOutput(second.classic()));
            markdown.accept(" continued\n");
            markdown.flush();
            assertEquals(2, cards.size());
            assertEquals("next burst\npartial tail continued", TerminalRenderer.printableToolOutput(cards.get(second.key()).classic()),
                    "Completing the line must replace its preview, not duplicate it");

            Object thinking = inner(loop, "TextTranscriptBurst", new Class<?>[]{String.class}, "thinking");
            ThinkingStreamRenderer renderer = new ThinkingStreamRenderer(
                    line -> invokeUnchecked(thinking, "rendered", String.class, line), null);
            invoke(thinking, "appendRaw", String.class, "reason ");
            renderer.accept("reason ");
            invoke(thinking, "appendRaw", String.class, "tail");
            renderer.accept("tail");
            renderer.flush();
            invoke(thinking, "finish");
            ChatDisplayBlock thought = cards.values().stream()
                    .filter(block -> block.kind().equals("thinking")).findFirst().orElseThrow();
            assertEquals("reason tail", section(thought, "body").content());
            assertFalse(thought.running());
            assertTrue(TerminalRenderer.printableToolOutput(thought.classic()).contains("reason tail"));
            assertTrue(legacy.isEmpty());
        }
    }

    @Test
    void liveAssistantFramesSurviveUiSummaryPreviewCancelAndLayoutChanges() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ChatUiSession session = new ChatUiSession(); var ignored = session.bind();
             LineDisciplineTerminal terminal = new LineDisciplineTerminal("assistant-ui", "xterm", output, StandardCharsets.UTF_8);
             BackgroundProcessManager processes = new BackgroundProcessManager("assistant-ui")) {
            terminal.setSize(new Size(120, 24));
            KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes,
                    new MessageQueue("assistant-ui"), new TerminalRenderer(true)) {
                @Override public void requestRedraw() { } // Explicit frames keep screen snapshots deterministic.
            };
            try {
                tui.start(terminal);
                ChatCompleter.setDisplayBlockOutput(tui::upsertDisplayBlock);
                StreamingMarkdownRenderer markdown = (StreamingMarkdownRenderer) invoke(loop(null), "presentationMarkdownRenderer");
                markdown.accept("\nHello assistant");
                tui.redrawContentView();
                VirtualTerminal frame = new VirtualTerminal(24, 120);
                frame.feed(output.toString(StandardCharsets.UTF_8)); output.reset();
                assertTrue(frame.screenDump().contains("Hello assistant"), frame::screenDump);
                ChatUiConfig summary = ChatUiConfig.classic(); summary.set("content.assistant.inline", "summary");
                tui.setUiConfig(summary); tui.redrawContentView();
                frame.feed(output.toString(StandardCharsets.UTF_8)); output.reset();
                assertTrue(frame.screenDump().contains("Hello assistant"), "Leading blank lines must not hide the summary\n" + frame.screenDump());
                tui.updateTemporaryWindow("UI", List.of("Choose a setting"));
                markdown.accept("\nUpdated during wizard");
                ChatUiConfig hidden = ChatUiConfig.classic(); hidden.set("content.assistant.inline", "none");
                tui.setUiConfig(hidden);
                tui.setUiConfig(ChatUiConfig.classic()); tui.closeTemporaryWindow(); // Cancel
                tui.redrawContentView();
                frame.feed(output.toString(StandardCharsets.UTF_8)); output.reset();
                assertTrue(frame.screenDump().contains("Updated during wizard"), frame::screenDump);
                for (String mode : List.of("split", "inline", "split", "inline")) {
                    ChatUiConfig layout = ChatUiConfig.classic(); layout.set("layout.mode", mode);
                    tui.setUiConfig(layout); tui.redrawContentView();
                    frame.feed(output.toString(StandardCharsets.UTF_8)); output.reset();
                    assertTrue(frame.screenDump().contains("Hello assistant"), frame::screenDump);
                    assertTrue(frame.screenDump().contains("Updated during wizard"), frame::screenDump);
                }
                markdown.flush();
                assertEquals(1, tui.getContentViewLines().stream().filter(line -> line.contains("Updated during wizard")).count());
            } finally { tui.stop(); }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void thinkingTailAndPriorToolAndMessagesSurviveStreamRedraws(boolean summaryTools) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ChatUiSession session = new ChatUiSession(); var ignored = session.bind();
             LineDisciplineTerminal terminal = new LineDisciplineTerminal("thinking-retention", "xterm", output, StandardCharsets.UTF_8);
             BackgroundProcessManager processes = new BackgroundProcessManager("thinking-retention")) {
            terminal.setSize(new Size(120, 32));
            KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes,
                    new MessageQueue("thinking-retention"), new TerminalRenderer(true)) {
                @Override public void requestRedraw() { } // Replay only complete, explicit frames.
            };
            try {
                // Exercise the user's summary-style tool presentation too.
                ChatUiConfig config = ChatUiConfig.classic();
                if (summaryTools) config.set("content.tool.inline", "summary");
                tui.setUiConfig(config); tui.start(terminal);
                ChatCompleter.setDisplayBlockOutput(tui::upsertDisplayBlock);
                tui.recordInScrollRegion("USER MESSAGE");
                tui.upsertDisplayBlock(ChatDisplayBlock.text("assistant:prior", "assistant", "PRIOR ASSISTANT"));
                Object tool = toolBlock(loop(null), "tool:prior", "read", "{\"file_path\":\"Prior.java\"}");
                invoke(tool, "complete", ToolResult.class, ToolResult.success("prior read complete"));
                VirtualTerminal frame = new VirtualTerminal(32, 120);
                var mapper = JsonUtils.standardMapper();
                var client = new ai.kompile.cli.main.chat.config.DirectLlmClient(
                        new ai.kompile.cli.main.chat.config.ChatConfig("custom", null, "thinking-test", "http://unused.invalid"), mapper) {
                    @Override public StreamResult streamChat(String message, String systemPrompt,
                            com.fasterxml.jackson.databind.node.ArrayNode definitions, List<ToolCallResultInput> results,
                            String model, List<AttachmentInput> attachments) {
                        for (String chunk : List.of("FIRST REASON", "\nPENDING REASON", " CONTINUES")) {
                            getThinkingConsumer().accept(chunk);
                            tui.redrawContentView();
                            frame.feed(output.toString(StandardCharsets.UTF_8)); output.reset();
                            String screen = frame.screenDump();
                            assertTrue(screen.contains("FIRST REASON"), screen);
                            if (chunk.contains("PENDING") || chunk.contains("CONTINUES")) {
                                assertTrue(screen.contains("PENDING REASON"), "Completed lines must not hide the pending tail\n" + screen);
                            }
                            for (String retained : List.of("USER MESSAGE", "PRIOR ASSISTANT", "Prior.java")) {
                                assertTrue(screen.contains(retained), screen);
                            }
                            assertTrue(screen.contains("✻ thinking"), screen);
                        }
                        getOutputConsumer().accept("FINAL ANSWER");
                        StreamResult result = new StreamResult(); result.text = "FINAL ANSWER"; return result;
                    }
                };
                AgenticChatLoop loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                        new PermissionService(), new AgentRegistry(), directory, client, null);
                assertEquals("FINAL ANSWER", loop.chat("question", "thinking-test", "coder", "default", false));
                tui.redrawContentView(); frame.feed(output.toString(StandardCharsets.UTF_8));
                String screen = frame.screenDump();
                for (String retained : List.of("USER MESSAGE", "PRIOR ASSISTANT", "Prior.java", "FIRST REASON", "PENDING REASON CONTINUES", "FINAL ANSWER")) {
                    assertTrue(screen.contains(retained), screen);
                }
                assertEquals(1, tui.getContentViewLines().stream().filter(line -> line.contains("PENDING REASON CONTINUES")).count());
                assertEquals(1, tui.getContentViewLines().stream().filter(line -> line.contains("✻ thinking")).count());
            } finally { tui.stop(); }
        }
    }

    @Test
    void sidePanelUpdatesOneCustomKeyAndHidingPublishesEmptyRemovalSnapshot() throws Exception {
        try (ChatUiSession session = new ChatUiSession(); var ignored = session.bind()) {
            Map<String, ChatDisplayBlock> cards = new LinkedHashMap<>();
            ChatCompleter.setDisplayBlockOutput(block -> { cards.put(block.key(), block); return true; });
            SidePanelManager panel = new SidePanelManager();
            AgenticChatLoop loop = loop(panel);
            panel.show("Plan", "detail\n".repeat(100));
            invoke(loop, "renderSidePanelUpdate", boolean.class, true);
            String key = cards.keySet().iterator().next();
            assertEquals("custom", cards.get(key).kind());
            assertEquals("detail\n".repeat(100), section(cards.get(key), "body").content());
            panel.show("Updated", "replacement");
            invoke(loop, "renderSidePanelUpdate", boolean.class, true);
            assertEquals(1, cards.size());
            assertEquals("Updated", cards.get(key).summary());
            panel.hide();
            invoke(loop, "renderSidePanelUpdate", boolean.class, true);
            assertTrue(cards.get(key).sections().isEmpty());
            assertEquals("", cards.get(key).summary());
            assertEquals("", cards.get(key).classic());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void backgroundTransferLeavesForegroundMarkerButDoesNotPublishLaterOutput() throws Exception {
        try (ChatUiSession session = new ChatUiSession(); var ignored = session.bind()) {
            Map<String, ChatDisplayBlock> cards = Collections.synchronizedMap(new LinkedHashMap<>());
            ChatCompleter.setDisplayBlockOutput(block -> { cards.put(block.key(), block); return true; });
            AgenticChatLoop loop = loop(null);
            Object tool = toolBlock(loop, "tool:background", "bash", "{}");
            Field field = AgenticChatLoop.class.getDeclaredField("backgroundOutputConsumer");
            field.setAccessible(true);
            AtomicReference<Consumer<String>> output = (AtomicReference<Consumer<String>>) field.get(loop);
            List<String> background = new ArrayList<>();
            output.set(background::add);
            invoke(tool, "appendOutput", String.class, "background log");
            ChatDisplayBlock marker = cards.get("tool:background");
            assertTrue(marker.classic().contains("continued in background"));
            assertFalse(marker.running());
            invoke(tool, "publishManagedUpdate");
            invoke(tool, "complete", ToolResult.class, ToolResult.success("done"));
            assertSame(marker, cards.get("tool:background"));
            assertTrue(background.stream().anyMatch(line -> line.contains("background log")));
        }
    }

    @Test
    void absentManagedHooksKeepExactAppendOnlyToolOutput() throws Exception {
        try (ChatUiSession session = new ChatUiSession(); var ignored = session.bind()) {
            List<String> lines = new ArrayList<>();
            ChatCompleter.setContentOutput(lines::add);
            AgenticChatLoop loop = loop(null);
            Field field = AgenticChatLoop.class.getDeclaredField("renderer");
            field.setAccessible(true);
            TerminalRenderer renderer = (TerminalRenderer) field.get(loop);
            Object tool = toolBlock(loop, "tool:classic", "bash", "{}");
            invoke(tool, "appendOutput", String.class, "live output");
            ToolResult result = ToolResult.success("done");
            invoke(tool, "complete", ToolResult.class, result);
            assertEquals(List.of(renderer.renderToolCallStart("bash", "{}"), "live output",
                    renderer.renderToolCallComplete("bash", "{}", result)), lines);
        }
    }

    @Test
    void preExecutionDenialUsesTypedErrorAndKeepsAppendOnlyFallback() throws Exception {
        try (ChatUiSession session = new ChatUiSession(); var ignored = session.bind()) {
            List<String> legacy = new ArrayList<>();
            List<ChatDisplayBlock> typed = new ArrayList<>();
            ChatCompleter.setContentOutput(legacy::add);
            AgenticChatLoop loop = loop(null);
            AgenticChatLoop.ToolCallRequest request = new AgenticChatLoop.ToolCallRequest();
            request.id = "denied-call";
            request.name = "bash";
            Method method = AgenticChatLoop.class.getDeclaredMethod("emitToolDenied",
                    AgenticChatLoop.ToolCallRequest.class, String.class, String.class);
            method.setAccessible(true);
            ChatCompleter.setDisplayBlockOutput(block -> { typed.add(block); return true; });
            method.invoke(loop, request, "{\"command\":\"not-run\"}", "held by supervisor");
            assertEquals(1, typed.size());
            assertTrue(typed.get(0).error());
            assertFalse(typed.get(0).running());
            assertTrue(typed.get(0).key().endsWith(":denied-call"));
            assertTrue(section(typed.get(0), "arguments").content().contains("not-run"));
            assertTrue(legacy.isEmpty());
            ChatCompleter.setDisplayBlockOutput(null);
            method.invoke(loop, request, "{}", "held by supervisor");
            assertEquals(1, legacy.size());
            assertTrue(TerminalRenderer.printableToolOutput(legacy.get(0)).contains("held by supervisor"));
        }
    }

    @Test
    void liveRetentionDisclosesOmissionsAndLateTypedHookCanConsumeCompletion() throws Exception {
        try (ChatUiSession session = new ChatUiSession(); var ignored = session.bind()) {
            List<String> legacy = new ArrayList<>();
            ChatCompleter.setContentOutput(legacy::add);
            AgenticChatLoop loop = loop(null);
            Object tool = toolBlock(loop, "tool:late", "bash", "{}");
            assertFalse(legacy.isEmpty());
            Map<String, ChatDisplayBlock> cards = Collections.synchronizedMap(new LinkedHashMap<>());
            ChatCompleter.setDisplayBlockOutput(block -> { cards.put(block.key(), block); return true; });
            invoke(tool, "appendOutput", String.class, "x".repeat(200_000));
            invoke(tool, "complete", ToolResult.class, ToolResult.success(""));
            ChatDisplayBlock completed = cards.get("tool:late");
            assertFalse(completed.running());
            String output = section(completed, "output").content();
            assertTrue(output.length() <= ChatDisplayBlock.MAX_SECTION_CHARS);
            assertTrue(output.contains("omitted"));
            assertEquals(1, legacy.size(), "typed output/completion must not enter the append-only sink");
        }
    }

    @Test
    void providerMessagesScrollAwayAndSurviveModalAndLayoutChanges() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ChatUiSession session = new ChatUiSession(); var ignored = session.bind();
             LineDisciplineTerminal terminal = new LineDisciplineTerminal("notice-ui", "xterm", output, StandardCharsets.UTF_8);
             BackgroundProcessManager processes = new BackgroundProcessManager("notice-ui")) {
            terminal.setSize(new Size(120, 24));
            KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes,
                    new MessageQueue("notice-ui"), new TerminalRenderer(true)) {
                @Override public void requestRedraw() { }
            };
            try {
                tui.start(terminal);
                ChatCompleter.setDisplayBlockOutput(tui::upsertDisplayBlock);
                AgenticChatLoop loop = loop(null);
                Method emit = AgenticChatLoop.class.getDeclaredMethod("emitProviderMessage", String.class, boolean.class);
                emit.setAccessible(true);
                tui.updateTemporaryWindow("UI", List.of("Choose a setting"));
                emit.invoke(loop, "[Claude] Fast mode is unavailable", false);
                emit.invoke(loop, "context compacted by Claude Code", false);
                ChatUiConfig focused = ChatUiConfig.classic(); focused.applyPreset("focused");
                tui.setUiConfig(focused);
                tui.closeTemporaryWindow();
                tui.redrawContentView();
                VirtualTerminal frame = new VirtualTerminal(24, 120);
                frame.feed(output.toString(StandardCharsets.UTF_8)); output.reset();
                assertTrue(frame.screenDump().contains("Fast mode is unavailable"), frame::screenDump);
                assertTrue(frame.screenDump().contains("context compacted by Claude Code"), frame::screenDump);
                tui.upsertDisplayBlock(ChatDisplayBlock.text("assistant:later", "assistant",
                        java.util.stream.IntStream.range(0, 60).mapToObj(i -> "Later assistant line " + i)
                                .collect(java.util.stream.Collectors.joining("\n"))));
                tui.redrawContentView();
                frame.feed(output.toString(StandardCharsets.UTF_8));
                assertTrue(frame.screenDump().contains("Later assistant line 59"), frame::screenDump);
                assertFalse(frame.screenDump().contains("Fast mode is unavailable"),
                        "Notifications must scroll out, not remain pinned\n" + frame.screenDump());
                assertFalse(frame.screenDump().contains("context compacted"), frame::screenDump);
                String retained = String.join("\n", tui.getContentViewLines());
                assertTrue(retained.contains("Fast mode is unavailable"));
                assertTrue(retained.contains("context compacted"), "Messages remain available in scrollback");
            } finally { tui.stop(); }
        }
    }

    @Test
    void providerMessagesFallBackOnSinkFailureAndStayWithBackgroundOwner() throws Exception {
        try (ChatUiSession session = new ChatUiSession(); var ignored = session.bind()) {
            List<String> legacy = new ArrayList<>();
            List<String> background = new ArrayList<>();
            ChatCompleter.setContentOutput(legacy::add);
            ChatCompleter.setDisplayBlockOutput(block -> { throw new IllegalStateException("sink failed"); });
            AgenticChatLoop loop = loop(null);
            Method emit = AgenticChatLoop.class.getDeclaredMethod("emitProviderMessage", String.class, boolean.class);
            emit.setAccessible(true);
            emit.invoke(loop, "[Claude] notice", false);
            assertEquals(List.of("[Claude] notice"), legacy);
            ChatCompleter.setDisplayBlockOutput(block -> { fail("Background messages must not enter foreground cards"); return true; });
            loop.backgroundActiveTurn(background::add);
            emit.invoke(loop, "[Claude] background notice", false);
            assertEquals(List.of("[Claude] background notice" + System.lineSeparator()), background);
            assertEquals(1, legacy.size(), "Detached messages must not enter the foreground transcript");
        }
    }

    private AgenticChatLoop loop(SidePanelManager panel) {
        var mapper = JsonUtils.standardMapper();
        ToolRegistry tools = new ToolRegistry(mapper);
        if (panel != null) tools.register(new SidePanelTool(panel));
        return new AgenticChatLoop(null, mapper, tools, new PermissionService(), new AgentRegistry(), directory);
    }

    private static Object toolBlock(AgenticChatLoop loop, String key, String name, String input) throws Exception {
        return inner(loop, "ToolTranscriptBlock", new Class<?>[]{String.class, String.class, String.class},
                key, name, input);
    }

    private static Object inner(AgenticChatLoop loop, String name, Class<?>[] types, Object... arguments)
            throws Exception {
        Class<?> type = Class.forName(AgenticChatLoop.class.getName() + "$" + name);
        Class<?>[] allTypes = new Class<?>[types.length + 1];
        allTypes[0] = AgenticChatLoop.class;
        System.arraycopy(types, 0, allTypes, 1, types.length);
        Object[] allArguments = new Object[arguments.length + 1];
        allArguments[0] = loop;
        System.arraycopy(arguments, 0, allArguments, 1, arguments.length);
        Constructor<?> constructor = type.getDeclaredConstructor(allTypes);
        constructor.setAccessible(true);
        return constructor.newInstance(allArguments);
    }

    private static Object invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Object invoke(Object target, String name, Class<?> type, Object value) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, type);
        method.setAccessible(true);
        return method.invoke(target, value);
    }

    private static void invokeUnchecked(Object target, String name, Class<?> type, Object value) {
        try { invoke(target, name, type, value); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static ChatDisplayBlock.Section section(ChatDisplayBlock block, String id) {
        return block.sections().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
    }
}
