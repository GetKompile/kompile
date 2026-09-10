package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.config.ChatConfig;
import org.jline.reader.EndOfFileException;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOError;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Host ownership tests, not a substitute for production ChatRepl/TUI/provider smoke tests. */
class MultiChatSessionHostTest {
    private Terminal terminal() throws IOException {
        // Ownership tests need a terminal surface, not native PTY pump threads whose
        // EOF/close races can outlive the fixture and print asynchronous failures.
        Terminal terminal = new LineDisciplineTerminal("host-ownership-test", "xterm",
                new ByteArrayOutputStream(), java.nio.charset.StandardCharsets.UTF_8);
        terminal.setSize(new org.jline.terminal.Size(80, 24));
        return terminal;
    }

    @Test void switchesWithoutReinitializingAndOnlySelectedSessionReads() throws Exception {
        Fixture fixture = new Fixture(List.of(
                List.of("/session new", "first turn", "/quit"),
                List.of("second turn", "/session 1", "/quit")));
        try (Terminal terminal = terminal(); var host = new MultiChatSessionHost(terminal, fixture)) {
            host.run("first", false);
            assertEquals(2, fixture.created.size());
            assertEquals(List.of("first turn", "/quit"), fixture.created.get(0).submitted);
            assertEquals(List.of("second turn", "/quit"), fixture.created.get(1).submitted);
            assertEquals(1, fixture.created.get(0).starts);
            assertEquals(1, fixture.created.get(1).starts);
            assertEquals(1, fixture.created.get(0).closes);
            assertEquals(1, fixture.created.get(1).closes);
            assertEquals(0, fixture.attached);
        }
    }

    @Test void clearReplacesOnlySelectedSessionAndDoesNotReuseNumbers() throws Exception {
        Fixture fixture = new Fixture(List.of(
                List.of("/session new", "/quit"),
                List.of("/clear"),
                List.of("/sessions", "/session 2", "/session close")));
        try (Terminal terminal = terminal(); var host = new MultiChatSessionHost(terminal, fixture)) {
            host.run("first", true);
            assertEquals(List.of(true, false, false), fixture.resumes);
            assertEquals(3, fixture.created.size());
            FakeSession replacement = fixture.created.get(2);
            assertTrue(replacement.messages.stream().anyMatch(text -> text.startsWith("* 3  ")));
            assertTrue(replacement.messages.contains("No live session 2"));
            assertEquals(1, fixture.created.get(0).starts);
            assertTrue(fixture.created.stream().allMatch(session -> session.closes == 1));
        }
    }

    @Test void eofClosesSelectedSessionThenReturnsToSibling() throws Exception {
        Fixture fixture = new Fixture(List.of(List.of("/session new", "still here", "/quit"), List.of()));
        try (Terminal terminal = terminal(); var host = new MultiChatSessionHost(terminal, fixture)) {
            host.run("first", false);
            assertEquals(List.of("still here", "/quit"), fixture.created.get(0).submitted);
            assertTrue(fixture.created.stream().allMatch(session -> session.normal));
        }
    }

    @Test void failedConstructionRestoresExistingForeground() throws Exception {
        Fixture fixture = new Fixture(List.of(List.of()));
        try (Terminal terminal = terminal(); var host = new MultiChatSessionHost(terminal, fixture)) {
            host.create("first", false);
            fixture.failCreate = true;
            assertThrows(IOException.class, () -> host.create("second", false));
            assertEquals(1, fixture.attached);
            assertTrue(host.control("/sessions"));
            assertTrue(fixture.created.get(0).messages.contains("* 1  first"));
            assertEquals(0, fixture.created.get(0).closes);
        }
        assertEquals(1, fixture.created.get(0).closes);
    }

