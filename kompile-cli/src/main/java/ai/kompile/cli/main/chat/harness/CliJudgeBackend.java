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

    private final String agentName;
    private final String agentBinary;

    /** Persistent subprocess via shared infrastructure. */
    private volatile PersistentAgentProcess persistentProcess;
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
        if (agentBinary == null) return;
        if (supportsPersistentMode()) {
            try {
                ensurePersistentProcess(systemPrompt);
            } catch (IOException | InterruptedException e) {
                // Will retry on first generate() call
            }
        }
    }

    @Override
    public String generate(String userPrompt, String systemPrompt) throws Exception {
        if (agentBinary == null) {
            throw new IllegalStateException("No CLI agent available for judge backend");
        }
        if (supportsPersistentMode()) {
            return generatePersistent(userPrompt, systemPrompt);
        }
        return generateSingleShot(userPrompt, systemPrompt);
    }

    // ========================================================================
    // Persistent mode — delegates to PersistentAgentProcess
    // ========================================================================

    private boolean supportsPersistentMode() {
        return agentName != null && agentName.toLowerCase().contains("claude");
    }

    private String generatePersistent(String userPrompt, String systemPrompt) throws Exception {
        ensurePersistentProcess(systemPrompt);
        return persistentProcess.sendMessage(userPrompt, TURN_TIMEOUT_SECONDS);
    }

    private void ensurePersistentProcess(String systemPrompt) throws IOException, InterruptedException {
        if (persistentProcess != null && persistentProcess.isAlive()) return;

        persistentProcess = PersistentAgentProcess.builder(agentBinary)
                .systemPrompt(systemPrompt)
                .model("haiku")
                .skipPermissions(true)
                .extraArgs(List.of("--tools", ""))  // disable tools — judge returns text only
                .build();
        persistentProcess.start(30);
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
            try { process.getErrorStream().transferTo(OutputStream.nullOutputStream()); }
            catch (IOException ignored) {}
        }, "cli-judge-err-drain");
        errDrain.setDaemon(true);
        errDrain.start();

        boolean finished = process.waitFor(JUDGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Judge agent timed out after " + JUDGE_TIMEOUT_SECONDS + "s");
        }
        reader.join(3000);
        return output.toString().trim();
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
        return agentBinary != null;
    }

    @Override
    public void close() {
        if (persistentProcess != null) {
            persistentProcess.close();
            persistentProcess = null;
        }
    }

    @Override
    public String describe() {
        return "cli(" + (agentName != null ? agentName : "none")
                + (supportsPersistentMode() ? ",persistent-stream-json" : "") + ")";
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
