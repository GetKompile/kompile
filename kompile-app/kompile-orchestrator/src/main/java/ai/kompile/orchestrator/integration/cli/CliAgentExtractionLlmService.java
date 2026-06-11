/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.orchestrator.integration.cli;

import ai.kompile.core.graphrag.agent.ExtractionLlmService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * {@link ExtractionLlmService} backed by CLI agent subprocesses.
 *
 * <p>Each {@link #complete(String)} call launches a fresh subprocess, pipes the prompt
 * to stdin, and streams stdout directly until the process exits. No persistent process,
 * no polling queues — just straightforward subprocess I/O.
 *
 * <p>Supports both Claude's stream-json format ({@code "assistant"}/{@code "result"} events)
 * and opencode's json format ({@code "text"}/{@code "step_finish"} events), as well as
 * plain-text output from other agents.
 */
@Slf4j
public class CliAgentExtractionLlmService implements ExtractionLlmService {

    private static final int DEFAULT_POOL_SIZE = 8;
    private static final int MAX_POOL_SIZE = 32;

    private final String agentName;
    private final String displayName;
    private final CliAgentConfig agentConfig;
    private final int timeoutSeconds;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Regex covering CSI, OSC, charset, keypad, and string terminator escape sequences. */
    private static final Pattern ANSI_PATTERN = Pattern.compile(
            "\033\\[[0-9;?><]*[a-zA-Z]"
            + "|\033\\].*?(?:\033\\\\|\007)"
            + "|\033[()][0-9A-B]"
            + "|\033[>=<]"
            + "|\033\\\\"
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // PROCESS POOL — pre-spawns CLI agent processes to eliminate startup latency
    // ═══════════════════════════════════════════════════════════════════════════
    private final LinkedBlockingQueue<Process> processPool = new LinkedBlockingQueue<>();
    private volatile ExecutorService poolReplenisher;
    private volatile int targetPoolSize = DEFAULT_POOL_SIZE;
    private final AtomicBoolean poolInitialized = new AtomicBoolean(false);

    public CliAgentExtractionLlmService(String agentName, String displayName,
                                         CliAgentConfig agentConfig, int timeoutSeconds) {
        this.agentName = agentName;
        this.displayName = displayName;
        this.agentConfig = agentConfig;
        this.timeoutSeconds = timeoutSeconds;
        this.poolReplenisher = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "cli-extraction-pool-" + agentName);
            t.setDaemon(true);
            return t;
        });
    }

    private void ensurePoolInitialized() {
        if (poolInitialized.compareAndSet(false, true)) {
            targetPoolSize = readPoolSizeFromConfig();
            log.info("Initializing extraction process pool for {}: size={}", agentName, targetPoolSize);
            poolReplenisher.submit(() -> {
                int spawned = 0;
                for (int i = 0; i < targetPoolSize; i++) {
                    Process p = spawnPoolProcess();
                    if (p != null) {
                        processPool.offer(p);
                        spawned++;
                    }
                }
                log.info("Extraction pool for {} pre-warmed with {}/{} processes", agentName, spawned, targetPoolSize);
            });
        }
    }

    private Process spawnPoolProcess() {
        try {
            List<String> command = buildCommand();
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.environment().putAll(agentConfig.safeEnvironment());
            Process process = pb.start();
            log.debug("Spawned extraction pool process PID {} for {}", process.pid(), agentName);
            return process;
        } catch (Exception e) {
            log.warn("Failed to spawn extraction pool process for {}: {}", agentName, e.getMessage());
            return null;
        }
    }

    private void replenishPool() {
        int deficit = targetPoolSize - processPool.size();
        if (deficit <= 0) return;
        int spawned = 0;
        for (int i = 0; i < deficit; i++) {
            Process p = spawnPoolProcess();
            if (p != null) {
                processPool.offer(p);
                spawned++;
            }
        }
        if (spawned > 0) {
            log.debug("Replenished extraction pool for {} with {} processes (total: {})",
                    agentName, spawned, processPool.size());
        }
    }

    private Process takeFromPool() {
        Process process;
        while ((process = processPool.poll()) != null) {
            if (process.isAlive()) {
                return process;
            }
            log.debug("Discarding dead extraction pool process PID {}", process.pid());
        }
        return null;
    }

    private int readPoolSizeFromConfig() {
        try {
            Path configPath = Path.of(
                    System.getProperty("user.home"), ".kompile", "config", "cli-llm-config.json");
            if (Files.exists(configPath)) {
                JsonNode root = objectMapper.readTree(configPath.toFile());
                if (root.has("processPoolSize")) {
                    return Math.max(1, Math.min(MAX_POOL_SIZE, root.get("processPoolSize").asInt(DEFAULT_POOL_SIZE)));
                }
            }
        } catch (Exception e) {
            log.debug("Could not read pool size from config: {}", e.getMessage());
        }
        return DEFAULT_POOL_SIZE;
    }

    @Override
    public String getId() {
        return agentName;
    }

    @Override
    public String getDescription() {
        return displayName + " (CLI subprocess)";
    }

    @Override
    public String complete(String prompt) {
        log.info("CLI extraction request to {}: prompt length={}", agentName, prompt.length());

        ensurePoolInitialized();

        try {
            Process process = takeFromPool();
            if (process != null) {
                log.info("CLI extraction {} pool hit — reusing PID {}", agentName, process.pid());
            } else {
                List<String> command = buildCommand();
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                pb.environment().putAll(agentConfig.safeEnvironment());
                process = pb.start();
                log.info("CLI extraction {} pool miss — spawned fresh PID {}", agentName, process.pid());
            }

            // Replenish pool asynchronously
            poolReplenisher.submit(this::replenishPool);

            // Write prompt to stdin, then close so agent knows input is complete
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write(prompt);
                writer.newLine();
                writer.flush();
            }
            log.info("CLI agent {}: wrote {} chars to stdin", agentName, prompt.length());

            // Stream stdout directly
            StringBuilder textOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String rawLine;
                while ((rawLine = reader.readLine()) != null) {
                    // Strip TUI escape sequences that leak from agent subprocesses
                    String line = stripAnsi(rawLine);
                    if (line.isBlank()) continue;

                    if (line.trim().startsWith("{")) {
                        try {
                            JsonNode json = objectMapper.readTree(line);
                            String type = json.has("type") ? json.get("type").asText() : null;

                            // Error handling
                            if ("error".equals(type) || "turn.failed".equals(type)) {
                                throw new ExtractionLlmException(
                                        "CLI agent " + agentName + " error: " + extractErrorMessage(json));
                            }

                            // Claude stream-json: "result" = turn complete
                            if ("result".equals(type)) {
                                Double cost = json.has("total_cost_usd")
                                        ? json.get("total_cost_usd").asDouble() : null;
                                Integer duration = json.has("duration_ms")
                                        ? json.get("duration_ms").asInt() : null;
                                log.info("CLI agent {} turn complete: cost=${}, duration={}ms",
                                        agentName, cost, duration);
                                break;
                            }

                            // Claude stream-json: "assistant" = text content
                            if ("assistant".equals(type)) {
                                String text = extractTextFromAssistant(json);
                                if (text != null && !text.isBlank()) {
                                    textOutput.append(text);
                                }
                            }

                            // opencode json: "text" = text content
                            if ("text".equals(type)) {
                                JsonNode part = json.has("part") ? json.get("part") : null;
                                if (part != null && part.has("text")) {
                                    textOutput.append(part.get("text").asText());
                                }
                            }

                            // opencode json: "step_finish" = turn complete
                            if ("step_finish".equals(type)) {
                                log.info("CLI agent {} step_finish event received", agentName);
                            }
                        } catch (ExtractionLlmException e) {
                            throw e;
                        } catch (Exception e) {
                            // Not valid JSON, treat as plain text
                            textOutput.append(line).append('\n');
                        }
                    } else {
                        textOutput.append(line).append('\n');
                    }
                }
            }

            // Wait for process exit
            boolean exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!exited) {
                log.warn("CLI agent {} timed out after {}s, destroying", agentName, timeoutSeconds);
                process.destroyForcibly();
            }

            String output = textOutput.toString().trim();
            if (output.isEmpty()) {
                throw new ExtractionLlmException(
                        "CLI agent " + agentName + " returned no text content");
            }

            log.info("CLI extraction response from {}: output length={} (exit={})",
                    agentName, output.length(), exited ? process.exitValue() : "timeout");
            return output;

        } catch (ExtractionLlmException e) {
            throw e;
        } catch (Exception e) {
            log.error("CLI agent {} subprocess error", agentName, e);
            throw new ExtractionLlmException(
                    "CLI agent " + agentName + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isAvailable() {
        return agentConfig.checkAvailability();
    }

    /**
     * Shut down the process pool and destroy any remaining pre-spawned processes.
     */
    public void shutdown() {
        log.info("Shutting down extraction process pool for {} ({} processes)", agentName, processPool.size());
        poolReplenisher.shutdownNow();
        Process p;
        while ((p = processPool.poll()) != null) {
            try {
                if (p.isAlive()) {
                    p.destroyForcibly();
                }
            } catch (Exception ignored) { }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private List<String> buildCommand() {
        List<String> command = new ArrayList<>();
        command.add(agentConfig.getCommand());

        // Add default args (includes subcommand like "run" for opencode)
        if (agentConfig.getDefaultArgs() != null) {
            command.addAll(agentConfig.getDefaultArgs());
        }

        if (agentConfig.getSkipPermissionsFlag() != null) {
            command.add(agentConfig.getSkipPermissionsFlag());
        }
        if (agentConfig.getOutputFormatFlag() != null && agentConfig.getOutputFormat() != null) {
            command.add(agentConfig.getOutputFormatFlag());
            command.add(agentConfig.getOutputFormat());
        }
        if (agentConfig.getVerboseFlag() != null) {
            command.add(agentConfig.getVerboseFlag());
        }
        return command;
    }

    private String extractTextFromAssistant(JsonNode json) {
        JsonNode message = json.has("message") ? json.get("message") : json;
        if (!message.has("content") || !message.get("content").isArray()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : message.get("content")) {
            String blockType = block.has("type") ? block.get("type").asText() : "text";
            if ("text".equals(blockType) && block.has("text")) {
                text.append(block.get("text").asText());
            }
        }
        return text.isEmpty() ? null : text.toString();
    }

    private String extractErrorMessage(JsonNode json) {
        if (json == null) {
            return "Unknown error";
        }
        if (json.has("message")) {
            return json.get("message").asText();
        }
        if (json.has("error")) {
            JsonNode error = json.get("error");
            if (error.isTextual()) {
                return error.asText();
            }
            if (error.has("message")) {
                return error.get("message").asText();
            }
            return error.toString();
        }
        return json.toString();
    }

    private static String stripAnsi(String text) {
        if (text == null || text.isEmpty()) return text;
        return ANSI_PATTERN.matcher(text).replaceAll("");
    }
}
