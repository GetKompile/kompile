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

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Centralizes compatibility-sensitive CLI flags for external agents.
 *
 * <p>Overrides are resolved in this order:
 * system property, environment variable, nearest project config, user config,
 * then built-in defaults. Empty override values intentionally disable defaults.
 */
public final class AgentFlagOverrides {

    private static final ObjectMapper OBJECT_MAPPER = JsonUtils.standardMapper();
    private static final String CONFIG_FILE = "agent-flags.json";

    private AgentFlagOverrides() {
    }

    public enum FlagSet {
        PERMISSION_BYPASS("permissionBypass");

        private final String key;

        FlagSet(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    public static void addPermissionBypassFlags(List<String> command, String agentName, boolean enabled) {
        addPermissionBypassFlags(command, agentName, enabled, null);
    }

    public static void addPermissionBypassFlags(List<String> command,
                                                String agentName,
                                                boolean enabled,
                                                Path workingDirectory) {
        if (!enabled || command == null) {
            return;
        }
        command.addAll(flags(agentName, FlagSet.PERMISSION_BYPASS, workingDirectory));
    }

    public static List<String> permissionBypassFlags(String agentName) {
        return flags(agentName, FlagSet.PERMISSION_BYPASS, null);
    }

    public static List<String> permissionBypassFlags(String agentName, Path workingDirectory) {
        return flags(agentName, FlagSet.PERMISSION_BYPASS, workingDirectory);
    }

    public static List<String> flags(String agentName, FlagSet flagSet, Path workingDirectory) {
        String agentKey = agentKey(agentName);
        return configuredFlags(agentKey, flagSet, workingDirectory)
                .orElseGet(() -> defaultFlags(agentKey, flagSet));
    }

    public static Optional<List<String>> configuredPermissionBypassFlags(String agentName, Path workingDirectory) {
        return configuredFlags(agentKey(agentName), FlagSet.PERMISSION_BYPASS, workingDirectory);
    }

    private static Optional<List<String>> configuredFlags(String agentKey,
                                                          FlagSet flagSet,
                                                          Path workingDirectory) {
        Optional<List<String>> systemProperty = systemPropertyOverride(agentKey, flagSet);
        if (systemProperty.isPresent()) {
            return systemProperty;
        }

        Optional<List<String>> environment = environmentOverride(agentKey, flagSet);
        if (environment.isPresent()) {
            return environment;
        }

        Optional<List<String>> project = projectOverride(agentKey, flagSet, workingDirectory);
        if (project.isPresent()) {
            return project;
        }

        Optional<List<String>> user = jsonOverride(userConfigPath(), agentKey, flagSet);
        if (user.isPresent()) {
            return user;
        }

        return Optional.empty();
    }

    public static String agentKey(String agentName) {
        String name = agentName == null ? "" : agentName.toLowerCase(Locale.ROOT);
        if (name.contains("claude")) {
            return "claude";
        }
        if (name.contains("codex")) {
            return "codex";
        }
        if (name.contains("opencode")) {
            return "opencode";
        }
        if (name.contains("qwen")) {
            return "qwen";
        }
        if (name.contains("gemini")) {
            return "gemini";
        }
        if (name.contains("pi")) {
            return "pi";
        }
        return normalizeAgentKey(name);
    }

    private static List<String> defaultFlags(String agentKey, FlagSet flagSet) {
        if (flagSet != FlagSet.PERMISSION_BYPASS) {
            return Collections.emptyList();
        }
        return switch (agentKey) {
            case "claude", "opencode" -> List.of("--dangerously-skip-permissions");
            case "codex" -> List.of("--dangerously-bypass-approvals-and-sandbox");
            case "qwen", "gemini" -> List.of("--yolo");
            case "pi" -> Collections.emptyList(); // pi is non-interactive in -p mode, no bypass needed
            default -> Collections.emptyList();
        };
    }

    private static Optional<List<String>> systemPropertyOverride(String agentKey, FlagSet flagSet) {
        for (String propertyName : propertyNames(agentKey, flagSet)) {
            if (System.getProperties().containsKey(propertyName)) {
                return Optional.of(splitFlags(System.getProperty(propertyName)));
            }
        }
        return Optional.empty();
    }

    private static Optional<List<String>> environmentOverride(String agentKey, FlagSet flagSet) {
        for (String envName : environmentNames(agentKey, flagSet)) {
            if (System.getenv().containsKey(envName)) {
                return Optional.of(splitFlags(System.getenv(envName)));
            }
        }
        return Optional.empty();
    }

    private static Optional<List<String>> projectOverride(String agentKey,
                                                          FlagSet flagSet,
                                                          Path workingDirectory) {
        Path current = workingDirectory != null ? workingDirectory : Path.of(System.getProperty("user.dir"));
        current = current.toAbsolutePath().normalize();

        while (current != null) {
            Optional<List<String>> direct = jsonOverride(current.resolve(".kompile").resolve(CONFIG_FILE), agentKey, flagSet);
            if (direct.isPresent()) {
                return direct;
            }
            Optional<List<String>> nested = jsonOverride(current.resolve(".kompile").resolve("config").resolve(CONFIG_FILE),
                    agentKey,
                    flagSet);
            if (nested.isPresent()) {
                return nested;
            }
            current = current.getParent();
        }

        return Optional.empty();
    }

    private static Path userConfigPath() {
        return KompileHome.configDirectory().toPath().resolve(CONFIG_FILE);
    }

    private static Optional<List<String>> jsonOverride(Path configPath, String agentKey, FlagSet flagSet) {
        if (configPath == null || !Files.isRegularFile(configPath)) {
            return Optional.empty();
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(configPath.toFile());
            Optional<List<String>> direct = jsonOverride(root, agentKey, flagSet);
            if (direct.isPresent()) {
                return direct;
            }
            JsonNode agents = root.path("agents");
            if (agents.isObject()) {
                return jsonOverride(agents, agentKey, flagSet);
            }
        } catch (IOException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Optional<List<String>> jsonOverride(JsonNode root, String agentKey, FlagSet flagSet) {
        for (String key : List.of(agentKey, "default", "*")) {
            JsonNode agentNode = root.get(key);
            if (agentNode == null || !agentNode.isObject()) {
                continue;
            }
            Optional<JsonNode> value = findFlagNode(agentNode, flagSet);
            if (value.isPresent()) {
                return Optional.of(flagsFromJson(value.get()));
            }
        }
        return Optional.empty();
    }

    private static Optional<JsonNode> findFlagNode(JsonNode agentNode, FlagSet flagSet) {
        for (String key : flagKeys(flagSet)) {
            if (agentNode.has(key)) {
                return Optional.of(agentNode.get(key));
            }
        }
        return Optional.empty();
    }

    private static List<String> flagsFromJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return Collections.emptyList();
        }
        if (node.isArray()) {
            List<String> result = new ArrayList<>();
            for (JsonNode item : node) {
                if (!item.isNull()) {
                    String value = item.asText();
                    if (!value.isBlank()) {
                        result.add(value);
                    }
                }
            }
            return List.copyOf(result);
        }
        if (node.isTextual()) {
            return splitFlags(node.asText());
        }
        if (node.isBoolean() && !node.asBoolean()) {
            return Collections.emptyList();
        }
        return splitFlags(node.asText());
    }

    static List<String> splitFlags(String value) {
        if (value == null || value.isBlank()) {
            return Collections.emptyList();
        }

        String trimmed = value.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            try {
                JsonNode node = OBJECT_MAPPER.readTree(trimmed);
                return flagsFromJson(node);
            } catch (IOException ignored) {
                // Fall back to shell-like splitting below.
            }
        }

        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaping = false;

        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (escaping) {
                current.append(ch);
                escaping = false;
                continue;
            }
            if (ch == '\\' && !inSingleQuote) {
                escaping = true;
                continue;
            }
            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }
            if (Character.isWhitespace(ch) && !inSingleQuote && !inDoubleQuote) {
                addPart(parts, current);
                continue;
            }
            current.append(ch);
        }
        if (escaping) {
            current.append('\\');
        }
        addPart(parts, current);

