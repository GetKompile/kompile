package ai.kompile.app.services.agent;

import ai.kompile.cli.common.ChatWorkspaceStore;
import ai.kompile.cli.common.WebChatContext;
import ai.kompile.cli.common.chat.sources.ChatTurn;
import ai.kompile.cli.common.chat.sources.adapters.KompileAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Owns real CLI terminals, independently of browser chat turns and socket connections. */
@Service
public class CliTerminalService implements AutoCloseable {
    static final int REPLAY_LIMIT = 512 * 1024;
    private static final long RETENTION_MS = Duration.ofMinutes(30).toMillis();
    public record Launch(String workingDirectory, int cols, int rows) { }
    public record Transcript(String sessionId, List<ChatTurn> turns) { }
    public record View(String id, String sessionId, String workingDirectory, long pid,
                       String state, Integer exitCode, int cols, int rows) { }
    @FunctionalInterface interface Launcher { List<String> resolve() throws IOException; }
    @FunctionalInterface interface Starter {
        PtyProcess start(List<String> command, Path directory, Map<String, String> environment,
                         int cols, int rows) throws IOException;
    }
    private final ObjectMapper mapper;
    private final Launcher launcher;
    private final Starter starter;
    private final KompileAdapter transcripts;
    private final Map<String, Terminal> terminals = new ConcurrentHashMap<>();
    private final ExecutorService readers = Executors.newFixedThreadPool(32, r -> daemon(r, "cli-terminal-reader"));
    private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "cli-terminal-reaper"));
    private boolean closed;

    @org.springframework.beans.factory.annotation.Autowired
    public CliTerminalService(ObjectMapper mapper) {
        this(mapper, KompileCliHarnessClient::resolveLauncher, (command, dir, env, cols, rows) ->
                new PtyProcessBuilder(command.toArray(String[]::new)).setDirectory(dir.toString())
                        .setEnvironment(env).setRedirectErrorStream(true)
                        .setInitialColumns(cols).setInitialRows(rows).start());
    }
    CliTerminalService(ObjectMapper mapper, Launcher launcher, Starter starter) {
        this(mapper, launcher, starter, new KompileAdapter());
    }
    CliTerminalService(ObjectMapper mapper, Launcher launcher, Starter starter, KompileAdapter transcripts) {
        this.mapper = mapper;
        this.launcher = launcher;
        this.starter = starter;
        this.transcripts = transcripts;
        reaper.scheduleWithFixedDelay(this::reap, 1, 1, TimeUnit.MINUTES);
    }
    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    public synchronized View launch(String owner, Launch request) throws IOException {
        if (closed) throw new IllegalStateException("Terminal manager is stopping");
        if (request == null) throw new IllegalArgumentException("Terminal dimensions are required");
        dimensions(request.cols(), request.rows());
        if (terminals.size() >= 32 || terminals.values().stream().filter(t -> t.owner.equals(owner)).count() >= 8)
            throw new IllegalStateException("Terminal limit reached; stop and remove an existing terminal");
        Path directory = directory(request.workingDirectory());
        String sessionId = UUID.randomUUID().toString();
        List<String> command = command(launcher.resolve(), directory, sessionId);
        Map<String, String> env = new HashMap<>(System.getenv());
        WebChatContext.clearEnvironment(env);
        env.remove("CLAUDECODE");
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.put("COLUMNS", Integer.toString(request.cols()));
        env.put("LINES", Integer.toString(request.rows()));
        PtyProcess process = starter.start(command, directory, env, request.cols(), request.rows());
        Terminal terminal = new Terminal(owner, sessionId, directory, process, request.cols(), request.rows());
        terminals.put(terminal.id, terminal);
        try { readers.submit(() -> pump(terminal)); }
        catch (RuntimeException rejected) { terminals.remove(terminal.id); terminate(process); throw rejected; }
        return terminal.view();
    }

    static List<String> command(List<String> launcher, Path directory, String sessionId) {
        List<String> command = new ArrayList<>(launcher);
        command.addAll(List.of("chat", "--working-dir", directory.toString(), "--session-id", sessionId));
        if (WebChatContext.globalConfig()) command.add("--global-config");
        String workflow = WebChatContext.workflow();
        if (workflow != null) command.addAll(List.of("--workflow", workflow));
        return List.copyOf(command);
    }
    private static Path directory(String requested) throws IOException {
        Path context = WebChatContext.workingDirectory();
        if (context == null) throw new IllegalStateException("Launch with kompile chat --web first");
        if (requested == null || requested.isBlank()) return context;
        if (requested.length() > 4096) throw new IllegalArgumentException("Invalid terminal directory");
        if (WebChatContext.workspace()) return new ChatWorkspaceStore().resolveRegisteredDirectory(requested);
        Path directory = Path.of(requested).toRealPath();
        if (!context.equals(directory)) throw new IllegalArgumentException("Select the web chat launch folder");
        return directory;
    }
    public List<View> list(String owner) {
        return terminals.values().stream().filter(t -> t.owner.equals(owner))
                .sorted(Comparator.comparing(t -> t.id)).map(Terminal::view).toList();
    }
    /** Saved chat turns survive screen redraws and bounded PTY replay; never accept a client file/session path. */
    public Transcript transcript(String owner, String id) throws IOException {
        Terminal t = owned(owner, id);
        return new Transcript(t.sessionId, transcripts.readTurns(t.sessionId));
    }
    private Terminal owned(String owner, String id) {
        Terminal t = terminals.get(id);
        if (t == null || !t.owner.equals(owner)) throw new NoSuchElementException("Unknown terminal");
        return t;
    }
    public void attach(String owner, String id, WebSocketSession socket) throws IOException {
        Terminal t = owned(owner, id);
        synchronized (t) {
            if (t.socket != null && t.socket.isOpen()) t.socket.close(CloseStatus.NORMAL);
            t.socket = new ConcurrentWebSocketSessionDecorator(socket, 5000, REPLAY_LIMIT * 2);
            t.touched = System.currentTimeMillis();
            t.send(Map.of("type", "reset", "truncated", t.truncated));
            if (!t.replay.isEmpty()) t.send(Map.of("type", "output", "data", t.replay.toString()));
            t.send(Map.of("type", "status", "terminal", t.view()));
        }
    }
    public void detach(String owner, String id, String socketId) {
        Terminal t = terminals.get(id);
        if (t == null || !t.owner.equals(owner)) return;
        synchronized (t) {
            if (t.socket != null && t.socket.getId().equals(socketId)) {
                t.socket = null;
                t.touched = System.currentTimeMillis();
            }
        }
    }
    public void message(String owner, String id, String socketId, JsonNode message) throws IOException {
        Terminal t = owned(owner, id);
        synchronized (t) {
            if (t.socket == null || !t.socket.getId().equals(socketId)) throw new IllegalStateException("Terminal was attached elsewhere");
            t.touched = System.currentTimeMillis();
            switch (message.path("type").asText()) {
                case "input" -> {
                    if (!t.process.isAlive()) throw new IllegalStateException("Terminal has exited");
                    if (!message.path("data").isTextual() || message.path("data").asText().length() > 16384)
                        throw new IllegalArgumentException("Terminal input exceeds 16 KiB");
                    t.process.getOutputStream().write(message.path("data").asText().getBytes(StandardCharsets.UTF_8));
                    t.process.getOutputStream().flush();
                }
                case "binary-input" -> {
                    if (!t.process.isAlive()) throw new IllegalStateException("Terminal has exited");
                    if (!message.path("data").isTextual() || message.path("data").asText().length() > 21848)
                        throw new IllegalArgumentException("Terminal binary input exceeds 16 KiB");
                    byte[] bytes = Base64.getDecoder().decode(message.path("data").asText());
                    if (bytes.length > 16384) throw new IllegalArgumentException("Terminal binary input exceeds 16 KiB");
                    // Legacy mouse reports contain bytes above 127: UTF-8 encoding would corrupt their coordinates.
                    t.process.getOutputStream().write(bytes);
                    t.process.getOutputStream().flush();
                }
                case "resize" -> {
                    int cols = message.path("cols").asInt(), rows = message.path("rows").asInt();
                    dimensions(cols, rows);
                    if (!t.process.isAlive()) return; // Exited terminals remain attachable for read-only replay.
                    t.process.setWinSize(new WinSize(cols, rows));
                    t.cols = cols; t.rows = rows;
                }
                default -> throw new IllegalArgumentException("Unknown terminal message");
            }
        }
    }
    public void stop(String owner, String id) {
        Terminal t = owned(owner, id);
        synchronized (t) {
            t.stopped = true;
            t.touched = System.currentTimeMillis();
            terminate(t.process);
        }
    }
    public void remove(String owner, String id) { remove(owned(owner, id)); }
    private void remove(Terminal t) {
        synchronized (t) {
            if (!terminals.remove(t.id, t)) return;
            t.stopped = true;
            terminate(t.process);
            try { if (t.socket != null) t.socket.close(CloseStatus.NORMAL); } catch (IOException ignored) { }
        }
    }
    static void dimensions(int cols, int rows) {
        if (cols < 10 || cols > 500 || rows < 2 || rows > 200)
            throw new IllegalArgumentException("Terminal size must be 10–500 columns and 2–200 rows");
    }
    private void pump(Terminal t) {
        try (var reader = new InputStreamReader(t.process.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int length;
            while ((length = reader.read(buffer)) != -1) {
                String data = new String(buffer, 0, length);
                synchronized (t) {
                    t.replay.append(data);
                    if (t.replay.length() > REPLAY_LIMIT) {
                        t.replay.delete(0, t.replay.length() - REPLAY_LIMIT);
                        t.truncated = true;
                    }
                    t.send(Map.of("type", "output", "data", data));
                }
            }
        } catch (IOException failure) {
            synchronized (t) { t.send(Map.of("type", "error", "message", "Terminal stream closed")); }
        } finally {
            // PTY EOF can arrive while a child is still alive; never leave it orphaned.
            if (t.process.isAlive()) terminate(t.process);
            synchronized (t) {
                try { t.exitCode = t.process.waitFor(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                t.touched = System.currentTimeMillis();
                t.send(Map.of("type", "status", "terminal", t.view()));
            }
        }
    }
    static void terminate(Process process) {
        if (!process.isAlive()) return; // Never inspect a dead/reused PID's descendants.
        // Snapshot BEFORE killing the parent: descendants may reparent on root exit.
        List<ProcessHandle> children;
        try { children = new ArrayList<>(ProcessHandle.of(process.pid())
                .map(root -> root.descendants().toList()).orElse(List.of())); }
        catch (UnsupportedOperationException unsupported) { children = new ArrayList<>(); }
        Collections.reverse(children);
        children.forEach(ProcessHandle::destroy);
        process.destroy();
        try { process.waitFor(500, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) process.destroyForcibly();
    }
    private void reap() {
        long now = System.currentTimeMillis();
        for (Terminal t : terminals.values()) {
            synchronized (t) {
                if ((t.socket == null || !t.socket.isOpen()) && now - t.touched > RETENTION_MS) remove(t);
            }
        }
    }
    @Override @PreDestroy public synchronized void close() {
        closed = true;
        reaper.shutdownNow();
        for (Terminal t : List.copyOf(terminals.values())) remove(t);
        readers.shutdownNow();
    }
    private final class Terminal {
        final String id = UUID.randomUUID().toString(), owner, sessionId;
        final Path directory;
        final PtyProcess process;
        final StringBuilder replay = new StringBuilder();
        WebSocketSession socket;
        Integer exitCode;
        int cols, rows;
        boolean stopped, truncated;
        long touched = System.currentTimeMillis();
        Terminal(String owner, String sessionId, Path directory, PtyProcess process, int cols, int rows) {
            this.owner = owner; this.sessionId = sessionId; this.directory = directory; this.process = process;
            this.cols = cols; this.rows = rows;
        }
        synchronized View view() {
            return new View(id, sessionId, directory.toString(), process.pid(),
                    process.isAlive() ? "RUNNING" : stopped ? "STOPPED" : "EXITED", exitCode, cols, rows);
        }
        void send(Object event) {
            if (socket == null || !socket.isOpen()) return;
            try { socket.sendMessage(new TextMessage(mapper.writeValueAsString(event))); }
            catch (IOException | RuntimeException slowOrDisconnected) {
                try { socket.close(CloseStatus.SESSION_NOT_RELIABLE); } catch (IOException ignored) { }
                socket = null;
                touched = System.currentTimeMillis();
            }
        }
    }
}
