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

package ai.kompile.cli.main.chat.enforcer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Best-effort watcher for native CLI JSONL session logs in true passthrough mode.
 * <p>
 * This is not a hard realtime API. It tails append-only session files when the
 * agent writes them and feeds recent messages to the enforcer judge as quickly as
 * the filesystem exposes complete JSONL lines.
 * </p>
 */
public class EnforcerJsonlTailer implements AutoCloseable {

    @FunctionalInterface
    public interface ViolationHandler {
        void onViolation(String reason, String correctionPrompt, boolean toolCall);
    }

    private static final long POLL_MS = 750;
    private static final long DISCOVERY_INTERVAL_MS = 2_000;
    private static final int MIN_TEXT_EVALUATION_CHARS = 1_200;
    private static final int TEXT_EVALUATION_INTERVAL_CHARS = 900;
    private static final long TEXT_EVALUATION_INTERVAL_MS = 2_000;

    private final String agent;
    private final Path workingDir;
    private final Instant sessionStart;
    private final ObjectMapper objectMapper;
    private final EnforcerConversationWindow conversationWindow;
    private final EnforcerJudge judge;
    private final EnforcerPolicy policy;
    private final ViolationHandler violationHandler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread thread;
    private Path jsonlFile;
    private long offset;
    private String partialLine = "";
    private long lastDiscoveryMs;
    private int lastTextEvaluationLength;
    private long lastTextEvaluationMs;
    private final StringBuilder streamingAssistant = new StringBuilder();

    public EnforcerJsonlTailer(String agent, Path workingDir, Instant sessionStart,
                               ObjectMapper objectMapper,
                               EnforcerConversationWindow conversationWindow,
                               EnforcerJudge judge,
                               EnforcerPolicy policy,
                               ViolationHandler violationHandler) {
        this.agent = agent == null ? "" : agent;
        this.workingDir = workingDir.toAbsolutePath().normalize();
        this.sessionStart = sessionStart;
        this.objectMapper = objectMapper;
        this.conversationWindow = conversationWindow;
        this.judge = judge;
        this.policy = policy;
        this.violationHandler = violationHandler;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = new Thread(this::runLoop, "enforcer-jsonl-tail-" + sanitize(agent));
        thread.setDaemon(true);
        thread.start();
    }

    private void runLoop() {
        while (running.get()) {
            try {
                pollOnce();
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                // Passthrough tailing is best-effort and must not destabilize the agent.
            }
        }
    }

    private void pollOnce() throws Exception {
        if (jsonlFile == null || !Files.exists(jsonlFile)) {
            long now = System.currentTimeMillis();
            if (now - lastDiscoveryMs < DISCOVERY_INTERVAL_MS) {
                return;
            }
            lastDiscoveryMs = now;
            jsonlFile = discoverJsonlFile();
            offset = 0;
            partialLine = "";
            if (jsonlFile == null) {
                return;
            }
        }

        long size = Files.size(jsonlFile);
        if (size < offset) {
            offset = 0;
            partialLine = "";
        }
        if (size == offset) {
            return;
        }

        byte[] bytes = readNewBytes(jsonlFile, offset);
        offset += bytes.length;
        String chunk = partialLine + new String(bytes, StandardCharsets.UTF_8);
        String[] lines = chunk.split("\\R", -1);
        int completeCount = lines.length;
        if (!chunk.endsWith("\n") && !chunk.endsWith("\r")) {
            completeCount = Math.max(0, lines.length - 1);
            partialLine = lines.length == 0 ? "" : lines[lines.length - 1];
        } else {
            partialLine = "";
        }

        for (int i = 0; i < completeCount; i++) {
            String line = lines[i];
            if (!line.isBlank()) {
                processLine(line);
            }
        }
    }

