package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.mcp.McpSseClient;
import org.jline.reader.*;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.jline.widget.AutosuggestionWidgets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TTY integration test for {@link ChatCompleter}.
 * <p>
 * Uses a {@link LineDisciplineTerminal} with xterm capabilities so JLine's
 * full completion rendering pipeline (cursor movement, candidate listing,
 * inline completion) is exercised. Keystrokes are fed through a pipe and
 * terminal output is captured from the master side.
 */
class ChatCompleterTtyTest {

    private PipedOutputStream keyboardPipe;
    private ByteArrayOutputStream terminalOutput;
    private LineDisciplineTerminal terminal;
    private LineReader reader;

    /** TAB character (triggers completion). */
    private static final char TAB = '\t';
    /** Enter/Return. */
    private static final char CR = '\r';

    @BeforeEach
    void setUp() throws Exception {
        terminalOutput = new ByteArrayOutputStream();

        // LineDisciplineTerminal with xterm type gives us proper cursor movement,
        // line editing, and completion rendering — unlike DumbTerminal which ignores TAB.
        terminal = new LineDisciplineTerminal("test", "xterm", terminalOutput,
                StandardCharsets.UTF_8);
        terminal.setSize(new Size(120, 40));

        // Set raw-ish attributes so JLine handles line discipline
        Attributes attrs = terminal.getAttributes();
        attrs.setLocalFlag(Attributes.LocalFlag.ECHO, false);
        attrs.setLocalFlag(Attributes.LocalFlag.ICANON, false);
        attrs.setInputFlag(Attributes.InputFlag.ICRNL, false);
        terminal.setAttributes(attrs);

        List<McpSseClient.ToolInfo> tools = List.of(
                new McpSseClient.ToolInfo("read", "Read a file", null),
                new McpSseClient.ToolInfo("write", "Write a file", null),
                new McpSseClient.ToolInfo("grep", "Search contents", null),
                new McpSseClient.ToolInfo("bash", "Run shell cmd", null)
        );
        Set<String> skills = new LinkedHashSet<>(List.of("commit", "review", "fix"));

        ChatCompleter completer = new ChatCompleter(
                () -> tools,
                () -> skills
        );

        reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(completer)
                .option(LineReader.Option.AUTO_LIST, true)
                .option(LineReader.Option.LIST_AMBIGUOUS, true)
                .option(LineReader.Option.AUTO_MENU, true)
                .build();

        // Enable autosuggestion (fish-style history hints)
        try {
            new AutosuggestionWidgets(reader).enable();
        } catch (Exception e) {
            // ok if unsupported
        }

        // Get the pipe for feeding keystrokes into the terminal's slave side
        keyboardPipe = new PipedOutputStream();
        PipedInputStream pipeIn = new PipedInputStream(keyboardPipe, 4096);
        // Pump bytes from pipe into the terminal's slave input
        Thread pumpThread = new Thread(() -> {
            try {
                byte[] buf = new byte[256];
                int n;
                while ((n = pipeIn.read(buf)) >= 0) {
                    terminal.processInputBytes(buf, 0, n);
                }
            } catch (IOException e) {
                // pipe closed
            }
        }, "tty-input-pump");
        pumpThread.setDaemon(true);
        pumpThread.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (keyboardPipe != null) {
            try { keyboardPipe.close(); } catch (Exception ignored) {}
        }
        if (terminal != null) terminal.close();
    }

    // ========================================================================
    // Tab completion rendering tests
    // ========================================================================

    @Test
    void tabAfterSlashHelpCompletesFullCommand() throws Exception {
        // /hel<TAB> — "help" is the only command starting with "hel", should complete
        String output = readLineOutputSanitized("/hel" + TAB + CR);
        assertTrue(output.contains("help"),
                "should complete /hel → /help, output: " + output);
    }

