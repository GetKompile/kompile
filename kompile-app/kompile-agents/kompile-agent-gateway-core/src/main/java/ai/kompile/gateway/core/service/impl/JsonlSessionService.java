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
package ai.kompile.gateway.core.service.impl;

import ai.kompile.gateway.core.service.SessionService;
import ai.kompile.react.model.ReActMessage;
import ai.kompile.react.model.ToolCall;
import ai.kompile.react.model.TokenUsage;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class JsonlSessionService implements SessionService {

    private Path sessionsDir;
    private final ObjectMapper objectMapper;

    private static final int CHARS_PER_TOKEN = 4;

    public JsonlSessionService(String workspace) throws IOException {
        this.sessionsDir = Path.of(workspace, "sessions");
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        Files.createDirectories(sessionsDir);
        log.info("Session storage initialized at: {}", sessionsDir);
    }

    public void setWorkspace(String workspace) throws IOException {
        this.sessionsDir = Path.of(workspace, "sessions");
        Files.createDirectories(sessionsDir);
        log.info("Session storage relocated to: {}", sessionsDir);
    }

    @Override
    public List<ReActMessage> loadSession(String sessionKey) {
        Path file = getSessionPath(sessionKey);
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }

        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines
                    .filter(line -> !line.isBlank())
                    .map(this::parseMessage)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Failed to load session: {}", sessionKey, e);
            return new ArrayList<>();
        }
    }

    @Override
    public void appendMessage(String sessionKey, ReActMessage message) {
        Path file = getSessionPath(sessionKey);
        try {
            String json = objectMapper.writeValueAsString(toMap(message));
            Files.write(file, (json + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.debug("Appended message to session: {}", sessionKey);
        } catch (IOException e) {
            log.error("Failed to append message to session: {}", sessionKey, e);
            throw new RuntimeException("Failed to append message", e);
        }
    }

    @Override
    public void appendMessages(String sessionKey, List<ReActMessage> messages) {
        Path file = getSessionPath(sessionKey);
        try {
            StringBuilder sb = new StringBuilder();
            for (ReActMessage message : messages) {
                String json = objectMapper.writeValueAsString(toMap(message));
                sb.append(json).append("\n");
            }
            Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.debug("Appended {} messages to session: {}", messages.size(), sessionKey);
        } catch (IOException e) {
            log.error("Failed to append messages to session: {}", sessionKey, e);
            throw new RuntimeException("Failed to append messages", e);
        }
    }

    @Override
    public void saveSession(String sessionKey, List<ReActMessage> messages) {
        Path file = getSessionPath(sessionKey);
        try {
            StringBuilder sb = new StringBuilder();
            for (ReActMessage message : messages) {
                String json = objectMapper.writeValueAsString(toMap(message));
                sb.append(json).append("\n");
            }
            Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.debug("Saved {} messages to session: {}", messages.size(), sessionKey);
        } catch (IOException e) {
            log.error("Failed to save session: {}", sessionKey, e);
            throw new RuntimeException("Failed to save session", e);
        }
    }

    @Override
    public void compactSession(String sessionKey, int maxTokens) {
        List<ReActMessage> messages = loadSession(sessionKey);
        if (messages.isEmpty()) {
            return;
        }

        int currentTokens = estimateTokens(messages);
        if (currentTokens < maxTokens) {
            return;
        }

        int splitIndex = messages.size() / 2;
        List<ReActMessage> oldMessages = messages.subList(0, splitIndex);
        List<ReActMessage> recentMessages = new ArrayList<>(messages.subList(splitIndex, messages.size()));

        String oldContent = oldMessages.stream()
                .map(m -> m.getRole() + ": " + m.getContent())
                .collect(Collectors.joining("\n"));

        ReActMessage summaryMessage = ReActMessage.builder()
                .id(UUID.randomUUID())
                .role(ReActMessage.Role.USER)
                .content("[Previous conversation summary]\n" +
                        "The conversation had " + oldMessages.size() + " messages with key topics: " +
                        extractKeyTopics(oldContent))
                .timestamp(Instant.now())
                .build();

        recentMessages.add(0, summaryMessage);
        saveSession(sessionKey, recentMessages);

        log.info("Compacted session {} from {} to {} tokens", sessionKey, currentTokens, estimateTokens(recentMessages));
    }

    @Override
    public void clearSession(String sessionKey) {
        Path file = getSessionPath(sessionKey);
        try {
            Files.deleteIfExists(file);
            log.debug("Cleared session: {}", sessionKey);
        } catch (IOException e) {
            log.error("Failed to clear session: {}", sessionKey, e);
        }
    }

    @Override
    public boolean sessionExists(String sessionKey) {
        Path file = getSessionPath(sessionKey);
        return Files.exists(file) && file.toFile().length() > 0;
    }

    @Override
    public int estimateTokenCount(String sessionKey) {
        List<ReActMessage> messages = loadSession(sessionKey);
        return estimateTokens(messages);
    }

    @Override
    public List<String> listSessions() {
        try (Stream<Path> files = Files.list(sessionsDir)) {
            return files
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .map(p -> p.getFileName().toString().replace(".jsonl", ""))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Failed to list sessions", e);
            return new ArrayList<>();
        }
    }

    private Path getSessionPath(String sessionKey) {
        String safeKey = sessionKey.replace(":", "_").replace("/", "_").replace("\\", "_");
        return sessionsDir.resolve(safeKey + ".jsonl");
    }

    private ReActMessage parseMessage(String line) {
        try {
            Map<String, Object> map = objectMapper.readValue(line, Map.class);
            return fromMap(map);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse message: {}", line, e);
            return ReActMessage.builder()
                    .role(ReActMessage.Role.USER)
                    .content("[Parse error]")
                    .build();
        }
    }

    private Map<String, Object> toMap(ReActMessage message) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", message.getId().toString());
        map.put("role", message.getRole().name());
        map.put("content", message.getContent());
        if (message.getThought() != null) {
            map.put("thought", message.getThought());
        }
        if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
            map.put("toolCalls", message.getToolCalls());
        }
        if (message.getToolCallId() != null) {
            map.put("toolCallId", message.getToolCallId());
        }
        if (message.getToolName() != null) {
            map.put("toolName", message.getToolName());
        }
        if (message.getToolSuccess() != null) {
            map.put("toolSuccess", message.getToolSuccess());
        }
        if (message.getToolError() != null) {
            map.put("toolError", message.getToolError());
        }
        if (message.getUsage() != null) {
            map.put("usage", message.getUsage());
        }
        map.put("timestamp", message.getTimestamp().toString());
        return map;
    }

    @SuppressWarnings("unchecked")
    private ReActMessage fromMap(Map<String, Object> map) {
        String idStr = (String) map.get("id");
        String roleStr = (String) map.get("role");
        String timestampStr = (String) map.get("timestamp");

        ReActMessage.ReActMessageBuilder builder = ReActMessage.builder()
                .id(idStr != null ? UUID.fromString(idStr) : UUID.randomUUID())
                .role(roleStr != null ? ReActMessage.Role.valueOf(roleStr) : ReActMessage.Role.ASSISTANT)
                .content((String) map.get("content"))
                .thought((String) map.get("thought"))
                .timestamp(timestampStr != null ? Instant.parse(timestampStr) : Instant.now());

        if (map.containsKey("toolCalls")) {
            List<Map<String, Object>> rawCalls = (List<Map<String, Object>>) map.get("toolCalls");
            if (rawCalls != null) {
                List<ToolCall> toolCalls = rawCalls.stream()
                        .map(tc -> ToolCall.builder()
                                .id(tc.containsKey("id") ? (String) tc.get("id") : UUID.randomUUID().toString())
                                .name((String) tc.get("name"))
                                .arguments((Map<String, Object>) tc.get("arguments"))
                                .rawArguments((String) tc.get("rawArguments"))
                                .build())
                        .collect(Collectors.toList());
                builder.toolCalls(toolCalls);
            }
        }
        if (map.containsKey("toolCallId")) {
            builder.toolCallId((String) map.get("toolCallId"));
        }
        if (map.containsKey("toolName")) {
            builder.toolName((String) map.get("toolName"));
        }
        if (map.containsKey("toolSuccess")) {
            builder.toolSuccess((Boolean) map.get("toolSuccess"));
        }
        if (map.containsKey("toolError")) {
            builder.toolError((String) map.get("toolError"));
        }
        if (map.containsKey("usage")) {
            Map<String, Object> rawUsage = (Map<String, Object>) map.get("usage");
            if (rawUsage != null) {
                builder.usage(TokenUsage.builder()
                        .promptTokens(toLong(rawUsage.get("promptTokens")))
                        .completionTokens(toLong(rawUsage.get("completionTokens")))
                        .totalTokens(toLong(rawUsage.get("totalTokens")))
                        .build());
            }
        }

        return builder.build();
    }

    private int estimateTokens(List<ReActMessage> messages) {
        return messages.stream()
                .mapToInt(m -> (m.getContent() != null ? m.getContent().length() : 0) / CHARS_PER_TOKEN)
                .sum();
    }

    private long toLong(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try { return Long.parseLong((String) value); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    private String extractKeyTopics(String content) {
        if (content.length() > 500) {
            return content.substring(0, 500) + "...";
        }
        return content;
    }
}