    @Test void undersizedCreateAndSelectKeepForegroundAttachedAndCanBeRetried() throws Exception {
        Fixture fixture = new Fixture(List.of(List.of(), List.of(), List.of()));
        try (Terminal terminal = terminal(); var host = new MultiChatSessionHost(terminal, fixture)) {
            terminal.setSize(new org.jline.terminal.Size(20, 12));
            host.create("first", false);
            host.create("second", false);
            FakeSession second = fixture.created.get(1);
            int detaches = second.detaches;
            for (var size : List.of(new org.jline.terminal.Size(19, 12),
                    new org.jline.terminal.Size(20, 11), new org.jline.terminal.Size(0, 0))) {
                terminal.setSize(size);
                assertThrows(IllegalStateException.class, () -> host.create("too small", false));
                assertThrows(IllegalStateException.class, () -> host.select(1));
                assertTrue(host.control("/session new"));
                assertTrue(host.control("/session 1"));
                assertTrue(second.attached);
                assertEquals(detaches, second.detaches, "size must be checked before detaching");
                assertEquals(2, fixture.created.size());
            }
            assertTrue(second.messages.stream().anyMatch(text -> text.contains("resize and retry")));
            terminal.setSize(new org.jline.terminal.Size(20, 12));
            host.select(1);
            assertTrue(fixture.created.get(0).attached);
            assertEquals(3, host.create("third", false));
        }
    }

    @Test void silentlyRejectedStartupIsClosedAndRestoresForeground() throws Exception {
        Fixture fixture = new Fixture(List.of(List.of(), List.of()));
        try (Terminal terminal = terminal(); var host = new MultiChatSessionHost(terminal, fixture)) {
            host.create("first", false);
            fixture.rejectStart = true;
            assertTrue(host.control("/session new"));
            assertEquals(1, fixture.created.get(1).closes);
            assertTrue(fixture.created.get(0).attached);
            assertEquals(1, fixture.attached);
            host.control("/sessions");
            assertTrue(fixture.created.get(0).messages.contains("* 1  first"));
            assertFalse(fixture.created.get(0).messages.stream().anyMatch(text -> text.startsWith("  2  ")));
        }
    }

    @Test void silentlyRejectedSelectionRestoresForegroundWithoutClosingHost() throws Exception {
        Fixture fixture = new Fixture(List.of(List.of(), List.of()));
        try (Terminal terminal = terminal(); var host = new MultiChatSessionHost(terminal, fixture)) {
            host.create("first", false);
            host.create("second", false);
            fixture.created.get(0).rejectAttach = true;
            assertTrue(host.control("/session 1"));
            assertTrue(fixture.created.get(1).attached);
            assertFalse(fixture.created.get(0).attached);
            host.control("/sessions");
            assertTrue(fixture.created.get(1).messages.contains("* 2  second"));
            assertTrue(fixture.created.get(1).messages.stream().anyMatch(text -> text.startsWith("Could not select session 1:")));
            assertTrue(fixture.created.stream().allMatch(session -> session.closes == 0));
            host.select(1);
            assertTrue(fixture.created.get(0).attached);
        }
    }

    @Test void hostLoopContinuesReadingAfterRecoverableSelectionFailure() throws Exception {
        Fixture fixture = new Fixture(List.of(
                List.of("/session new", "first turn", "/quit"),
                List.of("/session 1", "second turn", "/quit")));
        try (Terminal terminal = terminal(); var host = new MultiChatSessionHost(terminal, (id, resume) -> {
            MultiChatSessionHost.Session session = fixture.create(id, resume);
            if (fixture.created.size() == 2) fixture.created.get(0).rejectAttach = true;
            return session;
        })) {
            host.run("first", false);
            assertEquals(List.of("second turn", "/quit"), fixture.created.get(1).submitted);
            assertEquals(List.of("first turn", "/quit"), fixture.created.get(0).submitted);
            assertTrue(fixture.created.get(1).messages.stream().anyMatch(text -> text.startsWith("Could not select session 1:")));
            assertTrue(fixture.created.stream().allMatch(session -> session.closes == 1));
        }
    }

