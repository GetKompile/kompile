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

package ai.kompile.app.services.agent;

import ai.kompile.app.subprocess.SubprocessLogBus;
import ai.kompile.app.subprocess.SubprocessLogEvent;
import ai.kompile.core.agent.AgentProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ai.kompile.utils.StringUtils;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives opencode for crawl relation-extraction through a persistent, managed
 * {@code opencode serve} subprocess (a headless HTTP server) — NOT one-shot {@code opencode run}
 * (which hangs headless in this environment) and NOT TUI screen-scraping (which mangles JSON with
 * chain-of-thought + sidebar + dropped structural rows). A single warm server process serves every
 * extraction turn; each turn is an isolated session so unrelated chunks never share context.
 *
 * <h3>Why serve</h3>
 * {@code POST /session/{id}/message} returns the assistant message as clean structured parts
 * ({@code parts[].text} is the model's raw answer — no CoT, no sidebar, no row drops), validated to
 * return complete valid JSON in ~4s even on a free model. {@code /provider} exposes live model
 * discovery and the per-message {@code model:{providerID,modelID}} field makes opencode model
 * alternation trivial — the caller ({@link CliAgentLLMChat}) keeps its dynamic model selection and
 * health-based de-escalation untouched; this class only swaps the transport.
 *
 * <h3>Managed subprocess</h3>
 * The native {@code opencode serve} binary is supervised here (start-on-first-use, restart on death)
 * and its stdout/stderr are republished to the {@link SubprocessLogBus} so the server's real-time
 * process logs are visible alongside every other managed subprocess. {@link ManagedSubprocessLauncher}
 * is intentionally not used — that base launches JVM ({@code java -cp … MainClass}) subprocesses,
 * whereas opencode is a native binary.
 *
 * <h3>Configuration (no hardcoding)</h3>
 * Settings are read from {@code ~/.kompile/config/cli-llm-config.json} under an {@code "opencodeServe"}
 * object: {@code host}, {@code port} (0 = let opencode pick a free port — the actual port is parsed
 * from the server's startup line), {@code workingDir}, {@code startupTimeoutSeconds},
 * {@code defaultProviderId}. All have safe defaults and may be changed on the fly via the config API.
 */
@Service
public class OpencodeServeManager {

    private static final Logger log = LoggerFactory.getLogger(OpencodeServeManager.class);

    /** opencode prints e.g. "opencode server listening on http://127.0.0.1:7801" at startup. */
    private static final Pattern LISTENING = Pattern.compile("listening on\\s+(https?://[^\\s]+)");

    private static final String SUBPROCESS_ID = "opencode-serve";

    private final ObjectMapper objectMapper;
    private final SubprocessLogBus logBus;
    private final HttpClient httpClient;

    // ── Supervised server state ──────────────────────────────────────────────
    private final Object lifecycleLock = new Object();
    private volatile Process serveProcess;
    private volatile String baseUrl;          // e.g. http://127.0.0.1:7801 (parsed from startup line)
    private volatile boolean shutdown = false;

    public OpencodeServeManager(ObjectMapper objectMapper,
                                @Autowired(required = false) SubprocessLogBus logBus) {
        this.objectMapper = objectMapper;
        this.logBus = logBus;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Public API — one extraction turn
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Run a single extraction turn against the managed opencode server.
     *
     * @param agent          the opencode agent provider (supplies the configured binary + env)
     * @param rotationModel  the model id chosen by the caller, in {@code provider/model} form
     *                       (e.g. {@code opencode/deepseek-v4-flash-free}); null/blank uses the
     *                       configured default provider with the server's default model
     * @param prompt         the extraction prompt text
     * @param timeoutSeconds per-turn deadline
     * @return the assistant's clean answer text
     */
    public String prompt(AgentProvider agent, String rotationModel, String prompt, int timeoutSeconds) {
        if (shutdown) {
            throw new IllegalStateException("opencode serve manager is shut down");
        }
        final String url;
        try {
            url = ensureServer(agent);
        } catch (Exception e) {
            throw new IllegalStateException("could not start opencode serve: " + e.getMessage(), e);
        }
        if (url == null) {
            throw new IllegalStateException("opencode serve did not become ready");
        }

        String sessionId = null;
        try {
            sessionId = createSession(url, timeoutSeconds);
            if (sessionId == null) {
                throw new IllegalStateException("opencode serve session creation failed");
            }
            return sendMessage(url, sessionId, rotationModel, prompt, timeoutSeconds);
        } catch (java.net.http.HttpTimeoutException te) {
            throw new IllegalStateException("opencode serve turn timed out after " + timeoutSeconds + "s", te);
        } catch (java.net.ConnectException ce) {
            // Server died underneath us; drop it so the next call restarts a fresh one.
            markServerDead("connect refused: " + ce.getMessage());
            throw new IllegalStateException("opencode serve connection refused (server restarted): " + ce.getMessage(), ce);
        } catch (Exception e) {
            throw new IllegalStateException("opencode serve turn failed: " + e.getMessage(), e);
        } finally {
            if (sessionId != null) {
                deleteSessionQuietly(url, sessionId);
            }
        }
    }

    /** True once a server has been started and a base URL parsed. */
    public boolean isRunning() {
        Process p = serveProcess;
        return p != null && p.isAlive() && baseUrl != null;
    }

    public String currentBaseUrl() {
        return baseUrl;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HTTP turn
    // ═══════════════════════════════════════════════════════════════════════════

    private String createSession(String url, int timeoutSeconds) throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", "kompile-extraction");
        HttpResponse<String> resp = httpClient.send(
                HttpRequest.newBuilder(URI.create(url + "/session"))
                        .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            log.warn("opencode serve /session returned {}: {}", resp.statusCode(), StringUtils.truncate(resp.body(), 200));
            return null;
        }
        JsonNode node = objectMapper.readTree(resp.body());
        String id = node.path("id").asText(null);
        return (id != null && id.startsWith("ses")) ? id : null;
    }

    private String sendMessage(String url, String sessionId, String rotationModel,
                               String prompt, int timeoutSeconds) throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode();

        // Model: split "provider/model" into the API's {providerID, modelID}. When the caller did
        // not pin a model, omit the field entirely so the server uses its configured default.
        ModelRef model = parseModel(rotationModel);
        if (model != null) {
            ObjectNode m = body.putObject("model");
            m.put("providerID", model.providerId());
            m.put("modelID", model.modelId());
        }

        ArrayNode parts = body.putArray("parts");
        ObjectNode textPart = parts.addObject();
        textPart.put("type", "text");
        textPart.put("text", prompt);

        HttpResponse<String> resp = httpClient.send(
                HttpRequest.newBuilder(URI.create(url + "/session/" + sessionId + "/message"))
                        .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("opencode serve message returned " + resp.statusCode() + ": "
                    + StringUtils.truncate(resp.body(), 300));
        }
        String text = extractAssistantText(resp.body());
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("opencode serve returned no assistant text");
        }
        return text;
    }

    /**
     * Pull the assistant's answer out of a {@code POST .../message} response. The response is
     * {@code {info, parts:[...]}}; we concatenate the text of every {@code type=="text"} part and
     * deliberately ignore reasoning/tool/step parts (no CoT pollution).
     */
    String extractAssistantText(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        StringBuilder sb = new StringBuilder();
        collectTextParts(root, sb);
        return sb.toString().trim();
    }

    private void collectTextParts(JsonNode node, StringBuilder sb) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode type = node.get("type");
            JsonNode text = node.get("text");
            if (type != null && "text".equals(type.asText()) && text != null && text.isTextual()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(text.asText());
            }
            node.forEach(child -> collectTextParts(child, sb));
        } else if (node.isArray()) {
            node.forEach(child -> collectTextParts(child, sb));
        }
    }

    private void deleteSessionQuietly(String url, String sessionId) {
        try {
            httpClient.send(
                    HttpRequest.newBuilder(URI.create(url + "/session/" + sessionId))
                            .timeout(Duration.ofSeconds(5))
                            .DELETE()
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // Best effort — a stranded session is harmless; the server is short-lived per crawl anyway.
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Supervised server lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Return the base URL of a live server, starting one if necessary. Thread-safe: concurrent
     * extraction threads that arrive before the server is up all block on the same start.
     */
    private String ensureServer(AgentProvider agent) throws Exception {
        if (isRunning()) {
            return baseUrl;
        }
        synchronized (lifecycleLock) {
            if (isRunning()) {
                return baseUrl;
            }
            startServer(agent);
            return baseUrl;
        }
    }

    private void startServer(AgentProvider agent) throws Exception {
        ServeConfig cfg = readConfig();
        Path workingDir = Path.of(cfg.workingDir);
        Files.createDirectories(workingDir); // small, dedicated, empty — avoids file.watcher tree stalls

        String binary = (agent != null && agent.getCommand() != null) ? agent.getCommand() : "opencode";
        ProcessBuilder pb = new ProcessBuilder(
                binary, "serve", "--hostname", cfg.host, "--port", String.valueOf(cfg.port));
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);
        if (agent != null) {
            Map<String, String> env = new HashMap<>(agent.safeEnvironment());
            pb.environment().putAll(env);
        }

        publishLifecycle("starting opencode serve: " + binary + " serve --hostname " + cfg.host
                + " --port " + cfg.port + " (cwd=" + workingDir + ")");
        Process proc = pb.start();
        this.serveProcess = proc;
        this.baseUrl = null;

        CountDownLatch ready = new CountDownLatch(1);
        Thread reader = new Thread(() -> readServerOutput(proc, ready), "opencode-serve-reader");
        reader.setDaemon(true);
        reader.start();

        if (!ready.await(cfg.startupTimeoutSeconds, TimeUnit.SECONDS) || baseUrl == null) {
            String msg = "opencode serve did not report a listening URL within "
                    + cfg.startupTimeoutSeconds + "s";
            publishLifecycle(msg);
            try {
                proc.destroyForcibly();
            } catch (Exception ignored) {
            }
            this.serveProcess = null;
            throw new IOException(msg);
        }
        publishLifecycle("opencode serve ready at " + baseUrl);
        log.info("opencode serve ready at {} (pid={})", baseUrl, proc.pid());
    }

    /** Read the server's merged stdout/stderr, parse the listening URL, and mirror lines to the bus. */
    private void readServerOutput(Process proc, CountDownLatch ready) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (baseUrl == null) {
                    Matcher m = LISTENING.matcher(line);
                    if (m.find()) {
                        this.baseUrl = m.group(1).trim();
                        ready.countDown();
                    }
                }
                publishLine(line);
            }
        } catch (IOException e) {
            if (!shutdown) {
                log.debug("opencode serve reader IO error: {}", e.getMessage());
            }
        } finally {
            ready.countDown(); // unblock startup even if the process died before printing the URL
            publishLifecycle("opencode serve process exited");
        }
    }

    private void markServerDead(String reason) {
        synchronized (lifecycleLock) {
            Process p = serveProcess;
            if (p != null) {
                try {
                    p.destroyForcibly();
                } catch (Exception ignored) {
                }
            }
            serveProcess = null;
            baseUrl = null;
        }
        publishLifecycle("opencode serve marked dead: " + reason);
    }

    @PreDestroy
    public void shutdownServer() {
        shutdown = true;
        synchronized (lifecycleLock) {
            Process p = serveProcess;
            if (p != null && p.isAlive()) {
                try {
                    p.destroyForcibly();
                } catch (Exception ignored) {
                }
            }
            serveProcess = null;
            baseUrl = null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Config + helpers
    // ═══════════════════════════════════════════════════════════════════════════

    /** Parsed view of the {@code opencodeServe} block in cli-llm-config.json (with defaults). */
    private record ServeConfig(String host, int port, String workingDir,
                               int startupTimeoutSeconds, String defaultProviderId) {
    }

    private ServeConfig readConfig() {
        String host = "127.0.0.1";
        int port = 0; // 0 → opencode picks a free port; we parse the actual URL from the startup line
        String workingDir = Path.of(System.getProperty("user.home"), ".kompile", "opencode-serve").toString();
        int startupTimeout = 30;
        String defaultProvider = "opencode";
        try {
            Path configPath = Path.of(System.getProperty("user.home"), ".kompile", "config", "cli-llm-config.json");
            if (Files.exists(configPath)) {
                JsonNode root = objectMapper.readTree(configPath.toFile());
                JsonNode s = root.get("opencodeServe");
                if (s != null && s.isObject()) {
                    host = s.path("host").asText(host);
                    port = s.path("port").asInt(port);
                    workingDir = s.path("workingDir").asText(workingDir);
                    startupTimeout = Math.max(5, s.path("startupTimeoutSeconds").asInt(startupTimeout));
                    defaultProvider = s.path("defaultProviderId").asText(defaultProvider);
                }
            }
        } catch (Exception e) {
            log.debug("Could not read opencodeServe config, using defaults: {}", e.getMessage());
        }
        return new ServeConfig(host, port, workingDir, startupTimeout, defaultProvider);
    }

    private record ModelRef(String providerId, String modelId) {
    }

    /** Split {@code provider/model} → {providerID, modelID}. Null/blank → null (use server default). */
    private ModelRef parseModel(String rotationModel) {
        if (rotationModel == null || rotationModel.isBlank()) {
            return null;
        }
        String s = rotationModel.trim();
        int slash = s.indexOf('/');
        if (slash > 0 && slash < s.length() - 1) {
            return new ModelRef(s.substring(0, slash), s.substring(slash + 1));
        }
        // No provider prefix — pair the bare model id with the configured default provider.
        return new ModelRef(readConfig().defaultProviderId(), s);
    }

    private void publishLine(String message) {
        if (logBus == null || message == null || message.isBlank()) {
            return;
        }
        try {
            logBus.publish(SubprocessLogEvent.line(
                    SUBPROCESS_ID, SUBPROCESS_ID, null,
                    SubprocessLogEvent.Stream.STDOUT, "INFO", message));
        } catch (Exception ignored) {
        }
    }

    private void publishLifecycle(String message) {
        log.info("[opencode-serve] {}", message);
        if (logBus == null) {
            return;
        }
        try {
            logBus.publish(SubprocessLogEvent.lifecycle(SUBPROCESS_ID, SUBPROCESS_ID, null, message));
        } catch (Exception ignored) {
        }
    }
}
