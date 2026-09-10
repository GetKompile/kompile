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

package ai.kompile.cli.main.chat.agent;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A persistent CLI agent subprocess communicating via the stream-json protocol.
 * <p>
 * The process starts once, receives an init control_request, then accepts user messages
 * as NDJSON on stdin and returns stream-json events on stdout. Multi-turn within a
 * single persistent process — no restart overhead.
 * <p>
 * This is the shared infrastructure used by:
 * <ul>
 *   <li>Task delegation (subagent spawning for task tool)</li>
 *   <li>Judge LLM (enforcer evaluation)</li>
 *   <li>Any future use case needing a live agent subprocess</li>
 * </ul>
 * <p>
 * Protocol: Claude Code stream-json — {@code --input-format stream-json --output-format stream-json}
 * <pre>
 * stdin:  {"type":"control_request","request":{"subtype":"initialize",...}}
 * stdin:  {"type":"user","message":{"role":"user","content":[{"type":"text","text":"..."}]}}
 * stdout: {"type":"system",...}           // init ack
 * stdout: {"type":"assistant",...}        // text content
 * stdout: {"type":"result",...}           // turn complete
 * </pre>
 */
public class PersistentAgentProcess implements AutoCloseable {

    /**
     * Default per-turn timeout: 600 s (10 min) to match the outer multi_task
     * future.get(10, TimeUnit.MINUTES) gate and allow real agentic work to
     * complete before the timeout fires.
     */
    private static final int DEFAULT_TURN_TIMEOUT_SECONDS = 600;

    private final String agentBinary;
    private final Path workDir;
    private final String systemPrompt;
    private final String model;
    private final boolean skipPermissions;
    private final List<String> extraArgs;
    private final List<String> clearEnvPrefixes;

    private volatile Process process;
    private volatile OutputStream stdin;
    private volatile String sessionId;
    private volatile boolean ready;
    /** Bounded diagnostics captured from stderr/error events for visible judge failures. */
    private final StringBuilder diagnostics = new StringBuilder();
    private volatile String failureReason;
    private static final int MAX_DIAGNOSTICS_CHARS = 4_000;

    private final ReentrantLock sendLock = new ReentrantLock();
    private final AtomicReference<StringBuilder> currentTurnOutput = new AtomicReference<>();
    private volatile CountDownLatch turnComplete;
    private volatile CountDownLatch processReady;

    private PersistentAgentProcess(Builder builder) {
        this.agentBinary = builder.agentBinary;
        this.workDir = builder.workDir;
        this.systemPrompt = builder.systemPrompt;
        this.model = builder.model;
        this.skipPermissions = builder.skipPermissions;
        this.extraArgs = builder.extraArgs != null ? builder.extraArgs : List.of();
        this.clearEnvPrefixes = builder.clearEnvPrefixes != null ? builder.clearEnvPrefixes : List.of();
    }

    /**
     * Start the subprocess and wait for it to become ready.
     *
     * @param timeoutSeconds max seconds to wait for the process to initialize
     * @throws IOException if the process fails to start
     */
    public synchronized void start(int timeoutSeconds) throws IOException, InterruptedException {
        if (process != null && process.isAlive() && ready) return;

        failureReason = null;
        synchronized (diagnostics) {
            diagnostics.setLength(0);
        }
        List<String> cmd = buildCommand();

        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workDir != null) pb.directory(workDir.toFile());
        pb.redirectErrorStream(false);
        inheritEnv(pb.environment());
        // Drop inherited env by prefix (ProcessBuilder pre-populates the FULL parent
        // env). E.g. the enforcer judge strips KOMPILE_ENFORCER_* so its subprocess
        // tree does not treat itself as an enforced session and re-spawn judges.
        if (!clearEnvPrefixes.isEmpty()) {
            pb.environment().keySet().removeIf(
                    key -> clearEnvPrefixes.stream().anyMatch(key::startsWith));
        }

        processReady = new CountDownLatch(1);
        process = pb.start();
        stdin = process.getOutputStream();

        // Send stream-json initialization control_request
        try {
            sendInitMessage();
        } catch (IOException failure) {
            failureReason = "Could not initialize judge agent: " + failure.getMessage();
            close();
            throw failure;
        }

