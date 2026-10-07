package ai.kompile.app.services.agent;

import ai.kompile.cli.common.WebChatContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CliTerminalServiceTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private CliTerminalService service;
    private final Map<String, String> previous = new HashMap<>();
    @BeforeEach void context() throws Exception {
        property(WebChatContext.WORKING_DIRECTORY, directory.toRealPath().toString());
        property(WebChatContext.MODE, "single");
        property(WebChatContext.CONFIG_SCOPE, "project");
        property(WebChatContext.WORKFLOW, "");
    }
    void property(String name, String value) {
        if (!previous.containsKey(name)) previous.put(name, System.getProperty(name));
        System.setProperty(name, value);
    }
    @AfterEach void cleanup() {
        try { if (service != null) service.close(); }
        finally { previous.forEach((name, value) -> { if (value == null) System.clearProperty(name); else System.setProperty(name, value); }); }
    }
    @Test void commandUsesArgvAndNormalChatIdentity() {
        property(WebChatContext.CONFIG_SCOPE, "global");
        property(WebChatContext.WORKFLOW, "a team");
        assertEquals(List.of("/path with spaces/kompile", "chat", "--working-dir", "/project with spaces",
                "--session-id", "normal-uuid", "--global-config", "--workflow", "a team"),
                CliTerminalService.command(List.of("/path with spaces/kompile"), Path.of("/project with spaces"), "normal-uuid"));
    }
    @Test void validatesBeforeStartingAndDoesNotAcceptArbitraryFolders() throws Exception {
        CliTerminalService.Starter starter = mock(CliTerminalService.Starter.class);
        service = new CliTerminalService(mapper, () -> List.of("kompile"), starter);
        assertThrows(IllegalArgumentException.class, () -> service.launch("owner", new CliTerminalService.Launch(null, 0, 24)));
        assertThrows(IllegalArgumentException.class, () -> service.launch("owner", new CliTerminalService.Launch(directory.getParent().toString(), 80, 24)));
        verifyNoInteractions(starter);
    }
    @Test void realPtyInputResizeReplayOwnershipAndStop() throws Exception {
        service = realService("printf '\\033[32mREADY café\\033[0m\\n'; while IFS= read -r line; do "
                + "case \"$line\" in size) stty size;; quit) exit 7;; *) printf 'GOT:%s\\n' \"$line\";; esac; done");
        var terminal = service.launch("owner", new CliTerminalService.Launch(null, 80, 24));
        assertTrue(terminal.pid() > 0);
        assertTrue(service.list("other").isEmpty());
        assertThrows(NoSuchElementException.class, () -> service.stop("other", terminal.id()));
        Socket first = socket("first");
        assertThrows(NoSuchElementException.class, () -> service.attach("other", terminal.id(), first.socket));
        service.attach("owner", terminal.id(), first.socket);
        await(() -> first.output().contains("READY café"));
        service.message("owner", terminal.id(), "first", mapper.readTree("{\"type\":\"resize\",\"cols\":113,\"rows\":37}"));
        service.message("owner", terminal.id(), "first", mapper.readTree("{\"type\":\"input\",\"data\":\"size\\nhello\\n\"}"));
        await(() -> first.output().contains("37 113") && first.output().contains("GOT:hello"));
        assertEquals(113, service.list("owner").get(0).cols());
        service.detach("owner", terminal.id(), "first");
        assertEquals("RUNNING", service.list("owner").get(0).state());
        Socket second = socket("second");
        service.attach("owner", terminal.id(), second.socket);
        assertTrue(second.output().contains("READY café"));
        assertEquals("reset", mapper.readTree(second.messages.get(0)).path("type").asText());
        assertThrows(IllegalStateException.class, () -> service.message("owner", terminal.id(), "first", mapper.readTree("{\"type\":\"input\",\"data\":\"bad\"}")));
        service.message("owner", terminal.id(), "second", mapper.readTree("{\"type\":\"input\",\"data\":\"quit\\n\"}"));
        await(() -> service.list("owner").get(0).exitCode() != null);
        assertEquals(7, service.list("owner").get(0).exitCode());
        service.remove("owner", terminal.id());
        assertTrue(service.list("owner").isEmpty());
    }
    @Test void stoppingAndShutdownKillActualChildTree() throws Exception {
        service = realService("/bin/sh -c 'while IFS= read -r line; do :; done' < /dev/tty & "
                + "printf 'CHILD:%s\\n' \"$!\"; while IFS= read -r line; do :; done");
        var terminal = service.launch("owner", new CliTerminalService.Launch(null, 80, 24));
        Socket socket = socket("tree");
        service.attach("owner", terminal.id(), socket.socket);
        await(() -> socket.output().contains("CHILD:"));
        var matcher = java.util.regex.Pattern.compile("CHILD:([0-9]+)").matcher(socket.output());
        assertTrue(matcher.find());
        long child = Long.parseLong(matcher.group(1));
        assertTrue(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
        service.close();
        await(() -> !ProcessHandle.of(terminal.pid()).map(ProcessHandle::isAlive).orElse(false));
        await(() -> !ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
        assertTrue(service.list("owner").isEmpty());
    }
    @Test void cleanupNeverLooksUpADeadProcessesPotentiallyReusedPid() {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(false);
        CliTerminalService.terminate(process);
        verify(process, never()).pid();
        verify(process, never()).destroy();
        verify(process, never()).destroyForcibly();
    }
    @Test void boundedReplayRetainsOnlyTailAndReportsTrimming() throws Exception {
        PtyProcess process = mock(PtyProcess.class);
        String output = "x".repeat(CliTerminalService.REPLAY_LIMIT + 8192) + "THE-END";
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(output.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(process.pid()).thenReturn(999_999_999L);
        when(process.waitFor()).thenReturn(0);
        service = new CliTerminalService(mapper, () -> List.of("kompile"), (cmd, dir, env, cols, rows) -> process);
        var terminal = service.launch("owner", new CliTerminalService.Launch(null, 80, 24));
        await(() -> service.list("owner").get(0).exitCode() != null);
        Socket socket = socket("replay");
        service.attach("owner", terminal.id(), socket.socket);
        assertTrue(mapper.readTree(socket.messages.get(0)).path("truncated").asBoolean());
        assertEquals(CliTerminalService.REPLAY_LIMIT, socket.output().length());
        assertTrue(socket.output().endsWith("THE-END"));
        service.message("owner", terminal.id(), "replay", mapper.readTree("{\"type\":\"resize\",\"cols\":100,\"rows\":24}"));
        verify(process, never()).setWinSize(any());
        assertThrows(IllegalStateException.class, () -> service.message("owner", terminal.id(), "replay",
                mapper.readTree("{\"type\":\"input\",\"data\":\"closed\"}")));
    }
    @Test void forwardsBinaryMouseCoordinatesWithoutUtf8RecodingAndRejectsInvalidFrames() throws Exception {
        PtyProcess process = mock(PtyProcess.class);
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.getOutputStream()).thenReturn(input);
        service = new CliTerminalService(mapper, () -> List.of("kompile"), (cmd, dir, env, cols, rows) -> process);
        var terminal = service.launch("owner", new CliTerminalService.Launch(null, 80, 24));
        await(() -> service.list("owner").get(0).exitCode() != null);
        Socket socket = socket("mouse");
        service.attach("owner", terminal.id(), socket.socket);
        when(process.isAlive()).thenReturn(true);
        try {
            byte[] report = new byte[]{27, '[', 'M', 96, (byte) 200, (byte) 150};
            var message = mapper.createObjectNode().put("type", "binary-input").put("data", Base64.getEncoder().encodeToString(report));
            service.message("owner", terminal.id(), "mouse", message);
            assertArrayEquals(report, input.toByteArray());
            assertThrows(NoSuchElementException.class, () -> service.message("other", terminal.id(), "mouse", message));
            assertThrows(IllegalStateException.class, () -> service.message("owner", terminal.id(), "stale", message));
            assertThrows(IllegalArgumentException.class, () -> service.message("owner", terminal.id(), "mouse", message.deepCopy().put("data", "not base64!")));
            assertThrows(IllegalArgumentException.class, () -> service.message("owner", terminal.id(), "mouse",
                    message.deepCopy().put("data", Base64.getEncoder().encodeToString(new byte[16385]))));
            assertArrayEquals(report, input.toByteArray());
        } finally { when(process.isAlive()).thenReturn(false); }
        assertThrows(IllegalStateException.class, () -> service.message("owner", terminal.id(), "mouse",
                mapper.createObjectNode().put("type", "binary-input").put("data", "AA==")));
    }
    @Test void readsOwnedSavedTranscriptBeyondTheTerminalReplayLimit() throws Exception {
        PtyProcess process = mock(PtyProcess.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        var adapter = new ai.kompile.cli.common.chat.sources.adapters.KompileAdapter(directory);
        service = new CliTerminalService(mapper, () -> List.of("kompile"), (cmd, dir, env, cols, rows) -> process, adapter);
        var terminal = service.launch("owner", new CliTerminalService.Launch(null, 80, 24));
        assertTrue(service.transcript("owner", terminal.id()).turns().isEmpty());
        String answer = "Earlier café\n" + "x".repeat(CliTerminalService.REPLAY_LIMIT + 10);
        try (var writer = new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(directory.resolve(terminal.sessionId() + ".txt")))) {
            ai.kompile.cli.common.chat.sources.KompileTranscriptFormat.writeTurn(writer, "user", "first question");
            ai.kompile.cli.common.chat.sources.KompileTranscriptFormat.writeTurn(writer, "assistant", answer);
        }
        var transcript = service.transcript("owner", terminal.id());
        assertEquals(terminal.sessionId(), transcript.sessionId());
        assertEquals(2, transcript.turns().size());
        assertEquals("first question", transcript.turns().get(0).content());
        assertEquals(answer, transcript.turns().get(1).content());
        assertThrows(NoSuchElementException.class, () -> service.transcript("other", terminal.id()));
        assertThrows(NoSuchElementException.class, () -> service.transcript("owner", "../another-session"));
    }
    private CliTerminalService realService(String script) {
        return new CliTerminalService(mapper, () -> List.of("kompile"), (command, dir, env, cols, rows) -> {
            assertEquals("chat", command.get(1));
            assertFalse(command.contains("--web"));
            assertFalse(command.contains("--output-format"));
            assertFalse(env.containsKey(WebChatContext.ENV_WORKING_DIRECTORY));
            return new PtyProcessBuilder(new String[]{"/bin/sh", "-c", script}).setDirectory(dir.toString())
                    .setEnvironment(env).setInitialColumns(cols).setInitialRows(rows).setRedirectErrorStream(true).start();
        });
    }
    private record Socket(WebSocketSession socket, List<String> messages) {
        String output() {
            ObjectMapper mapper = new ObjectMapper();
            StringBuilder text = new StringBuilder();
            for (String message : messages) try {
                var node = mapper.readTree(message);
                if (node.path("type").asText().equals("output")) text.append(node.path("data").asText());
            } catch (Exception invalid) { throw new AssertionError(invalid); }
            return text.toString();
        }
    }
    private Socket socket(String id) throws Exception {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(id);
        when(socket.isOpen()).thenReturn(true);
        List<String> messages = new CopyOnWriteArrayList<>();
        doAnswer(call -> { messages.add(((TextMessage) call.getArgument(0)).getPayload()); return null; }).when(socket).sendMessage(any());
        return new Socket(socket, messages);
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Terminal condition did not arrive within 10 seconds");
    }
}