        return List.copyOf(parts);
    }

    private static void addPart(List<String> parts, StringBuilder current) {
        if (current.length() == 0) {
            return;
        }
        parts.add(current.toString());
        current.setLength(0);
    }

    private static List<String> propertyNames(String agentKey, FlagSet flagSet) {
        return List.of(
                "kompile.agent.flags." + agentKey + "." + flagSet.key(),
                "kompile.agent.flags." + agentKey + "." + kebabCase(flagSet.key()),
                "kompile.agent.flags." + agentKey + "." + snakeCase(flagSet.key())
        );
    }

    private static List<String> environmentNames(String agentKey, FlagSet flagSet) {
        String agent = envToken(agentKey);
        String flag = snakeCase(flagSet.key()).toUpperCase(Locale.ROOT);
        String compactFlag = flag.replace("_", "");
        return List.of(
                "KOMPILE_AGENT_FLAGS_" + agent + "_" + flag,
                "KOMPILE_AGENT_FLAGS_" + agent + "_" + compactFlag
        );
    }

    private static List<String> flagKeys(FlagSet flagSet) {
        return List.of(flagSet.key(), kebabCase(flagSet.key()), snakeCase(flagSet.key()));
    }

    private static String normalizeAgentKey(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("^-+", "").replaceAll("-+$", "");
        return normalized.isEmpty() ? "default" : normalized;
    }

    private static String envToken(String value) {
        return value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    private static String kebabCase(String value) {
        return snakeCase(value).replace('_', '-');
    }

    private static String snakeCase(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isUpperCase(ch) && i > 0) {
                result.append('_');
            }
            result.append(Character.toLowerCase(ch));
        }
        return result.toString();
    }
}