        // Drain stderr, but retain a bounded diagnostic excerpt. The previous null sink made
        // quota/auth/disabled-agent failures indistinguishable from a healthy idle process.
        Thread errDrain = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (handleDiagnostic(line)) {
                        return;
                    }
                }
            } catch (IOException ignored) {}
        }, "agent-proc-err-drain");
        errDrain.setDaemon(true);
        errDrain.start();

        // Start output reader
        Thread outputReader = new Thread(this::readOutputLoop, "agent-proc-reader");
        outputReader.setDaemon(true);
        outputReader.start();

        // Wait for ready signal. A disabled/exhausted CLI may leave a shell process alive
        // without ever producing the protocol handshake; that is a failed judge, not "working".
        boolean gotReady = processReady.await(timeoutSeconds, TimeUnit.SECONDS);
        ready = gotReady;
        if (!gotReady) {
            String detail = diagnosticText();
            failureReason = "Judge agent did not become ready within " + timeoutSeconds + "s"
                    + (detail.isBlank() ? "" : ": " + detail);
            close();
            throw new IOException(failureReason);
        }
    }

    /**
     * Start with default 30s timeout.
     */
    public void start() throws IOException, InterruptedException {
        start(30);
    }

    /**
     * Thrown when an agent turn times out but has produced some partial output.
     * Carries the partial text so callers can surface it as INCOMPLETE rather
     * than silently treating it as a successful completion.
     */
    public static final class TimedOutException extends IOException {
        private final String partialOutput;

        public TimedOutException(String partialOutput, int timeoutSeconds) {
            this(partialOutput, timeoutSeconds, "");
        }

        public TimedOutException(String partialOutput, int timeoutSeconds, String diagnostic) {
            super("Agent turn timed out after " + timeoutSeconds + "s with partial output ("
                    + partialOutput.length() + " chars)"
                    + (diagnostic == null || diagnostic.isBlank() ? "" : ": " + diagnostic));
            this.partialOutput = partialOutput;
        }

        /** The partial text accumulated before the timeout fired. Never null, may be empty. */
        public String getPartialOutput() {
            return partialOutput;
        }
    }

    /**
     * Send a message and wait for the agent's response.
     *
     * @param message        the user message to send
     * @param timeoutSeconds max seconds to wait for a complete turn
     * @return the agent's text response
     * @throws IOException        if communication fails
     * @throws TimedOutException  if the turn timed out (even with partial output — callers
     *                            MUST distinguish this from a true completion)
     */
    public String sendMessage(String message, int timeoutSeconds) throws IOException, InterruptedException {
        sendLock.lock();
        try {
            if (process == null || !process.isAlive()) {
                throw new IOException("Agent process is not running");
            }

            StringBuilder turnOutput = new StringBuilder();
            currentTurnOutput.set(turnOutput);
            turnComplete = new CountDownLatch(1);

            writeUserMessage(message);

            boolean completed = turnComplete.await(timeoutSeconds, TimeUnit.SECONDS);
            String result = turnOutput.toString().trim();

            if (!completed) {
                // A timed-out judge must not remain pooled and appear healthy. Tear it down
                // before reporting the timeout so the next request can explicitly restart it.
                String detail = diagnosticText();
                failureReason = "Agent turn timed out after " + timeoutSeconds + "s"
                        + (detail.isBlank() ? "" : ": " + detail);
                close();
                throw new TimedOutException(result, timeoutSeconds, detail);
            }
            if (result.isBlank() && (process == null || !process.isAlive())) {
                String detail = diagnosticText();
                if (failureReason == null || failureReason.isBlank()) {
                    failureReason = "Agent process exited before returning a response"
                            + (detail.isBlank() ? "" : ": " + detail);
                }
                throw new IOException(failureReason);
            }
            return result;
        } finally {
            sendLock.unlock();
        }
    }

    /**
     * Send a message with default timeout.
     */
    public String sendMessage(String message) throws IOException, InterruptedException {
        return sendMessage(message, DEFAULT_TURN_TIMEOUT_SECONDS);
    }

    /**
     * Check if the subprocess is alive and ready.
     */
    public boolean isAlive() {
        return process != null && process.isAlive() && ready;
    }

    /**
     * Get the session ID assigned by the agent (if any).
     */
    public String getSessionId() {
        return sessionId;
    }

    @Override
    public synchronized void close() {
        CountDownLatch readyLatch = processReady;
        if (readyLatch != null) readyLatch.countDown();
        CountDownLatch turnLatch = turnComplete;
        if (turnLatch != null) turnLatch.countDown();
        Process p = process;
        if (p != null) {
            try { if (stdin != null) stdin.close(); } catch (IOException ignored) {}
            p.destroyForcibly();
            try { p.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        process = null;
        stdin = null;
        sessionId = null;
        ready = false;
    }

    /** Last bounded stderr/protocol diagnostic, suitable for a user-facing failure. */
    public String failureReason() {
        String explicit = failureReason;
        return explicit != null && !explicit.isBlank() ? explicit : diagnosticText();
    }

    private void recordDiagnostic(String line) {
        if (line == null || line.isBlank()) return;
        synchronized (diagnostics) {
            if (diagnostics.length() >= MAX_DIAGNOSTICS_CHARS) return;
            if (diagnostics.length() > 0) diagnostics.append(" | ");
            int remaining = MAX_DIAGNOSTICS_CHARS - diagnostics.length();
            diagnostics.append(line, 0, Math.min(line.length(), remaining));
        }
    }

    private String diagnosticText() {
        synchronized (diagnostics) {
            return diagnostics.toString().trim();
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(agentBinary);

        // Stream-json protocol for persistent stdin/stdout communication
        cmd.add("-p");
        cmd.add("--input-format");
        cmd.add("stream-json");
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--verbose");

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            cmd.add("--system-prompt");
            cmd.add(systemPrompt);
        }

        if (model != null && !model.isBlank()) {
            cmd.add("--model");
            cmd.add(model);
        }

        if (skipPermissions) {
            cmd.add("--dangerously-skip-permissions");
        }

        cmd.addAll(extraArgs);
        return cmd;
    }

    private void sendInitMessage() throws IOException {
        String init = "{\"type\":\"control_request\",\"request\":{\"subtype\":\"initialize\",\"request_id\":\"req_init\",\"hooks\":{},\"sdk_mcp_servers\":[]}}\n";
        stdin.write(init.getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    private void writeUserMessage(String message) throws IOException {
        String escaped = escapeJsonString(message);
        String json = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"" + escaped + "\"}]}}\n";
        try {
            stdin.write(json.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    private void readOutputLoop() {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                // Preserve provider errors (including quota/auth/disabled messages) even when
                // they arrive on stdout as a non-protocol line or an error JSON event.
                if (handleDiagnostic(trimmed)) {
                    return;
                }
                if (!trimmed.startsWith("{")) continue;

                // Capture session ID
                if (sessionId == null && trimmed.contains("\"session_id\"")) {
                    String sid = extractFieldValue(trimmed, "session_id");
                    if (sid != null && !sid.isBlank()) sessionId = sid;
                }

                // Signal ready on system/control_response events
                if (trimmed.contains("\"type\":\"system\"") || trimmed.contains("\"type\":\"control_response\"")) {
                    CountDownLatch ready = processReady;
                    if (ready != null && ready.getCount() > 0) {
                        ready.countDown();
                    }
                }

                // Extract text from assistant message events
                if (trimmed.contains("\"type\":\"assistant\"")) {
                    String text = extractAssistantText(trimmed);
                    if (text != null) {
                        StringBuilder current = currentTurnOutput.get();
                        if (current != null) current.append(text);
                    }
                }

                // Content block delta — streaming text chunks
                if (trimmed.contains("\"type\":\"content_block_delta\"")) {
                    String delta = extractDeltaText(trimmed);
                    if (delta != null) {
                        StringBuilder current = currentTurnOutput.get();
                        if (current != null) current.append(delta);
                    }
                }

                // Result event = turn complete
                if (trimmed.contains("\"type\":\"result\"")) {
                    String resultText = extractFieldValue(trimmed, "result");
                    if (resultText != null) {
                        StringBuilder current = currentTurnOutput.get();
                        if (current != null && current.length() == 0) {
                            current.append(resultText);
                        }
                    }
                    CountDownLatch latch = turnComplete;
                    if (latch != null) latch.countDown();
                }
            }
        } catch (IOException ignored) {
        } finally {
            CountDownLatch latch = turnComplete;
            if (latch != null) latch.countDown();
            CountDownLatch rdy = processReady;
            if (rdy != null) rdy.countDown();
        }
    }

    private boolean handleDiagnostic(String line) {
        if (line == null || line.isBlank()) return false;
        String trimmed = line.trim();
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        boolean diagnostic = !trimmed.startsWith("{") || lower.contains("error")
                || lower.contains("quota") || lower.contains("limit")
                || lower.contains("not authenticated") || lower.contains("disabled");
        if (!diagnostic) return false;
        recordDiagnostic(trimmed);
        if (!isFatalDiagnostic(lower)) return false;
        failureReason = "Judge agent reported an availability failure: " + trimmed;
        close();
        return true;
    }

    private boolean isFatalDiagnostic(String lower) {
        if (lower == null || lower.isBlank()) return false;
        if (lower.startsWith("{") && (lower.contains("\"type\":\"error\"")
                || lower.contains("\"is_error\":true"))) {
            return true;
        }
        return lower.contains("usage limit") || lower.contains("weekly limit")
                || lower.contains("monthly limit")
                || (lower.contains("quota") && (lower.contains("exceed")
                        || lower.contains("limit") || lower.contains("hit")
                        || lower.contains("reach") || lower.contains("exhaust")
                        || lower.contains("deplet") || lower.contains("zero")))
                || lower.contains("quota has been exceeded") || lower.contains("rate limit")
                || lower.contains("limit reached") || lower.contains("hit your limit")
                || lower.contains("reached your limit") || lower.contains("exceeded your limit")
                || lower.contains("out of credits") || lower.contains("credits exhausted")
                || lower.contains("credit limit") || lower.contains("insufficient credits")
                || lower.contains("too many requests") || lower.contains("resource exhausted")
                || lower.contains("429") || lower.contains("not authenticated")
                || (lower.contains("401") && lower.contains("unauthorized"))
                || lower.contains("error: closed") || lower.contains("error closed")
                || lower.contains("error] closed")
                || lower.contains("not logged in") || lower.contains("authentication required")
                || lower.contains("login required") || lower.contains("agent disabled")
                || lower.contains("command not found");
    }

    private String extractAssistantText(String json) {
        int textIdx = json.indexOf("\"type\":\"text\"");
        if (textIdx < 0) return null;
        int searchFrom = textIdx + 13;
        int textFieldIdx = json.indexOf("\"text\":", searchFrom);
        if (textFieldIdx < 0) return null;
        int startQuote = json.indexOf('"', textFieldIdx + 7);
        if (startQuote < 0) return null;
        return extractJsonString(json, startQuote);
    }

    private String extractDeltaText(String json) {
        int deltaIdx = json.indexOf("\"delta\"");
        if (deltaIdx < 0) return null;
        int textIdx = json.indexOf("\"text\":", deltaIdx);
        if (textIdx < 0) return null;
        int startQuote = json.indexOf('"', textIdx + 7);
        if (startQuote < 0) return null;
        return extractJsonString(json, startQuote);
    }

    private static String extractFieldValue(String json, String field) {
        String pattern = "\"" + field + "\":\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int start = idx + pattern.length();
        return extractJsonString(json, start - 1);
    }

    private static String extractJsonString(String json, int openQuote) {
        int i = openQuote + 1;
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '"') break;
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case '"' -> { sb.append('"'); i += 2; }
                    case '\\' -> { sb.append('\\'); i += 2; }
                    case 'n' -> { sb.append('\n'); i += 2; }
                    case 'r' -> { sb.append('\r'); i += 2; }
                    case 't' -> { sb.append('\t'); i += 2; }
                    case '/' -> { sb.append('/'); i += 2; }
                    case 'u' -> {
                        if (i + 5 < json.length()) {
                            String hex = json.substring(i + 2, i + 6);
                            try { sb.append((char) Integer.parseInt(hex, 16)); }
                            catch (NumberFormatException e) { sb.append("\\u").append(hex); }
                            i += 6;
                        } else { sb.append(c); i++; }
                    }
                    default -> { sb.append(c); i++; }
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static String escapeJsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private static void inheritEnv(Map<String, String> env) {
        String[] keys = {"PATH", "HOME", "USER", "SHELL", "LANG", "LC_ALL",
                "JAVA_HOME", "MAVEN_HOME", "TERM", "COLORTERM",
                "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GOOGLE_API_KEY",
                "MCP_TIMEOUT"};
        Map<String, String> sysEnv = System.getenv();
        for (String key : keys) {
            String val = sysEnv.get(key);
            if (val != null) env.putIfAbsent(key, val);
        }
        env.putIfAbsent("MCP_TIMEOUT", "60000");
    }

    // ── Builder ──────────────────────────────────────────────────────────────

    public static Builder builder(String agentBinary) {
        return new Builder(agentBinary);
    }

    /**
     * Convenience: resolve agent name to binary and build.
     */
    public static Builder builder(String agentName, Path workDir) {
        String binary = SubprocessAgentRunner.resolveAgentBinary(agentName);
        if (binary == null) {
            throw new IllegalArgumentException("Agent '" + agentName + "' not found on PATH");
        }
        return new Builder(binary).workDir(workDir);
    }

    public static class Builder {
        private final String agentBinary;
        private Path workDir;
        private String systemPrompt;
        private String model;
        private boolean skipPermissions = true;
        private List<String> extraArgs;
        private List<String> clearEnvPrefixes;

        Builder(String agentBinary) {
            this.agentBinary = agentBinary;
        }

        public Builder workDir(Path workDir) { this.workDir = workDir; return this; }
        public Builder systemPrompt(String sp) { this.systemPrompt = sp; return this; }
        public Builder model(String m) { this.model = m; return this; }
        public Builder skipPermissions(boolean b) { this.skipPermissions = b; return this; }
        public Builder extraArgs(List<String> args) { this.extraArgs = args; return this; }

        /** Remove inherited env vars whose name starts with the given prefix. */
        public Builder clearEnvPrefix(String prefix) {
            if (prefix != null && !prefix.isBlank()) {
                if (this.clearEnvPrefixes == null) this.clearEnvPrefixes = new ArrayList<>();
                this.clearEnvPrefixes.add(prefix);
            }
            return this;
        }

        public PersistentAgentProcess build() {
            return new PersistentAgentProcess(this);
        }
    }
}
