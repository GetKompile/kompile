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

import ai.kompile.cli.main.chat.harness.HarnessConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Durable runtime policy shared with subprocesses spawned by enforcer mode.
 * <p>
 * The chat process writes a small policy file and injects its path into child
 * process environments. Any nested {@code kompile mcp-stdio} server then loads
 * the same rules and blocks forbidden tool calls before the tool executor runs.
 * </p>
 */
public class EnforcerRuntimePolicy {

    public static final String ENV_ACTIVE = "KOMPILE_ENFORCER_ACTIVE";
    public static final String ENV_POLICY_FILE = "KOMPILE_ENFORCER_POLICY_FILE";
    public static final String ENV_SESSION_ID = "KOMPILE_ENFORCER_SESSION_ID";
    public static final String ENV_CONTEXT_FILE = "KOMPILE_ENFORCER_CONTEXT_FILE";

    private final String sessionId;
    private final Path policyFile;
    private final Path contextFile;
    private final EnforcerPolicy policy;
    private final HarnessConfig harnessConfig;

    public EnforcerRuntimePolicy(String sessionId, Path policyFile,
                                 Path contextFile,
                                 EnforcerPolicy policy, HarnessConfig harnessConfig) {
        this.sessionId = sessionId;
        this.policyFile = policyFile;
        this.contextFile = contextFile;
        this.policy = policy;
        this.harnessConfig = harnessConfig;
    }

    public static EnforcerRuntimePolicy create(Path workingDir, EnforcerPolicy policy,
                                               HarnessConfig harnessConfig,
                                               ObjectMapper objectMapper) throws IOException {
        String sessionId = "enforcer-" + UUID.randomUUID().toString().substring(0, 8);
        return create(workingDir, sessionId, policy, harnessConfig, objectMapper);
    }

    public static EnforcerRuntimePolicy create(Path workingDir, String sessionId,
                                               EnforcerPolicy policy,
                                               HarnessConfig harnessConfig,
                                               ObjectMapper objectMapper) throws IOException {
        Path baseDir = workingDir.toAbsolutePath().normalize()
                .resolve(".kompile").resolve("enforcer");
        Files.createDirectories(baseDir);
        Path policyFile = baseDir.resolve(sessionId + ".json");
        Path contextFile = baseDir.resolve(sessionId + "-context.json");

        ObjectNode root = objectMapper.createObjectNode();
        root.put("sessionId", sessionId);
        root.put("createdAt", Instant.now().toString());
        root.put("contextFile", contextFile.toAbsolutePath().toString());
        root.put("rules", policy.getRules());
        root.put("maxCorrections", policy.getMaxCorrections());
        root.put("returnAttempts", policy.isReturnAttempts());
        ObjectNode judge = root.putObject("judge");
        if (harnessConfig != null) {
            putIfPresent(judge, "mode", harnessConfig.getJudgeMode());
            putIfPresent(judge, "provider", harnessConfig.getJudgeProvider());
            putIfPresent(judge, "model", harnessConfig.getJudgeModel());
            putIfPresent(judge, "apiKey", harnessConfig.getJudgeApiKey());
            putIfPresent(judge, "baseUrl", harnessConfig.getJudgeBaseUrl());
            putIfPresent(judge, "localModel", harnessConfig.getJudgeLocalModel());
            putIfPresent(judge, "localQuant", harnessConfig.getJudgeLocalQuant());
            putIfPresent(judge, "serverType", harnessConfig.getJudgeServerType());
            if (harnessConfig.getJudgeServerPort() > 0) {
                judge.put("serverPort", harnessConfig.getJudgeServerPort());
            }
        }

        Files.writeString(policyFile, objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(root), StandardCharsets.UTF_8);
        EnforcerConversationContext.empty().write(contextFile, objectMapper);
        return new EnforcerRuntimePolicy(sessionId, policyFile, contextFile, policy, harnessConfig);
    }

    public static EnforcerRuntimePolicy loadFromEnvironment(ObjectMapper objectMapper) {
        String active = System.getenv(ENV_ACTIVE);
        if (!Boolean.parseBoolean(active)) {
            return null;
        }
        String path = System.getenv(ENV_POLICY_FILE);
        if (path == null || path.isBlank()) {
            return null;
        }
        return load(Path.of(path), objectMapper);
    }

    public static EnforcerRuntimePolicy load(Path policyFile, ObjectMapper objectMapper) {
        if (policyFile == null || !Files.exists(policyFile)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(policyFile, StandardCharsets.UTF_8));
            String sessionId = root.path("sessionId").asText(policyFile.getFileName().toString());
            String rules = root.path("rules").asText("");
            int maxCorrections = root.path("maxCorrections").asInt(EnforcerPolicy.DEFAULT_MAX_CORRECTIONS);
            boolean returnAttempts = root.path("returnAttempts").asBoolean(false);
            String contextPath = root.path("contextFile").asText("");
            Path contextFile = contextPath.isBlank()
                    ? policyFile.toAbsolutePath().normalize()
                    .resolveSibling(sessionId + "-context.json")
                    : Path.of(contextPath).toAbsolutePath().normalize();

            HarnessConfig harnessConfig = HarnessConfig.load(objectMapper);
            JsonNode judge = root.path("judge");
            setIfText(judge, "mode", harnessConfig::setJudgeMode);
            setIfText(judge, "provider", harnessConfig::setJudgeProvider);
            setIfText(judge, "model", harnessConfig::setJudgeModel);
            setIfText(judge, "apiKey", harnessConfig::setJudgeApiKey);
            setIfText(judge, "baseUrl", harnessConfig::setJudgeBaseUrl);
            setIfText(judge, "localModel", harnessConfig::setJudgeLocalModel);
            setIfText(judge, "localQuant", harnessConfig::setJudgeLocalQuant);
            setIfText(judge, "serverType", harnessConfig::setJudgeServerType);
            if (judge.has("serverPort")) {
                harnessConfig.setJudgeServerPort(judge.path("serverPort").asInt());
            }

            return new EnforcerRuntimePolicy(sessionId, policyFile.toAbsolutePath().normalize(),
                    contextFile,
                    new EnforcerPolicy(rules, maxCorrections, returnAttempts), harnessConfig);
        } catch (Exception e) {
            System.err.println("[Enforcer] Failed to load runtime policy: " + e.getMessage());
            return null;
        }
    }

    public Map<String, String> toEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(ENV_ACTIVE, "true");
        env.put(ENV_POLICY_FILE, policyFile.toAbsolutePath().toString());
        env.put(ENV_SESSION_ID, sessionId);
        if (contextFile != null) {
            env.put(ENV_CONTEXT_FILE, contextFile.toAbsolutePath().toString());
        }
        return env;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Path getPolicyFile() {
        return policyFile;
    }

    public Path getContextFile() {
        return contextFile;
    }

    public EnforcerPolicy getPolicy() {
        return policy;
    }

    public HarnessConfig getHarnessConfig() {
        return harnessConfig;
    }

    public void cleanup() {
        try {
            Files.deleteIfExists(policyFile);
        } catch (IOException ignored) {
        }
        try {
            if (contextFile != null) {
                Files.deleteIfExists(contextFile);
            }
        } catch (IOException ignored) {
        }
    }

    private static void putIfPresent(ObjectNode node, String key, String value) {
        if (value != null && !value.isBlank()) {
            node.put(key, value);
        }
    }

    private static void setIfText(JsonNode node, String key,
                                  java.util.function.Consumer<String> setter) {
        JsonNode value = node.path(key);
        if (value.isTextual() && !value.asText().isBlank()) {
            setter.accept(value.asText());
        }
    }
}