    @Test void partiallyAttachedSelectionIsDetachedBeforeRestoringForeground() throws Exception {
        Fixture fixture = new Fixture(List.of(List.of(), List.of()));
        try (Terminal terminal = terminal(); var host = new MultiChatSessionHost(terminal, fixture)) {
            host.create("first", false);
            host.create("second", false);
            fixture.created.get(0).failAfterAttach = true;
            assertTrue(host.control("/session 1"));
            assertFalse(fixture.created.get(0).attached);
            assertTrue(fixture.created.get(1).attached);
            assertEquals(1, fixture.attached);
        }
    }

    @Test void failedRestorationIsNotMistakenForRecoverableSelectionFailure() throws Exception {
        Fixture fixture = new Fixture(List.of(List.of(), List.of()));
        try (Terminal terminal = terminal(); var host = new MultiChatSessionHost(terminal, fixture)) {
            host.create("first", false);
            host.create("second", false);
            fixture.created.get(0).rejectAttach = true;
            fixture.created.get(1).rejectAttach = true;
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> host.control("/session 1"));
            assertEquals(1, failure.getSuppressed().length);
            assertEquals(0, fixture.attached);
        }
    }

    @Test void pendingEditorInputIsNotConsumedAsHostCommand() throws Exception {
        Fixture fixture = new Fixture(List.of(List.of("/session new", "/quit")));
        fixture.ownsFirstInput = true;
        try (Terminal terminal = terminal(); var host = new MultiChatSessionHost(terminal, fixture)) {
            host.run("first", false);
            assertEquals(1, fixture.created.size());
            assertEquals(List.of("/session new", "/quit"), fixture.created.get(0).submitted);
        }
    }

    @Test void terminalFailureFinalizesWithoutClaimingNormalExit() throws Exception {
        Fixture fixture = new Fixture(List.of(List.of("FAIL_IO")));
        try (Terminal terminal = terminal(); var host = new MultiChatSessionHost(terminal, fixture)) {
            IOException failure = assertThrows(IOException.class, () -> host.run("first", false));
            assertTrue(failure.getMessage().contains("Terminal I/O failed"));
            assertFalse(fixture.created.get(0).normal);
            assertSame(failure, fixture.created.get(0).failure);
        }
        assertEquals(1, fixture.created.get(0).closes);
    }

    @Test void optInAndUnsupportedOptionsAreValidatedBeforeStartup() {
        ChatCommand ordinary = new ChatCommand();
        assertFalse(new CommandLine(ordinary).parseArgs().hasMatchedOption("--multi-session"));
        for (String[] options : List.of(
                new String[]{"--multi-session", "--prompt", "hello"},
                new String[]{"--multi-session", "--url", "http://localhost:8081"},
                new String[]{"--multi-session", "--mode", "passthrough"},
                new String[]{"--multi-session", "--setup"},
                new String[]{"--multi-session", "--roles"})) {
            ChatCommand command = new ChatCommand();
            new CommandLine(command).parseArgs(options);
            assertNotNull(command.multiSessionOptionError());
        }
        ChatCommand direct = new ChatCommand();
        new CommandLine(direct).parseArgs("--multi-session", "--mode", "standard", "--provider", "codex");
        assertNull(direct.multiSessionOptionError());
        assertTrue(new CommandLine(direct).getUsageMessage().contains("--multi-session"));
    }

    @Test void directRoutesAndOrdinarySlashCommandsRemainAvailable() {
        ChatConfig direct = new ChatConfig("codex", null, "example-model", null);
        direct.setChatMode("standard");
        assertTrue(MultiChatSessionHost.supports(direct));
        ChatConfig passthrough = direct.copy();
        passthrough.setChatMode("passthrough");
        assertFalse(MultiChatSessionHost.supports(passthrough));
        ChatConfig server = direct.copy();
        server.setProvider("kompile");
        assertFalse(MultiChatSessionHost.supports(server));
        for (String command : List.of("/judge", "/activity", "/loop", "/loop-global", "/model x", "/queue", "/clear", "/help", "/mode plan")) {
            assertFalse(MultiChatSessionHost.unsupportedCommand(command), command);
        }
        for (String command : List.of("/passthrough claude", "/resume x", "/resume-all", "/menu", "/setup", "/restart", "/reset-all", "/mode passthrough")) {
            assertTrue(MultiChatSessionHost.unsupportedCommand(command), command);
        }
    }

    @Test void onlyExactJudgeWizardRoutesAreBlockedIncludingAliases() {
        for (String alias : List.of("/judge", "/enforcer", "/enforce")) {
            for (String route : List.of("init", "setup", "policy init", "policy setup",
                    "init ignored-argument", "policy setup ignored-argument")) {
                assertTrue(MultiChatSessionHost.unsupportedCommand(alias + " " + route), alias + " " + route);
            }
            for (String route : List.of("", "status", "on", "off", "config", "rules", "reload", "policy",
                    "policy show", "policy reload", "policy initialize", "chat init", "talk setup", "ask policy init",
                    "feedback init", "workflow init", "direction setup", "initialize", "setup-guide")) {
                assertFalse(MultiChatSessionHost.unsupportedCommand(alias + " " + route), alias + " " + route);
            }
        }
        assertTrue(MultiChatSessionHost.unsupportedCommand("  /JuDgE\tPoLiCy\tSeTuP  "));
        assertFalse(MultiChatSessionHost.unsupportedCommand("/judge-global init"));
    }

    @Test void hostSwitchUsesRealRetainedTuiReaderQueueAndActivityView() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        List<SurfaceSession> surfaces = new ArrayList<>();
        Fixture ownership = new Fixture(List.of());
        try (var terminal = new org.jline.terminal.impl.LineDisciplineTerminal(
                "host-retention-test", "xterm", output, java.nio.charset.StandardCharsets.UTF_8)) {
            terminal.setSize(new org.jline.terminal.Size(100, 30));
            try (var host = new MultiChatSessionHost(terminal, (id, resume) -> {
                SurfaceSession session = new SurfaceSession(ownership, id, terminal);
                surfaces.add(session);
                return session;
            })) {
                host.create("retained-first", false);
                SurfaceSession first = surfaces.get(0);
                first.reader.getBuffer().write("unfinished draft");
                first.queue.enqueue("queued work");
                first.tui.showActivityView("judge", "Judge", "judge discussion");
                host.create("retained-second", false);
                SurfaceSession second = surfaces.get(1);
                assertFalse(first.tui.isTerminalAttached());
                assertTrue(second.tui.isTerminalAttached());
                first.ui.capture((Runnable) () -> ChatCompleter.printAbove("completed while hidden")).run();
                assertEquals("unfinished draft", first.reader.getBuffer().toString());
                assertEquals(1, first.queue.getAll().size());
                host.select(1);
                assertTrue(String.join("\n", first.tui.getVisibleContentLines()).contains("judge discussion"));
                first.tui.showMainView();
                assertTrue(String.join("\n", first.tui.getVisibleContentLines()).contains("completed while hidden"));
                assertFalse(String.join("\n", second.tui.getVisibleContentLines()).contains("completed while hidden"));
                host.closeSelected();
                assertTrue(second.tui.isTerminalAttached());
                assertFalse(first.tui.isStarted());
            }
            assertEquals(0, ownership.attached);
            // The still-open test-owned terminal confirms no session closed the shared terminal.
            terminal.writer().println("host still owns terminal");
            terminal.writer().flush();
            assertTrue(output.toString(java.nio.charset.StandardCharsets.UTF_8).contains("host still owns terminal"));
        }
    }

    private static final class SurfaceSession extends FakeSession {
        final ChatUiSession ui = new ChatUiSession();
        final org.jline.reader.LineReader reader;
        final ai.kompile.cli.main.chat.tools.BackgroundProcessManager processes;
        final MessageQueue queue;
        final ai.kompile.cli.main.chat.tui.KompileTui tui;
        final Terminal terminal;
        SurfaceSession(Fixture fixture, String id, Terminal terminal) {
            super(fixture, id, List.of());
            this.terminal = terminal;
            reader = org.jline.reader.LineReaderBuilder.builder().terminal(terminal).build();
            processes = new ai.kompile.cli.main.chat.tools.BackgroundProcessManager("host-test-" + java.util.UUID.randomUUID());
            queue = new MessageQueue("host-test-" + java.util.UUID.randomUUID());
            try (var ignored = ui.bind()) {
                tui = new ai.kompile.cli.main.chat.tui.KompileTui(new BackgroundTaskManager(), processes, queue,
                        new ai.kompile.cli.main.chat.render.TerminalRenderer(true));
                ChatCompleter.setContentOutput(tui::recordInScrollRegion);
            }
        }
        @Override public void attach() {
            super.attach();
            try (var ignored = ui.bind()) {
                tui.attachLineReader(reader);
                tui.attachTerminal(terminal);
                ChatCompleter.setTerminalRef(reader, terminal);
            }
        }
        @Override public boolean isAttached() { return tui.isTerminalAttached(); }
        @Override public void detach() {
            super.detach();
            try (var ignored = ui.bind()) {
                ChatCompleter.detachTerminalRef(reader);
                tui.detachTerminal();
            }
        }
        @Override public void close() {
            super.close();
            try (var ignored = ui.bind()) { tui.stop(); }
            queue.clear();
            processes.close();
            ui.close();
        }
    }

    private static final class Fixture implements MultiChatSessionHost.SessionFactory {
        final Deque<List<String>> scripts;
        final List<FakeSession> created = new ArrayList<>();
        final List<Boolean> resumes = new ArrayList<>();
        int attached;
        boolean failCreate;
        boolean rejectStart;
        boolean ownsFirstInput;
        Fixture(List<List<String>> scripts) { this.scripts = new ArrayDeque<>(scripts); }
        @Override public MultiChatSessionHost.Session create(String id, boolean resume) throws IOException {
            if (failCreate) throw new IOException("injected constructor failure");
            FakeSession session = new FakeSession(this, id, scripts.removeFirst());
            session.rejectAttach = rejectStart;
            rejectStart = false;
            created.add(session);
            resumes.add(resume);
            return session;
        }
    }

    private static class FakeSession implements MultiChatSessionHost.Session {
        final Fixture fixture;
        final String id;
        final Deque<String> input;
        final List<String> submitted = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        int starts;
        int closes;
        int detaches;
        boolean attached;
        boolean rejectAttach;
        boolean failAfterAttach;
        boolean replace;
        boolean normal;
        IOException failure;
        FakeSession(Fixture fixture, String id, List<String> input) {
            this.fixture = fixture; this.id = id; this.input = new ArrayDeque<>(input);
        }
        @Override public String id() { return id; }
        @Override public void start() { starts++; attach(); }
        @Override public void attach() {
            assertFalse(attached);
            assertEquals(0, fixture.attached, "must detach the previous reader before attaching another");
            if (rejectAttach) { rejectAttach = false; return; }
            fixture.attached++; attached = true;
            if (failAfterAttach) {
                failAfterAttach = false;
                throw new IllegalStateException("injected failure after attachment");
            }
        }
        @Override public boolean isAttached() { return attached; }
        @Override public void detach() { detaches++; if (attached) { fixture.attached--; attached = false; } }
        @Override public String read() {
            assertTrue(attached);
            assertEquals(1, fixture.attached);
            if (input.isEmpty()) throw new EndOfFileException();
            String line = input.removeFirst();
            if (line.equals("FAIL_IO")) throw new IOError(new IOException("PTY disconnected"));
            return line;
        }
        @Override public boolean submit(String line) {
            submitted.add(line);
            if (fixture.ownsFirstInput) { fixture.ownsFirstInput = false; return true; }
            replace = line.equals("/clear");
            return !replace && !line.equals("/quit");
        }
        @Override public boolean ownsInput() { return fixture.ownsFirstInput; }
        @Override public boolean replacementRequested() { return replace; }
        @Override public void message(String text) { messages.add(text); }
        @Override public void finish(boolean normal, IOException failure) { this.normal = normal; this.failure = failure; }
        @Override public void close() { assertFalse(attached); closes++; }
    }
}
