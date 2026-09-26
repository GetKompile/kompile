package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tui.KompileTui;
import ai.kompile.cli.main.chat.tui.VirtualTerminal;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Standard chat on the Claude subscription route, painted by the real TUI: a
 * provider-executed MCP call must be visible in the main transcript rows while it
 * runs and after it completes, not only in the bottom activity panel. The fake
 * {@code claude} replays the CLI's stream-json and holds the tool result until
 * the running state has been checked on screen.
 */
@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ClaudeCliProviderToolTtyTest {
    private static final int WIDTH = 100, HEIGHT = 40;
    private static final String PROMPT = "kompile > ";
    private static final String FIXTURE = "/ai/kompile/cli/main/chat/agent/claude-stream-mcp-tool.jsonl";
    /** Fixture lines emitted before the provider reports the tool result. */
    private static final int LINES_BEFORE_TOOL_RESULT = 18;
    @TempDir Path home;

    @Test
    void providerMcpCallPaintsInMainTranscriptWhileRunningAndAfterCompletion() throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LineDisciplineTerminal terminal = new LineDisciplineTerminal(
                "claude-provider-tool-test", "xterm", output, StandardCharsets.UTF_8);
        terminal.setSize(new Size(WIDTH, HEIGHT));
        Attributes attributes = terminal.getAttributes();
        attributes.setLocalFlag(Attributes.LocalFlag.ECHO, false);
        attributes.setLocalFlag(Attributes.LocalFlag.ICANON, false);
        attributes.setInputFlag(Attributes.InputFlag.ICRNL, false);
        terminal.setAttributes(attributes);
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes = new BackgroundProcessManager("claude-provider-tool-test");
        MessageQueue queue = new MessageQueue("claude-provider-tool-" + UUID.randomUUID());
        KompileTui tui = new KompileTui(tasks, processes, queue, new TerminalRenderer(true));
        StandardChatActivityPanel panel = new StandardChatActivityPanel(
                tasks, processes, tui.getStatusBar(), tui::getReservedMiddleRows);
        ExecutorService inputThread = Executors.newSingleThreadExecutor();
        ExecutorService dispatch = Executors.newSingleThreadExecutor();
        Path release = home.resolve("release-tool-result");
        var mapper = JsonUtils.standardMapper();
        ChatConfig config = new ChatConfig("anthropic", null, "claude-opus-5-5", null);
        config.setAuthenticationMethod("oauth");
        config.setDefaultMemory(false);
        config.setContextWindowTokens(200_000);
        config.setMaxOutputTokens(4_096);
        try (DirectLlmClient client = new DirectLlmClient(config, mapper, home)) {
            installFakeClaude(client, release);
            AgenticChatLoop loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                    new PermissionService(), new AgentRegistry(), home, client, null);
            String session = "claude-provider-tool-" + UUID.randomUUID();
            loop.configureConversationSession(session);
            // Same bottom-panel feed as ChatRepl.
            loop.setToolActivityListener(new AgenticChatLoop.ToolActivityListener() {
                @Override
                public void onToolStart(String callId, String toolName, String rawInput) {
                    panel.recordToolStart(callId, toolName, rawInput);
                }

                @Override
                public void onToolInput(String callId, String toolName, String rawInput) {
                    panel.updateToolInput(callId, toolName, rawInput);
                }

                @Override
                public void onToolComplete(String callId, String toolName, String rawInput,
                                           ToolResult result) {
                    panel.recordToolComplete(callId, toolName, rawInput, result);
                }
            });
            // Same layout and sinks as ChatRepl once the TUI has started.
            tui.setReservedRowsCalculator(StandardChatActivityPanel::reservedRowsForTerminal);
            tui.attachLineReader(reader);
            tui.start(terminal);
            panel.refresh();
            ChatCompleter.setTerminalRef(reader, terminal);
            ChatCompleter.setContentRedraw(tui::redrawContentView);
            ChatCompleter.setContentOutput(tui::recordInScrollRegion);
            ChatCompleter.setTranscriptBlockOutput(tui::upsertMainTranscriptBlock);
            inputThread.submit(() -> reader.readLine(PROMPT));
            await(output, () -> reader.isReading()
                    && screen(output).getRow(tui.inputTop() - 1).contains(PROMPT.strip()));

            Future<String> turn = dispatch.submit(() ->
                    loop.chat("read the notes", session, "coder", "default", false));
            await(output, () -> bottomRows(tui, screen(output)).contains("toolu_mcp_read_1")
                    && transcriptRows(tui, screen(output)).lines()
                            .anyMatch(row -> row.contains("Read") && row.contains("NOTES.md")));
            assertFalse(turn.isDone(), "the tool must still be running for this check");

            Files.createFile(release);
            String response = turn.get(10, TimeUnit.SECONDS);
            await(output, () -> transcriptRows(tui, screen(output)).contains("The notes file is short."));
            VirtualTerminal screen = screen(output);
            String transcript = transcriptRows(tui, screen);
            String diagnostics = "response=" + response + "\n" + screen.screenDump();
            System.out.println("[claude-provider-tool-screen]\n" + diagnostics);
            int text = transcript.indexOf("Reading the notes file now.");
            int tool = transcript.indexOf("NOTES.md");
            int answer = transcript.indexOf("The notes file is short.");
            assertTrue(transcript.contains("✓"), "completed tool block\n" + diagnostics);
            assertTrue(text >= 0 && text < tool && tool < answer,
                    "tool block must sit between the prose around it\n" + diagnostics);
            assertEquals(text, transcript.lastIndexOf("Reading the notes file now."),
                    "streamed text must be painted once\n" + diagnostics);
            assertFalse(transcript.contains("arguments:"),
                    "the header already shows the tool input\n" + diagnostics);
        } finally {
            if (!Files.exists(release)) Files.createFile(release);
            ChatCompleter.clearTerminalRef(reader);
            tui.detachLineReader();
            dispatch.shutdownNow();
            inputThread.shutdownNow();
            inputThread.awaitTermination(2, TimeUnit.SECONDS);
            tui.stop();
            processes.close();
            terminal.close();
            queue.clear();
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    private void installFakeClaude(DirectLlmClient client, Path release) throws Exception {
        Path stream = home.resolve("claude-stream.jsonl");
        try (InputStream fixture = getClass().getResourceAsStream(FIXTURE)) {
            assertNotNull(fixture, "fixture");
            Files.write(stream, fixture.readAllBytes());
        }
        Path fake = home.resolve("fake-claude");
        Files.writeString(fake, "#!/bin/bash\n"
                + "cat > /dev/null 2>&1 || true\n"
                + "head -n " + LINES_BEFORE_TOOL_RESULT + " '" + stream + "'\n"
                + "for i in $(seq 1 200); do [ -e '" + release + "' ] && break; sleep 0.05; done\n"
                + "tail -n +" + (LINES_BEFORE_TOOL_RESULT + 1) + " '" + stream + "'\n"
                + "exit 0\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(fake, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        Class<?> transport = Class.forName("ai.kompile.cli.main.chat.config.ClaudeCliClient");
        Constructor<?> constructor = transport.getDeclaredConstructor(
                Path.class, String.class, String.class);
        constructor.setAccessible(true);
        Object claude = constructor.newInstance(home, "claude-native-session", fake.toString());
        Field field = DirectLlmClient.class.getDeclaredField("claudeServeClient");
        field.setAccessible(true);
        field.set(client, claude);
    }

    private static String transcriptRows(KompileTui tui, VirtualTerminal screen) {
        return rows(screen, tui.scrollTop() - 1, tui.transcriptBottom());
    }

    private static String bottomRows(KompileTui tui, VirtualTerminal screen) {
        return rows(screen, tui.scrollBottom(), HEIGHT);
    }

    private static String rows(VirtualTerminal screen, int from, int to) {
        StringBuilder rows = new StringBuilder();
        for (int row = from; row < to; row++) rows.append(screen.getRow(row)).append('\n');
        return rows.toString();
    }

    private static VirtualTerminal screen(ByteArrayOutputStream output) {
        VirtualTerminal screen = new VirtualTerminal(HEIGHT, WIDTH);
        screen.feed(output.toString(StandardCharsets.UTF_8));
        return screen;
    }

    private static void await(ByteArrayOutputStream output, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), () -> "timed out\n" + screen(output).screenDump());
    }
}
