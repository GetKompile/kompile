/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.serve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Persistent server manager for multi-session agent coordination.
 * Manages a long-running server process that multiple CLI clients can connect to.
 * Sessions persist across client disconnections and server restarts.
 * Inspired by jcode's server/client architecture.
 */
public class PersistentServerManager {

    private final Path stateDir;
    private final ObjectMapper mapper;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final String serverId;
    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private final ExecutorService clientExecutor;
    private static final int DEFAULT_PORT = 9876;
    private static final long IDLE_TIMEOUT_MS = 300_000; // 5 min
    private volatile long lastClientActivity = System.currentTimeMillis();

    public PersistentServerManager() {
        this.stateDir = Paths.get(System.getProperty("user.home"), ".kompile", "server");
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.serverId = generateServerId();
        this.clientExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "server-client-handler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start the persistent server.
     */
    public void start() throws IOException {
        start(DEFAULT_PORT);
    }

    public void start(int port) throws IOException {
        Files.createDirectories(stateDir);

        // Load persisted sessions
        loadSessions();

        // Write server registry
        writeServerRegistry(port);

        // Start socket server
        serverSocket = new ServerSocket(port);
        running = true;

        System.err.println("[Server] Started " + serverId + " on port " + port);
        System.err.println("[Server] " + sessions.size() + " persisted sessions loaded");

        // Accept client connections
        clientExecutor.submit(() -> {
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    lastClientActivity = System.currentTimeMillis();
                    clientExecutor.submit(() -> handleClient(client));
                } catch (IOException e) {
                    if (running) System.err.println("[Server] Accept error: " + e.getMessage());
                }
            }
        });

        // Idle timeout check
        ScheduledExecutorService idleCheck = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "server-idle-check");
            t.setDaemon(true);
            return t;
        });
        idleCheck.scheduleAtFixedRate(() -> {
            if (System.currentTimeMillis() - lastClientActivity > IDLE_TIMEOUT_MS) {
                System.err.println("[Server] Idle timeout -- shutting down");
                shutdown();
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * Stop the server and persist all session state.
     */
    public void shutdown() {
        running = false;
        persistSessions();
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        clientExecutor.shutdownNow();
        removeServerRegistry();
        System.err.println("[Server] Shutdown complete. " + sessions.size() + " sessions persisted.");
    }

    /**
     * Create or resume a session.
     */
    public SessionState getOrCreateSession(String sessionName) {
        return sessions.computeIfAbsent(sessionName, name -> {
            SessionState state = new SessionState();
            state.name = name;
            state.id = UUID.randomUUID().toString().substring(0, 8);
            state.createdAt = Instant.now();
            state.lastActiveAt = Instant.now();
            state.status = "active";
            return state;
        });
    }

    /**
     * List all sessions.
     */
    public List<SessionState> listSessions() {
        return new ArrayList<>(sessions.values());
    }

    /**
     * Get server status.
     */
    public String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Kompile Server ").append(serverId).append(" ===\n");
        sb.append("Running: ").append(running).append("\n");
        sb.append("Sessions: ").append(sessions.size()).append("\n");
        for (SessionState s : sessions.values()) {
            sb.append(String.format("  %s [%s] %s -- last active: %s\n",
                s.name, s.id, s.status, s.lastActiveAt));
        }
        long idleMs = System.currentTimeMillis() - lastClientActivity;
        sb.append("Idle: ").append(idleMs / 1000).append("s / ").append(IDLE_TIMEOUT_MS / 1000).append("s timeout\n");
        return sb.toString();
    }

    /**
     * Check if a server is already running.
     */
    public static ServerInfo findRunningServer() {
        Path registryFile = Paths.get(System.getProperty("user.home"), ".kompile", "server", "server.json");
        if (!Files.exists(registryFile)) return null;
        try {
            ObjectMapper om = new ObjectMapper();
            om.registerModule(new JavaTimeModule());
            return om.readValue(registryFile.toFile(), ServerInfo.class);
        } catch (IOException e) {
            return null;
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private void handleClient(Socket client) {
        try (var in = new BufferedReader(new InputStreamReader(client.getInputStream()));
             var out = new PrintWriter(client.getOutputStream(), true)) {

            String line;
            while ((line = in.readLine()) != null) {
                lastClientActivity = System.currentTimeMillis();
                String response = processCommand(line.trim());
                out.println(response);
                out.println("---END---");
            }
        } catch (IOException e) {
            // Client disconnected
        }
    }

    private String processCommand(String command) {
        String[] parts = command.split(" ", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "status": return status();
            case "list": {
                StringBuilder sb = new StringBuilder();
                for (SessionState s : sessions.values()) {
                    sb.append(s.name).append(" [").append(s.status).append("] ").append(s.id).append("\n");
                }
                return sb.length() == 0 ? "(no sessions)" : sb.toString();
            }
            case "session": {
                if (arg.isEmpty()) return "Usage: session <name>";
                SessionState s = getOrCreateSession(arg);
                s.lastActiveAt = Instant.now();
                return "Session: " + s.name + " [" + s.id + "] " + s.status;
            }
            case "close": {
                if (arg.isEmpty()) return "Usage: close <name>";
                SessionState s = sessions.get(arg);
                if (s != null) { s.status = "closed"; return "Closed: " + arg; }
                return "Session not found: " + arg;
            }
            default: return "Unknown command: " + cmd + ". Commands: status, list, session <name>, close <name>";
        }
    }

    private void loadSessions() {
        Path sessionsFile = stateDir.resolve("sessions.json");
        if (!Files.exists(sessionsFile)) return;
        try {
            SessionState[] loaded = mapper.readValue(sessionsFile.toFile(), SessionState[].class);
            for (SessionState s : loaded) {
                sessions.put(s.name, s);
            }
        } catch (IOException e) {
            System.err.println("[Server] Warning: Could not load sessions: " + e.getMessage());
        }
    }

    private void persistSessions() {
        try {
            Files.createDirectories(stateDir);
            mapper.writeValue(stateDir.resolve("sessions.json").toFile(), sessions.values().toArray());
        } catch (IOException e) {
            System.err.println("[Server] Warning: Could not persist sessions: " + e.getMessage());
        }
    }

    private void writeServerRegistry(int port) {
        try {
            ServerInfo info = new ServerInfo();
            info.serverId = serverId;
            info.port = port;
            info.pid = ProcessHandle.current().pid();
            info.startedAt = Instant.now();
            mapper.writeValue(stateDir.resolve("server.json").toFile(), info);
        } catch (IOException e) {
            System.err.println("[Server] Warning: Could not write registry: " + e.getMessage());
        }
    }

    private void removeServerRegistry() {
        try { Files.deleteIfExists(stateDir.resolve("server.json")); } catch (IOException ignored) {}
    }

    private static String generateServerId() {
        String[] adjectives = {"blazing", "swift", "bright", "steady", "rising", "deep", "keen", "bold"};
        String adj = adjectives[new Random().nextInt(adjectives.length)];
        return adj + "-" + Long.toHexString(System.currentTimeMillis() % 0xFFFF);
    }

    // ── Data Types ────────────────────────────────────────────────────────

    public static class SessionState {
        public String name;
        public String id;
        public Instant createdAt;
        public Instant lastActiveAt;
        public String status; // active, idle, closed
        public Map<String, Object> metadata = new HashMap<>();

        public SessionState() {} // Jackson
    }

    public static class ServerInfo {
        public String serverId;
        public int port;
        public long pid;
        public Instant startedAt;

        public ServerInfo() {} // Jackson
    }
}
