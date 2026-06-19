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
 * Builders for the JSONL event stream emitted by {@code kompile exec --json}.
 *
 * <p>Each event is a single-line JSON object with a {@code "type"} discriminator:
 * <ul>
 *   <li>{@code session} — emitted once at start: {@code session_id}, {@code model}, {@code cwd}</li>
 *   <li>{@code text}    — a streamed assistant text chunk: {@code text}</li>
 *   <li>{@code tool}    — a completed tool call: {@code name}, {@code ok}, {@code ms}</li>
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

    private static String write(ObjectMapper mapper, ObjectNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            // ObjectNode serialization should never fail; degrade to a minimal valid line.
            return "{\"type\":\"error\",\"message\":\"json-serialization-failed\"}";
        }
    }
}
