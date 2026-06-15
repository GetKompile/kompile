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

package ai.kompile.cli.common.chat.sources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Optional;

public final class ChatAdapterSupport {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private ChatAdapterSupport() {}

    public static Path userHome() {
        return Paths.get(System.getProperty("user.home"));
    }

    /**
     * Platform-correct IDE/editor config root (covers VSCode, Cursor, Continue).
     * Honors {@code XDG_CONFIG_HOME} on Linux.
     */
    public static Path userConfigDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return userHome().resolve("Library").resolve("Application Support");
        }
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Paths.get(appData);
            }
            return userHome().resolve("AppData").resolve("Roaming");
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Paths.get(xdg);
        }
        return userHome().resolve(".config");
    }

    public static String extractRole(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String role = textOf(node, "role");
        if (role != null) return normalizeRole(role);
        String type = textOf(node, "type");
        if (type != null) {
            String n = type.toLowerCase(Locale.ROOT);
            if (n.equals("human") || n.equals("user") || n.equals("assistant")
                    || n.equals("ai") || n.equals("model") || n.equals("gemini")
                    || n.equals("bot")) {
                return normalizeRole(n);
            }
        }
        String sender = textOf(node, "sender");
        if (sender != null) {
            String n = sender.toLowerCase(Locale.ROOT);
            if (n.equals("user") || n.equals("human")) return "user";
            if (n.equals("bot") || n.equals("assistant") || n.equals("ai") || n.equals("model")) return "assistant";
        }
        JsonNode msg = node.path("message");
        if (msg.isObject()) return extractRole(msg);
        return null;
    }

    private static String normalizeRole(String raw) {
        String n = raw.toLowerCase(Locale.ROOT);
        if (n.equals("human") || n.equals("user")) return "user";
        if (n.equals("assistant") || n.equals("ai") || n.equals("model")
                || n.equals("bot") || n.equals("gemini")) return "assistant";
        if (n.equals("system")) return "system";
        if (n.equals("tool") || n.equals("tool_result") || n.equals("tool_use")) return "tool";
        return n;
    }

    public static String extractContent(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;

        JsonNode msg = node.path("message");
        if (msg.isObject()) {
            String fromMessage = extractContent(msg);
            if (fromMessage != null && !fromMessage.isBlank()) return fromMessage;
        }

        JsonNode content = node.path("content");
        if (!content.isMissingNode() && !content.isNull()) {
            if (content.isTextual()) return content.asText();
            if (content.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode block : content) {
                    String blockText = extractBlockText(block);
                    if (blockText != null && !blockText.isBlank()) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(blockText);
                    }
                }
                String joined = sb.toString();
                if (!joined.isBlank()) return joined;
            }
        }

        JsonNode parts = node.path("parts");
        if (parts.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : parts) {
                String blockText = extractBlockText(part);
                if (blockText != null && !blockText.isBlank()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(blockText);
                }
            }
            String joined = sb.toString();
            if (!joined.isBlank()) return joined;
        }

        if (node.has("text")) {
            JsonNode text = node.get("text");
            if (text.isTextual()) return text.asText();
        }

        return null;
    }

    private static String extractBlockText(JsonNode block) {
        if (block == null || block.isMissingNode() || block.isNull()) return null;
        if (block.isTextual()) return block.asText();
        String type = textOf(block, "type");
        if ("thinking".equalsIgnoreCase(type)) {
            String thinking = textOf(block, "thinking");
            if (thinking == null) thinking = textOf(block, "text");
            return thinking == null ? null : "[thinking] " + thinking;
        }
        if ("tool_use".equalsIgnoreCase(type)) {
            String name = textOf(block, "name");
            return name == null ? null : "[tool:" + name + "]";
        }
        if ("tool_result".equalsIgnoreCase(type)) {
            String text = textOf(block, "text");
            if (text != null) return "[tool-result] " + text;
            JsonNode c = block.path("content");
            if (c.isTextual()) return "[tool-result] " + c.asText();
            if (c.isArray()) {
                StringBuilder sb = new StringBuilder("[tool-result] ");
                for (JsonNode b : c) {
                    String bt = extractBlockText(b);
                    if (bt != null) sb.append(bt).append(' ');
                }
                return sb.toString().trim();
            }
            return null;
        }
        String text = textOf(block, "text");
        if (text != null) return text;
        JsonNode content = block.path("content");
        if (content.isTextual()) return content.asText();
        return null;
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return (n.isTextual() && !n.asText().isEmpty()) ? n.asText() : null;
    }

    public static Optional<String> safeSessionId(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String trimmed = raw.trim();
        if (trimmed.contains("..") || trimmed.contains("/") || trimmed.contains("\\")) return Optional.empty();
        return Optional.of(trimmed);
    }

    public static String sanitize(String raw) {
        if (raw == null) return "unknown";
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public static long lastModified(Path path) {
        try {
            File f = path.toFile();
            return f.exists() ? f.lastModified() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Runs a block with the SQLite driver loaded, and returns a fallback if the driver
     * class is missing (e.g. running in a minimal native-image build).
     */
    public static boolean sqliteAvailable() {
        try {
            Class.forName("org.sqlite.JDBC");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