    private byte[] readNewBytes(Path file, long startOffset) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            raf.seek(startOffset);
            byte[] buf = new byte[8192];
            int n;
            while ((n = raf.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private void processLine(String line) {
        try {
            JsonNode node = objectMapper.readTree(line);
            String lower = agent.toLowerCase(Locale.ROOT);
            if (lower.contains("claude")) {
                processClaudeLine(node);
            } else if (lower.contains("codex")) {
                processCodexLine(node);
            }
        } catch (Exception ignored) {
        }
    }

    private void processClaudeLine(JsonNode node) {
        String type = node.path("type").asText("");
        if ("user".equals(type)) {
            String text = extractClaudeText(node.path("message").path("content"), false);
            if (!text.isBlank()) {
                conversationWindow.addUserMessage(text);
            }
        } else if ("assistant".equals(type)) {
            JsonNode content = node.path("message").path("content");
            String text = extractClaudeText(content, true);
            collectClaudeToolCalls(content);
            if (!text.isBlank()) {
                conversationWindow.finishAssistantMessage(text);
                evaluateAssistantText(text);
            }
        }
    }

    private String extractClaudeText(JsonNode content, boolean assistant) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (!content.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            String type = block.path("type").asText("");
            if ("text".equals(type) && block.has("text")) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(block.path("text").asText(""));
            } else if (!assistant && "input_text".equals(type)) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(block.path("text").asText(""));
            }
        }
        return sb.toString();
    }

    private void collectClaudeToolCalls(JsonNode content) {
        if (content == null || !content.isArray()) {
            return;
        }
        for (JsonNode block : content) {
            if ("tool_use".equals(block.path("type").asText(""))) {
                String name = block.path("name").asText("unknown");
                String input = block.path("input").toString();
                evaluateToolCall(name, input);
            }
        }
    }

    private void processCodexLine(JsonNode root) {
        JsonNode node = root;
        if (root.has("msg") && root.get("msg").isObject()) {
            node = root.get("msg");
        }

        String type = node.path("type").asText("");
        if ("response_item".equals(type)) {
            JsonNode payload = node.path("payload");
            String payloadType = payload.path("type").asText("");
            String role = payload.path("role").asText("");
            if ("message".equals(payloadType)) {
                String text = extractCodexTextContent(payload);
                if (!text.isBlank()) {
                    if ("user".equals(role) && !text.startsWith("<environment_context>")) {
                        conversationWindow.addUserMessage(text);
                    } else if ("assistant".equals(role)) {
                        conversationWindow.finishAssistantMessage(text);
                        evaluateAssistantText(text);
                    }
                }
            } else if ("function_call".equals(payloadType)
                    || "custom_tool_call".equals(payloadType)) {
                evaluateToolCall(payload.path("name").asText("unknown"),
                        payload.path("arguments").toString());
            }
        } else if ("event_msg".equals(type)) {
            JsonNode payload = node.path("payload");
            String payloadType = payload.path("type").asText("");
            if ("user_message".equals(payloadType)) {
                String msg = payload.path("message").asText("");
                if (!msg.isBlank()) {
                    conversationWindow.addUserMessage(msg);
                }
            } else if ("agent_message".equals(payloadType)) {
                String msg = payload.path("message").asText("");
                if (!msg.isBlank()) {
                    conversationWindow.finishAssistantMessage(msg);
                    evaluateAssistantText(msg);
                }
            }
        } else if ("turn.started".equals(type)) {
            streamingAssistant.setLength(0);
            lastTextEvaluationLength = 0;
            lastTextEvaluationMs = 0;
        } else if ("message.delta".equals(type)) {
            String delta = node.path("delta").asText("");
            if (!delta.isBlank()) {
                streamingAssistant.append(delta);
                String current = streamingAssistant.toString();
                conversationWindow.updateAssistantMessage(current);
                evaluateAssistantText(current);
            }
        } else if ("message.completed".equals(type)) {
            String content = node.path("content").asText("");
            String text = !content.isBlank() ? content : streamingAssistant.toString();
            if (!text.isBlank()) {
                conversationWindow.finishAssistantMessage(text);
                evaluateAssistantText(text);
            }
            streamingAssistant.setLength(0);
        } else if ("turn.completed".equals(type)) {
            if (streamingAssistant.length() > 0) {
                String text = streamingAssistant.toString();
                conversationWindow.finishAssistantMessage(text);
                evaluateAssistantText(text);
                streamingAssistant.setLength(0);
            }
        } else if ("exec.started".equals(type)) {
            String command = node.has("command") ? node.path("command").asText("")
                    : node.path("args").toString();
            evaluateToolCall("exec", command);
        }
    }

    private String extractCodexTextContent(JsonNode payload) {
        JsonNode content = payload.path("content");
        if (!content.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            String type = block.path("type").asText("");
            if ("input_text".equals(type) || "output_text".equals(type)) {
                sb.append(block.path("text").asText(""));
            }
        }
        return sb.toString();
    }

    private void evaluateAssistantText(String text) {
        if (judge == null || policy == null || !policy.hasRules() || text == null || text.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        int length = text.length();
        if (length < MIN_TEXT_EVALUATION_CHARS) {
            return;
        }
        if (length - lastTextEvaluationLength < TEXT_EVALUATION_INTERVAL_CHARS
                && now - lastTextEvaluationMs < TEXT_EVALUATION_INTERVAL_MS) {
            return;
        }
        lastTextEvaluationLength = length;
        lastTextEvaluationMs = now;
        try {
            EnforcerConversationContext context = conversationWindow.snapshot();
            EnforcerDecision decision = judge.evaluatePartialOutput(lastUser(context), text, policy, context);
            if (decision.isStop()) {
                reportViolation(summarize(decision), decision.getCorrectionPrompt(), false);
            }
        } catch (Exception ignored) {
        }
    }

    private void evaluateToolCall(String name, String input) {
        conversationWindow.addToolCall(name, input);
        if (judge == null || policy == null || !policy.hasRules()) {
            return;
        }
        try {
            EnforcerConversationContext context = conversationWindow.snapshot();
            EnforcerToolCallDecision decision = judge.evaluateToolCall(name, input, policy, context);
            if (!decision.isAllowed() || decision.isRewrite()) {
                reportViolation(decision.blockMessage(), decision.getCorrectionPrompt(), true);
            }
        } catch (Exception e) {
            reportViolation("Enforcer tool-call evaluation failed: " + e.getMessage(), "", true);
        }
    }

    private void reportViolation(String reason, String correctionPrompt, boolean toolCall) {
        if (violationHandler != null) {
            violationHandler.onViolation(reason == null || reason.isBlank() ? "rule violation" : reason,
                    correctionPrompt == null ? "" : correctionPrompt, toolCall);
        }
    }

    private String lastUser(EnforcerConversationContext context) {
        List<EnforcerConversationContext.Message> messages = context.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            EnforcerConversationContext.Message message = messages.get(i);
            if ("user".equals(message.role())) {
                return message.content();
            }
        }
        return "";
    }

    private Path discoverJsonlFile() {
        String lower = agent.toLowerCase(Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home"));
        if (lower.contains("claude")) {
            Path projectsDir = home.resolve(".claude").resolve("projects");
            Path projectDir = findClaudeProjectDir(projectsDir, workingDir.toString());
            return projectDir == null ? null : findNewestJsonl(projectDir);
        }
        if (lower.contains("codex")) {
            return findNewestJsonlRecursive(home.resolve(".codex").resolve("sessions"));
        }
        return null;
    }

    private Path findClaudeProjectDir(Path projectsDir, String absWorkDir) {
        if (!Files.isDirectory(projectsDir)) {
            return null;
        }
        String normalized = absWorkDir.replace("\\", "/");
        Path direct = projectsDir.resolve(normalized.replace("/", "-"));
        if (Files.isDirectory(direct)) {
            return direct;
        }
        File[] dirs = projectsDir.toFile().listFiles(File::isDirectory);
        if (dirs == null) {
            return null;
        }
        for (File dir : dirs) {
            String name = dir.getName();
            if (!name.startsWith("-")) {
                continue;
            }
            String decoded = "/" + name.substring(1).replace("-", "/");
            if (normalized.equals(decoded) || normalized.startsWith(decoded + "/")
                    || decoded.startsWith(normalized + "/")) {
                return dir.toPath();
            }
        }
        return null;
    }

    private Path findNewestJsonl(Path dir) {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        File[] files = dir.toFile().listFiles((d, name) -> name.endsWith(".jsonl"));
        return newestModifiedDuringSession(files);
    }

    private Path findNewestJsonlRecursive(Path root) {
        if (!Files.isDirectory(root)) {
            return null;
        }
        List<File> files = new ArrayList<>();
        collectJsonlFiles(root.toFile(), files);
        return newestModifiedDuringSession(files.toArray(new File[0]));
    }

    private void collectJsonlFiles(File dir, List<File> files) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectJsonlFiles(child, files);
            } else if (child.getName().endsWith(".jsonl")) {
                files.add(child);
            }
        }
    }

    private Path newestModifiedDuringSession(File[] files) {
        if (files == null || files.length == 0) {
            return null;
        }
        long startMs = sessionStart.toEpochMilli();
        File newest = null;
        long newestTime = 0;
        for (File file : files) {
            long lastModified = file.lastModified();
            if (lastModified >= startMs - 30_000 && lastModified > newestTime) {
                newest = file;
                newestTime = lastModified;
            }
        }
        return newest == null ? null : newest.toPath();
    }

    private String summarize(EnforcerDecision decision) {
        if (decision == null) {
            return "rule violation";
        }
        if (!decision.getViolations().isEmpty()) {
            return String.join("; ", decision.getViolations());
        }
        if (!decision.getReasoning().isBlank()) {
            return decision.getReasoning();
        }
        return decision.getSeverity();
    }

    private String sanitize(String text) {
        return text == null ? "agent" : text.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    @Override
    public void close() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
