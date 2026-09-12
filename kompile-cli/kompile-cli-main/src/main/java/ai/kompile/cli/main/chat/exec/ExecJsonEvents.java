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

package ai.kompile.cli.main.chat.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builders for the JSONL event stream emitted by {@code kompile exec --json}
 * and {@code kompile chat --output-format stream-json}.
 *
 * <p>Each event is a single-line JSON object with a {@code "type"} discriminator:
 * <ul>
 *   <li>{@code session} — emitted once at start with effective non-secret configuration</li>
 *   <li>{@code text}    — a streamed assistant text chunk: {@code text}</li>
 *   <li>{@code tool_start}/{@code tool} — tool lifecycle records</li>
 *   <li>{@code usage}   — provider-reported token counts</li>
 *   <li>{@code result}  — emitted once at end: full {@code text}, {@code session_id}, {@code tools}, {@code exit}</li>
 *   <li>{@code error}   — a fatal error: {@code message}</li>
 * </ul>
 *
 * <p>Pure (string in, single-line JSON out) so the wire format is unit-testable
 * without running the agent. Jackson handles all escaping.
 */
public final class ExecJsonEvents {

    private ExecJsonEvents() {}

    /** {@code {"type":"session","session_id":...,"model":...,"cwd":...}} */
    public static String session(ObjectMapper mapper, String sessionId, String model, String cwd) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "session");
        n.put("session_id", sessionId);
        if (model != null) n.put("model", model);
        if (cwd != null) n.put("cwd", cwd);
        return write(mapper, n);
    }

    /** {@code {"type":"text","text":...}} — a streamed assistant text chunk. */
    public static String text(ObjectMapper mapper, String chunk) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "text");
        n.put("text", chunk == null ? "" : chunk);
        return write(mapper, n);
    }

    /** {@code {"type":"tool","name":...,"ok":bool,"ms":long}} — a completed tool call. */
    public static String tool(ObjectMapper mapper, String name, boolean ok, long ms) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "tool");
        n.put("name", name);
        n.put("ok", ok);
        n.put("ms", ms);
        return write(mapper, n);
    }

    /** {@code {"type":"result","text":...,"session_id":...,"tools":int,"exit":int}} — the final event. */
    public static String result(ObjectMapper mapper, String text, String sessionId, int tools, int exit) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "result");
        n.put("text", text == null ? "" : text);
        n.put("session_id", sessionId);
        n.put("tools", tools);
        n.put("exit", exit);
        return write(mapper, n);
    }

    /** {@code {"type":"error","message":...}} */
    public static String error(ObjectMapper mapper, String message) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "error");
        n.put("message", message == null ? "" : message);
        return write(mapper, n);
    }

    /** Serialize a rich headless event while retaining the existing JSONL vocabulary. */
    public static String event(ObjectMapper mapper, HeadlessRunEvent event) {
        ObjectNode n = mapper.createObjectNode();
        if (event == null) {
            return error(mapper, "null-event");
        }
        n.put("seq", event.sequence());
        switch (event.type()) {
            case RUN_STARTED -> {
                n.put("type", "session");
                n.put("session_id", event.sessionId());
                if (!event.toolName().isBlank()) n.put("model", event.toolName());
                if (!event.rawInput().isBlank()) n.put("cwd", event.rawInput());
                event.metadata().forEach((key, value) -> {
                    if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                        if ("rag".equals(key) || "memory".equals(key)) {
                            n.put(key, Boolean.parseBoolean(value));
                        } else {
                            n.put(key, value);
                        }
                    }
                });
            }
            case BACKEND_STARTED -> {
                n.put("type", "backend");
                n.put("session_id", event.sessionId());
                if (!event.toolName().isBlank()) n.put("agent", event.toolName());
                if (!event.callId().isBlank()) n.put("process_id", event.callId());
            }
            case SOURCES -> {
                n.put("type", "sources");
                putJson(n, "sources", event.text(), mapper);
            }
            case STATS -> {
                n.put("type", "stats");
                putJson(n, "stats", event.text(), mapper);
            }
            case ASSISTANT_DELTA -> {
                n.put("type", "text");
                n.put("text", event.text());
            }
            case TOOL_STARTED -> {
                n.put("type", "tool_start");
                n.put("call_id", event.callId());
                n.put("name", event.toolName());
                if (!event.rawInput().isBlank()) n.put("input", event.rawInput());
            }
            case TOOL_COMPLETED -> {
                n.put("type", "tool");
                n.put("call_id", event.callId());
                n.put("name", event.toolName());
                n.put("ok", event.ok());
                n.put("ms", event.durationMs());
            }
            case TOKEN_USAGE -> {
                n.put("type", "usage");
                putLong(n, "input_tokens", event.metadata().get("input_tokens"));
                putLong(n, "output_tokens", event.metadata().get("output_tokens"));
                putLong(n, "cache_read_tokens", event.metadata().get("cache_read_tokens"));
                putLong(n, "cache_creation_tokens", event.metadata().get("cache_creation_tokens"));
            }
            case COMMAND_OUTCOME -> {
                n.put("type", "command");
                n.put("session_id", event.sessionId());
                n.put("protocol_version", parseInt(event.metadata().get("protocol_version")));
                n.put("command", event.metadata().get("command"));
                n.put("status", event.metadata().get("status"));
                n.put("text", event.text());
                n.put("ok", event.ok());
                n.put("exit", event.exitCode());
                if (event.data() != null) {
                    n.set("data", event.data());
                }
            }
            case RUN_COMPLETED -> {
                n.put("type", "result");
                n.put("text", event.text());
                n.put("session_id", event.sessionId());
                n.put("tools", parseInt(event.message()));
                n.put("exit", event.exitCode());
            }
            case RUN_FAILED -> {
                n.put("type", "error");
                n.put("message", event.message());
                n.put("exit", event.exitCode());
            }
            case RUN_DETACHED -> {
                n.put("type", "detached");
                n.put("session_id", event.sessionId());
                n.put("message", event.message());
            }
        }
        return write(mapper, n);
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static void putLong(ObjectNode node, String field, String value) {
        try {
            node.put(field, Math.max(0L, Long.parseLong(value)));
        } catch (RuntimeException ignored) {
            node.put(field, 0L);
        }
    }

    private static void putJson(
            ObjectNode node, String field, String value, ObjectMapper mapper) {
        try {
            node.set(field, mapper.readTree(value));
        } catch (Exception ignored) {
            node.put(field, value == null ? "" : value);
        }
    }

    private static String write(ObjectMapper mapper, ObjectNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            // ObjectNode serialization should never fail; degrade to a minimal valid line.
            return "{\"type\":\"error\",\"message\":\"json-serialization-failed\"}";
        }
    }
}
