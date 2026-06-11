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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable recent-message context supplied to the enforcer judge.
 */
public class EnforcerConversationContext {

    public record Message(String role, String content) {
        public Message {
            role = role == null || role.isBlank() ? "unknown" : role;
            content = content == null ? "" : content;
        }
    }

    private final List<Message> messages;

    public EnforcerConversationContext(List<Message> messages) {
        this.messages = List.copyOf(messages == null ? List.of() : messages);
    }

    public static EnforcerConversationContext empty() {
        return new EnforcerConversationContext(List.of());
    }

    public static EnforcerConversationContext of(List<Message> messages) {
        return new EnforcerConversationContext(messages);
    }

    public List<Message> getMessages() {
        return messages;
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }

    public String formatForPrompt(int maxChars) {
        if (messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message message : messages) {
            if (message.content().isBlank()) {
                continue;
            }
            sb.append(message.role()).append(": ")
                    .append(message.content().trim())
                    .append("\n\n");
        }
        return truncateFromStart(sb.toString().trim(), maxChars);
    }

    public ObjectNode toJson(ObjectMapper objectMapper) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("updatedAt", Instant.now().toString());
        ArrayNode array = root.putArray("messages");
        for (Message message : messages) {
            ObjectNode item = array.addObject();
            item.put("role", message.role());
            item.put("content", message.content());
        }
        return root;
    }

    public void write(Path file, ObjectMapper objectMapper) {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.toAbsolutePath().normalize().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, objectMapper.writeValueAsString(toJson(objectMapper)),
                    StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            try {
                Files.writeString(file, objectMapper.writeValueAsString(toJson(objectMapper)),
                        StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        }
    }

    public static EnforcerConversationContext read(Path file, ObjectMapper objectMapper) {
        if (file == null || !Files.exists(file)) {
            return empty();
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            return fromJson(root);
        } catch (Exception e) {
            return empty();
        }
    }

    public static EnforcerConversationContext fromJson(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return empty();
        }
        JsonNode messagesNode = root.isArray() ? root : root.path("messages");
        if (!messagesNode.isArray()) {
            return empty();
        }
        List<Message> messages = new ArrayList<>();
        for (JsonNode item : messagesNode) {
            if (item.isObject()) {
                String role = item.path("role").asText("unknown");
                String content = item.path("content").asText("");
                if (!content.isBlank()) {
                    messages.add(new Message(role, content));
                }
            } else if (item.isTextual() && !item.asText().isBlank()) {
                messages.add(new Message("message", item.asText()));
            }
        }
        return new EnforcerConversationContext(messages);
    }

    private static String truncateFromStart(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        return "... (truncated earlier context, " + text.length() + " chars total)\n"
                + text.substring(text.length() - Math.max(0, maxChars - 80));
    }
}