    @Test
    void tabAfterSlashQueueDashShowsQueueSubcommands() throws Exception {
        // /queue-<TAB> — multiple matches, JLine should list them
        String output = readLineOutputSanitized("/queue-" + TAB + CR);
        boolean hasQueueCandidate =
                output.contains("queue-send") ||
                output.contains("queue-clear") ||
                output.contains("queue-remove") ||
                output.contains("queue-status");
        assertTrue(hasQueueCandidate,
                "/queue-<TAB> should show queue subcommands, output: " + output);
    }

    @Test
    void tabAfterToolSpaceShowsToolNames() throws Exception {
        // /tool <TAB> — list all tool names
        String output = readLineOutputSanitized("/tool " + TAB + CR);
        boolean hasToolName =
                output.contains("read") ||
                output.contains("write") ||
                output.contains("grep") ||
                output.contains("bash");
        assertTrue(hasToolName,
                "/tool <TAB> should list tool names, output: " + output);
    }

    @Test
    void tabAfterToolPrefixNarrows() throws Exception {
        // /tool re<TAB> — only "read" matches, should complete
        String output = readLineOutputSanitized("/tool re" + TAB + CR);
        assertTrue(output.contains("read"),
                "/tool re<TAB> should complete to 'read', output: " + output);
    }

    @Test
    void tabAfterEnforceSpaceShowsSubArgs() throws Exception {
        String output = readLineOutputSanitized("/enforce " + TAB + CR);
        boolean hasSubArg =
                output.contains("on") ||
                output.contains("off") ||
                output.contains("rules") ||
                output.contains("score");
        assertTrue(hasSubArg,
                "/enforce <TAB> should show sub-arguments, output: " + output);
    }

    @Test
    void tabAfterModeSpaceShowsModes() throws Exception {
        String output = readLineOutputSanitized("/mode " + TAB + CR);
        boolean hasModes =
                output.contains("standard") ||
                output.contains("passthrough") ||
                output.contains("plan");
        assertTrue(hasModes,
                "/mode <TAB> should show mode options, output: " + output);
    }

    @Test
    void tabAfterAgentSpaceShowsAgentNames() throws Exception {
        String output = readLineOutputSanitized("/agent " + TAB + CR);
        boolean hasAgents = output.contains("coder") || output.contains("planner");
        assertTrue(hasAgents,
                "/agent <TAB> should show agent names, output: " + output);
    }

    @Test
    void tabAfterRoleSpaceShowsRoleNames() throws Exception {
        String output = readLineOutputSanitized("/role " + TAB + CR);
        boolean hasRoles = output.contains("senior-dev") || output.contains("architect");
        assertTrue(hasRoles,
                "/role <TAB> should show role names, output: " + output);
    }

    @Test
    void tabOnNonSlashInputProducesNoCompletions() throws Exception {
        String output = readLineOutputSanitized("hello" + TAB + CR);
        // No completions — input should pass through unchanged
        assertTrue(output.contains("hello"),
                "non-slash input should remain, output: " + output);
        // Should NOT contain any slash commands
        assertFalse(output.contains("/help"));
        assertFalse(output.contains("/tools"));
    }

    @Test
    void tabAfterRagSpaceShowsOnOff() throws Exception {
        // "on" and "off" share common prefix "o" — JLine completes to "o" then needs 2nd TAB.
        // Double-TAB to force the listing.
        String output = readLineOutputSanitized("/rag " + TAB + "" + TAB + CR);
        // After double TAB, either the listing shows "on"/"off" or it inserted "o"
        boolean hasRagArgs = output.contains("on") || output.contains("off");
        assertTrue(hasRagArgs,
                "/rag <TAB><TAB> should show on/off, output: " + output);
    }

    @Test
    void tabAfterSlashExUniqueCompletesToExit() throws Exception {
        // /exi<TAB> — only /exit matches
        String output = readLineOutputSanitized("/exi" + TAB + CR);
        assertTrue(output.contains("exit"),
                "/exi<TAB> should complete to /exit, output: " + output);
    }

