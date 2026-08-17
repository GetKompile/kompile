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

package ai.kompile.cli.main.chat.harness;

import ai.kompile.cli.main.chat.agent.PersistentAgentProcess;
import ai.kompile.cli.main.chat.agent.PersistentJudgeProcessPool;
import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.core.agent.CliAgentRegistry;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Judge backend that runs an installed CLI agent as a <b>persistent concurrent
 * subprocess</b> using the stream-json stdin/stdout protocol via
 * {@link PersistentAgentProcess}.
 * <p>
 * For claude: uses {@link PersistentAgentProcess} with stream-json protocol.
 * The process starts once, accepts user messages on stdin, returns stream-json
 * events on stdout. Multi-turn within a single persistent process.
 * <p>
 * For other agents: falls back to single-shot subprocess with session resume.
 */
public class CliJudgeBackend implements JudgeBackend {

    private static final List<String> AGENT_PREFERENCE = CliAgentRegistry.commandNames();

    private static final int JUDGE_TIMEOUT_SECONDS = 120;
    private static final int TURN_TIMEOUT_SECONDS = 60;

    private volatile String agentName;
    private volatile String agentBinary;
    private volatile boolean failed;
    private volatile String failureReason;

    /** Lease on the shared judge process pool (persistent mode). */
    private volatile PersistentJudgeProcessPool.Lease judgeLease;
    /** Session ID for single-shot resume (non-claude agents). */
    private volatile String sessionId;

    public CliJudgeBackend(String agentName) {
        if (agentName != null && !agentName.isBlank()) {
            this.agentName = agentName;
            this.agentBinary = SubprocessAgentRunner.resolveAgentBinary(agentName);
        } else {
            String foundName = null;
            String foundBinary = null;
            for (String candidate : AGENT_PREFERENCE) {
                String binary = SubprocessAgentRunner.resolveAgentBinary(candidate);
                if (binary != null) {
                    foundName = candidate;
                    foundBinary = binary;
                    break;
                }
            }
            this.agentName = foundName;
            this.agentBinary = foundBinary;
        }
    }

    @Override
    public void warmUp(String systemPrompt) {
        if (agentBinary == null) {
            markFailure("No CLI agent is available for judge backend");
            return;
        }
        if (supportsPersistentMode()) {
            try {
                ensurePersistentProcess(systemPrompt);
            } catch (IOException | InterruptedException e) {
                markFailure(e.getMessage());
                System.err.println("[enforcer] judge unavailable: " + failureReason);
            }
        }
    }

    @Override
    public String generate(String userPrompt, String systemPrompt) throws Exception {
        if (agentBinary == null) {
            markFailure("No CLI agent available for judge backend");
            throw new IllegalStateException(failureReason);
        }
        if (failed) {
            throw new IllegalStateException("Judge backend is unavailable: " + failureReason
                    + ". Use /judge restart after fixing the agent or /judge agent <name>.");
        }
        if (supportsPersistentMode()) {
            return generatePersistent(userPrompt, systemPrompt);
        }
        try {
            return generateSingleShot(userPrompt, systemPrompt);
        } catch (Exception failure) {
            markFailure(failure.getMessage());
            throw failure;
        }
    }

    // ========================================================================
    // Persistent mode — delegates to PersistentAgentProcess
    // ========================================================================

    private boolean supportsPersistentMode() {
        return agentName != null && agentName.toLowerCase().contains("claude");
    }

    private String generatePersistent(String userPrompt, String systemPrompt) throws Exception {
        try {
            ensurePersistentProcess(systemPrompt);
            String response = judgeLease.sendMessage(userPrompt, TURN_TIMEOUT_SECONDS);
            if (response == null || response.isBlank()) {
                throw new IOException("Judge agent returned an empty response");
            }
            return response;
        } catch (Exception failure) {
            invalidatePersistentProcess();
            markFailure(failure.getMessage());
            String message = "Judge agent '" + agentName + "' failed"
                    + (failureReason == null || failureReason.isBlank() ? "" : ": " + failureReason);
            System.err.println("[enforcer] " + message);
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IOException(message, failure);
        }
    }

    // synchronized: the async constructor warm-up and the first generate() may race here;
    // without the lock both would acquire a lease and one would leak.
    private synchronized void ensurePersistentProcess(String systemPrompt) throws IOException, InterruptedException {
        if (judgeLease != null && judgeLease.isAlive()) return;
        if (judgeLease != null) {
            judgeLease.abort();
            judgeLease = null;
        }

        // Judge processes come from the shared pool: identical spec (binary, model, args,
        // system prompt) → ONE warm process shared by every judge consumer in this JVM
        // (turn gate, realtime tap, tool-call guard, per-call MCP tools) instead of a
        // private agent boot each. Releasing the lease keeps it warm for the pool's idle
        // window, so bursty per-call consumers stop paying a boot per invocation.
        judgeLease = PersistentJudgeProcessPool.acquire(new PersistentJudgeProcessPool.Spec(
                agentBinary,
                "haiku",
                true,
                // --tools "" : judge returns text only. --strict-mcp-config with no
                // --mcp-config: never load project/user MCP servers — a judge that reads
                // the project's .mcp.json spawns kompile mcp-stdio, which (with the
                // enforcer env inherited) builds another judge, recursively.
                List.of("--tools", "", "--strict-mcp-config"),
                // Belt-and-braces against the same recursion: the judge process must not
                // look like an enforced session to anything it spawns.
                List.of("KOMPILE_ENFORCER_"),
                systemPrompt,
                30));
        if (judgeLease == null || !judgeLease.isAlive()) {
            invalidatePersistentProcess();
            throw new IOException("Judge agent did not become ready");
        }
    }

