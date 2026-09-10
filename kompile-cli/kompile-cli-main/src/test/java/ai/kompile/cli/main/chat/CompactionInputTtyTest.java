package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.context.ConversationLedger;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.CompactionProgressIndicator;
import ai.kompile.cli.main.chat.render.CompactionService;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tui.KompileTui;
import ai.kompile.cli.main.chat.tui.VirtualTerminal;
import com.sun.net.httpserver.HttpServer;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Real one-shot HTTP streaming and JLine screen regression, without a live model. */
@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class CompactionInputTtyTest {
    private static final int WIDTH = 72, HEIGHT = 24;
    private static final String PROMPT = "kompile > ";
    private static final String PRIVATE_SUMMARY = "PRIVATE_COMPACTION_SUMMARY";
    @TempDir Path home;

    @ParameterizedTest(name = "automatic={0}, multiline={1}")
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void compactionPreservesInputWithoutPaintingSummary(boolean automatic, boolean multiline)
            throws Exception {
        String summary = multiline ? (PRIVATE_SUMMARY + " detail\n").repeat(60) : PRIVATE_SUMMARY;
        String draft = multiline ? "fresh draft\nsecond draft line" : "fresh draft";
        var mapper = JsonUtils.standardMapper();
        AtomicInteger summaryCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean summarizing = request.contains("You are a precise conversation summarizer");
            if (summarizing) summaryCalls.incrementAndGet();
            String content = summarizing ? summary : "normal assistant response";
            String json = mapper.writeValueAsString(Map.of("choices", List.of(
                    Map.of("delta", Map.of("content", content), "finish_reason", "stop"))));
            byte[] payload = ("data: " + json + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        PrintStream originalOut = System.out;
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LineDisciplineTerminal terminal = new LineDisciplineTerminal(
                "compaction-input-test", "xterm", output, StandardCharsets.UTF_8);
        terminal.setSize(new Size(WIDTH, HEIGHT));
        Attributes attributes = terminal.getAttributes();
        attributes.setLocalFlag(Attributes.LocalFlag.ECHO, false);
        attributes.setLocalFlag(Attributes.LocalFlag.ICANON, false);
        attributes.setInputFlag(Attributes.InputFlag.ICRNL, false);
        terminal.setAttributes(attributes);
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
        BackgroundProcessManager processes = new BackgroundProcessManager("compaction-input-test");
        MessageQueue queue = new MessageQueue("compaction-input-" + UUID.randomUUID());
        KompileTui tui = new KompileTui(
                new BackgroundTaskManager(), processes, queue, new TerminalRenderer(true));
        ExecutorService inputThread = Executors.newSingleThreadExecutor();
        CompactionProgressIndicator progress = null;
        ChatConfig config = new ChatConfig("custom", "fake-local-test-key", "retention-test",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        config.setAuthenticationMethod("api-key");
        config.setDefaultMemory(false);
        config.setContextWindowTokens(50_000);
        config.setMaxOutputTokens(1_024);
        server.start();
        try (DirectLlmClient client = new DirectLlmClient(config, mapper, home)) {
            AgenticChatLoop loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                    new PermissionService(), new AgentRegistry(), home, client, null);
            String session = "compaction-input-" + UUID.randomUUID();
            loop.configureConversationSession(session);
            Field field = AgenticChatLoop.class.getDeclaredField("conversationLedger");
            field.setAccessible(true);
            ConversationLedger ledger = (ConversationLedger) field.get(loop);
            for (int i = 0; i < 3; i++) {
                ledger.append(CompactionService.ConversationEntry.user("old request ".repeat(4000)));
                ledger.append(CompactionService.ConversationEntry.assistant("old result ".repeat(4000)));
            }
            tui.attachLineReader(reader);
            ChatCompleter.setTerminalRef(reader, terminal);
            ChatCompleter.setContentOutput(tui::recordInScrollRegion);
            ChatCompleter.setContentRedraw(tui::redrawContentView);
            ChatCompleter.setTranscriptBlockOutput(tui::upsertMainTranscriptBlock);
            ChatCompleter.enableAutoTrigger(reader);
            tui.start(terminal);
            Future<String> submitted = inputThread.submit(() -> reader.readLine(PROMPT));
            await(output, () -> reader.isReading()
                    && screen(output).getRow(tui.inputTop() - 1).contains(PROMPT.strip()));
            type(terminal, "\033[200~" + draft + "\033[201~");
            await(output, () -> draft.equals(reader.getBuffer().toString())
                    && screen(output).getRow(tui.inputTop() - 1).contains("fresh draft"));
            String inputBefore = inputPane(tui, screen(output));

            // In the real process stdout and the JLine writer reach the same terminal.
            // Capturing only ChatCompleter would miss the original raw stdout leak.
            System.setOut(new PrintStream(terminal.output(), true, StandardCharsets.UTF_8));
            if (automatic) {
                assertEquals("normal assistant response",
                        loop.chat("continue", session, "coder", "default", false));
            } else {
                progress = CompactionProgressIndicator.start("compact:test", new TerminalRenderer(true),
                        loop.conversationEntryCount(), loop.estimateConversationTokens(),
                        ChatCompleter::printAbove, ChatCompleter::setActivity);
                var result = loop.forceCompact(null, progress);
                assertTrue(result.isSuccess(), result::getMessage);
                assertEquals(summary, result.getSummary());
                progress.complete(result.getTokensBefore(), result.getTokensAfter(), result.getPreservedTurns());
            }
            assertEquals(1, summaryCalls.get(), "the test must exercise generic compaction");
            assertNotNull(ledger.snapshot().checkpoint());
            assertTrue(ledger.snapshot().activeEntries().stream()
                    .anyMatch(entry -> entry.content != null && entry.content.contains(PRIVATE_SUMMARY)),
                    "the summary must remain available to the conversation, not be discarded");
            await(output, () -> screen(output).screenDump().contains("Context compacted"));
            // Follow with normal background output so a frame cannot hide a contaminated draft.
            ChatCompleter.printAbove("post-compaction redraw marker");
            await(output, () -> screen(output).screenDump().contains("post-compaction redraw marker"));
            assertFalse(output.toString(StandardCharsets.UTF_8).contains(PRIVATE_SUMMARY),
                    "no summary bytes may reach the terminal, even transiently");
            assertFalse(String.join("\n", tui.getContentViewLines()).contains(PRIVATE_SUMMARY),
                    "private compaction text must not enter the visible transcript either");
            assertEquals(inputBefore, inputPane(tui, screen(output)));
            assertEquals(draft, reader.getBuffer().toString());
            assertNull(client.getOutputConsumer(), "restore the parent's original output routing");
            type(terminal, "\r");
            assertEquals(draft, submitted.get(3, TimeUnit.SECONDS));
        } finally {
            System.setOut(originalOut);
            if (progress != null) progress.abandonIfActive("test cleanup");
            ChatCompleter.clearTerminalRef(reader);
            tui.detachLineReader();
            inputThread.shutdownNow();
            inputThread.awaitTermination(2, TimeUnit.SECONDS);
            tui.stop();
            processes.close();
            terminal.close();
            queue.clear();
            server.stop(0);
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    private static String inputPane(KompileTui tui, VirtualTerminal screen) {
        StringBuilder rows = new StringBuilder();
        for (int row = tui.inputTop() - 1; row < tui.scrollBottom(); row++) {
            rows.append(screen.getRow(row)).append('\n');
        }
        return rows.toString();
    }

    private static VirtualTerminal screen(ByteArrayOutputStream output) {
        VirtualTerminal screen = new VirtualTerminal(HEIGHT, WIDTH);
        screen.feed(output.toString(StandardCharsets.UTF_8));
        return screen;
    }

    private static void type(LineDisciplineTerminal terminal, String keys) throws Exception {
        byte[] bytes = keys.getBytes(StandardCharsets.UTF_8);
        terminal.processInputBytes(bytes, 0, bytes.length);
    }

    private static void await(ByteArrayOutputStream output, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), () -> "timed out\n" + screen(output).screenDump());
    }
}