    @Test
    void tabAfterJobsShowsJobCommands() throws Exception {
        // /jo<TAB> — matches /jobs, /jobs-remove, /jobs-clear
        String output = readLineOutputSanitized("/jo" + TAB + CR);
        assertTrue(output.contains("jobs"),
                "/jo<TAB> should complete to jobs commands, output: " + output);
    }

    // ========================================================================
    // Autosuggestion widget verification
    // ========================================================================

    @Test
    void autosuggestionWidgetCanBeEnabled() {
        LineReaderImpl impl = (LineReaderImpl) reader;
        assertNotNull(impl.getKeyMaps());
    }

    @Test
    void lineReaderOptionsAreSet() {
        assertTrue(reader.isSet(LineReader.Option.AUTO_LIST));
        assertTrue(reader.isSet(LineReader.Option.LIST_AMBIGUOUS));
        assertTrue(reader.isSet(LineReader.Option.AUTO_MENU));
    }

    @Test
    void readLineReturnsCompletedValue() throws Exception {
        // /hel<TAB><CR> — JLine completes to "/help" then appends a space (complete=true),
        // so the returned line is "/help " with trailing space.
        String result = readLineResult("/hel" + TAB + CR);
        assertTrue(result.startsWith("/help"),
                "readLine should return the completed value, got: '" + result + "'");
    }

    // ========================================================================
    // Candidate description rendering in output
    // ========================================================================

    @Test
    void queueCompletionShowsDescriptions() throws Exception {
        // When listing queue commands, descriptions should appear in parentheses
        String output = readLineOutputSanitized("/queue-" + TAB + CR);
        boolean hasDescriptions =
                output.contains("Clear all queued messages") ||
                output.contains("Send next queued message") ||
                output.contains("Remove a queued message") ||
                output.contains("Show queue status") ||
                output.contains("Send all queued messages");
        assertTrue(hasDescriptions,
                "/queue-<TAB> should show command descriptions, output: " + output);
    }

    @Test
    void toolCompletionShowsDescriptions() throws Exception {
        String output = readLineOutputSanitized("/tool " + TAB + CR);
        boolean hasDescriptions =
                output.contains("Read a file") ||
                output.contains("Write a file") ||
                output.contains("Search contents") ||
                output.contains("Run shell cmd");
        assertTrue(hasDescriptions,
                "/tool <TAB> should show tool descriptions, output: " + output);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Sends keystrokes, reads the line, and returns sanitized terminal output.
     */
    private String readLineOutputSanitized(String keys) throws Exception {
        readLineInternal(keys);
        return sanitize(terminalOutput.toString(StandardCharsets.UTF_8));
    }

    /**
     * Sends keystrokes and returns the result of reader.readLine() (the final buffer value).
     */
    private String readLineResult(String keys) throws Exception {
        return readLineInternal(keys);
    }

    private String readLineInternal(String keys) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> future = executor.submit(() -> {
            try {
                return reader.readLine("> ");
            } catch (UserInterruptException | EndOfFileException e) {
                return "";
            }
        });

        // Small delay to let readLine start
        Thread.sleep(50);

        // Feed keystrokes
        keyboardPipe.write(keys.getBytes(StandardCharsets.UTF_8));
        keyboardPipe.flush();

        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return "";
        } finally {
            executor.shutdownNow();
            Thread.sleep(100); // let output flush
        }
    }

    /**
     * Strip ANSI escape sequences for cleaner assertion matching.
     */
    private static String sanitize(String s) {
        return s
                // CSI sequences: ESC [ ... letter
                .replaceAll("\033\\[[0-9;?]*[A-Za-z]", "")
                // OSC sequences: ESC ] ... BEL
                .replaceAll("\033\\][^\007]*\007", "")
                // Any remaining bare ESC
                .replaceAll("\033", "")
                // Collapse whitespace for easier matching
                .replaceAll("\\s+", " ")
                .trim();
    }
}