    private synchronized void invalidatePersistentProcess() {
        if (judgeLease != null) {
            try {
                judgeLease.abort();
            } catch (RuntimeException ignored) {
                // Best effort; the process is already considered failed.
            }
            judgeLease = null;
        }
    }

    // ========================================================================
    // Single-shot mode — for agents without interactive stdin
    // ========================================================================

    private String generateSingleShot(String userPrompt, String systemPrompt) throws Exception {
        List<String> cmd = buildSingleShotCommand(agentBinary, userPrompt, systemPrompt);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(System.getProperty("user.dir")));
        pb.redirectErrorStream(false);
        inheritEnv(pb.environment());

        Process process = pb.start();
        process.getOutputStream().close();

        StringBuilder output = new StringBuilder();
        StringBuilder diagnostics = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (sessionId == null && line.contains("\"session_id\"")) {
                        String sid = extractFieldValue(line, "session_id");
                        if (sid != null && !sid.isBlank()) sessionId = sid;
                    }
                    String text = extractText(line);
                    if (text != null) output.append(text);
                }
            } catch (IOException ignored) {}
        }, "cli-judge-reader");
        reader.setDaemon(true);
        reader.start();

        Thread errDrain = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    synchronized (diagnostics) {
                        if (diagnostics.length() < 4_000) {
                            diagnostics.append(line).append('\n');
                        }
                    }
                }
            } catch (IOException ignored) {}
        }, "cli-judge-err-drain");
        errDrain.setDaemon(true);
        errDrain.start();

        boolean finished = process.waitFor(JUDGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Judge agent timed out after " + JUDGE_TIMEOUT_SECONDS + "s"
                    + diagnosticSuffix(diagnostics));
        }
        reader.join(3000);
        errDrain.join(1000);
        int exitCode = process.exitValue();
        String response = output.toString().trim();
        if (exitCode != 0) {
            throw new IOException("Judge agent exited with code " + exitCode + diagnosticSuffix(diagnostics));
        }
        if (response.isBlank()) {
            throw new IOException("Judge agent returned no response" + diagnosticSuffix(diagnostics));
        }
        return response;
    }

    private List<String> buildSingleShotCommand(String binary, String userPrompt, String systemPrompt) {
        List<String> cmd = new ArrayList<>();
        cmd.add(binary);
        String name = agentName.toLowerCase();

        if (name.contains("codex")) {
            if (sessionId != null) {
                cmd.add("exec"); cmd.add("resume"); cmd.add(sessionId);
                cmd.add("--json"); cmd.add("--full-auto");
                cmd.add(fallbackPrompt(userPrompt, systemPrompt));
            } else {
                cmd.add("exec"); cmd.add("--json"); cmd.add("--full-auto");
                cmd.add(fallbackPrompt(userPrompt, systemPrompt));
            }
        } else if (name.contains("gemini")) {
            cmd.add("-p"); cmd.add(fallbackPrompt(userPrompt, systemPrompt));
            cmd.add("-o"); cmd.add("stream-json"); cmd.add("--sandbox=false");
            if (sessionId != null) { cmd.add("--resume"); cmd.add(sessionId); }
        } else if (name.contains("qwen")) {
            cmd.add("-o"); cmd.add("stream-json"); cmd.add("--yolo");
            if (sessionId != null) cmd.add("--continue");
            cmd.add(fallbackPrompt(userPrompt, systemPrompt));
        } else if (name.contains("opencode")) {
            cmd.add("run"); cmd.add("--format"); cmd.add("json");
            cmd.add("--dangerously-skip-permissions");
            if (sessionId != null) { cmd.add("--session"); cmd.add(sessionId); }
            cmd.add(fallbackPrompt(userPrompt, systemPrompt));
        } else if (name.contains("pi")) {
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                cmd.add("--system-prompt"); cmd.add(systemPrompt);
            }
            cmd.add("--mode"); cmd.add("json"); cmd.add("-p"); cmd.add(userPrompt);
            if (sessionId != null) cmd.add("--continue");
        } else {
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                cmd.add("--system-prompt"); cmd.add(systemPrompt);
            }
            cmd.add("-p"); cmd.add(userPrompt);
            cmd.add("--output-format"); cmd.add("stream-json");
            cmd.add("--verbose"); cmd.add("--dangerously-skip-permissions");
            if (sessionId != null) { cmd.add("--resume"); cmd.add(sessionId); }
        }
        return cmd;
    }

    // ========================================================================
    // Interface
    // ========================================================================

    @Override
    public boolean isAvailable() {
        return agentBinary != null && !failed;
    }

    @Override
    public synchronized void restart() {
        invalidatePersistentProcess();
        sessionId = null;
        failed = false;
        failureReason = null;
        agentBinary = agentName == null ? null : SubprocessAgentRunner.resolveAgentBinary(agentName);
        if (agentBinary == null) {
            markFailure("Agent '" + agentName + "' is not available on PATH");
            System.err.println("[enforcer] judge restart failed: " + failureReason);
        }
    }

    @Override
    public synchronized boolean modify(String selection) {
        if (selection == null || selection.isBlank()) return false;
        String next = selection.trim();
        invalidatePersistentProcess();
        sessionId = null;
        agentName = next;
        agentBinary = SubprocessAgentRunner.resolveAgentBinary(next);
        failed = false;
        failureReason = null;
        if (agentBinary == null) {
            markFailure("Agent '" + next + "' is not available on PATH");
            System.err.println("[enforcer] judge agent unavailable: " + failureReason);
            return false;
        }
        return true;
    }

    @Override
    public String failureReason() {
        return failureReason == null ? "" : failureReason;
    }

    @Override
    public synchronized void close() {
        // A failed lease must be destroyed, not returned to the warm pool.
        if (failed) {
            invalidatePersistentProcess();
        } else if (judgeLease != null) {
            // Releases the pool lease — the shared process stays warm for the pool's idle
            // window so the next judge consumer skips the agent boot entirely.
            judgeLease.close();
            judgeLease = null;
        }
    }

    @Override
    public String describe() {
        return "cli(" + (agentName != null ? agentName : "none")
                + (supportsPersistentMode() ? ",persistent-stream-json" : "") + ")";
    }

    private void markFailure(String reason) {
        failed = true;
        failureReason = reason == null || reason.isBlank() ? "unknown judge failure" : reason;
    }

    private String diagnosticSuffix(StringBuilder diagnostics) {
        synchronized (diagnostics) {
            String detail = diagnostics.toString().trim();
            return detail.isBlank() ? "" : ": " + detail;
        }
    }

    public static boolean anyAgentAvailable() {
        for (String candidate : AGENT_PREFERENCE) {
            if (SubprocessAgentRunner.resolveAgentBinary(candidate) != null) return true;
        }
        return false;
    }

    public static String firstAvailableAgent() {
        for (String candidate : AGENT_PREFERENCE) {
            if (SubprocessAgentRunner.resolveAgentBinary(candidate) != null) return candidate;
        }
        return null;
    }

    // ========================================================================
    // Helpers (single-shot text extraction for non-claude agents)
    // ========================================================================

    private String fallbackPrompt(String userPrompt, String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank()) return userPrompt;
        return systemPrompt + "\n\n---\n\n" + userPrompt;
    }

    private String extractText(String line) {
        if (line == null || line.isBlank()) return null;
        String trimmed = line.trim();

        if (!trimmed.startsWith("{")) return trimmed + "\n";

        boolean isTextEvent = trimmed.contains("\"text_delta\"")
                || trimmed.contains("\"content_block_delta\"")
                || (trimmed.contains("\"type\":\"text\"") && !trimmed.contains("\"type\":\"text_delta\""));
        boolean isCodexOutput = trimmed.contains("\"output_text\"");
        boolean isGeminiText = trimmed.contains("\"type\":\"text_delta\"");

        if (!isTextEvent && !isCodexOutput && !isGeminiText) {
            if (trimmed.contains("\"type\":\"result\"")) return extractFieldValue(trimmed, "result");
            return null;
        }
        return extractFieldValue(trimmed, "text");
    }

    private String extractFieldValue(String json, String fieldName) {
        String needle = "\"" + fieldName + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + needle.length());
        if (colonIdx < 0) return null;
        int startQuote = json.indexOf('"', colonIdx + 1);
        if (startQuote < 0) return null;
        return extractJsonString(json, startQuote);
    }

    private String extractJsonString(String json, int openQuote) {
        StringBuilder sb = new StringBuilder();
        for (int i = openQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    default -> { sb.append('\\'); sb.append(next); }
                }
                i++;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private void inheritEnv(java.util.Map<String, String> env) {
        for (String key : new String[]{"PATH", "HOME", "USER", "SHELL", "LANG",
                "LC_ALL", "JAVA_HOME", "TERM", "COLORTERM"}) {
            String val = System.getenv(key);
            if (val != null) env.put(key, val);
        }
        env.put("GEMINI_CLI_TRUST_WORKSPACE", "true");
    }
}
