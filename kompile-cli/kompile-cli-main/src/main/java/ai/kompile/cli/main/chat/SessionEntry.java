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

package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single tracked agent session entry.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionEntry {

    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private String kompileSessionId;
    private String conversationId;
    private String agent;
    private String projectDirectory;
    private String launchMode;
    private String startedAt;
    private String endedAt;
    private String status;
    private long pid;
    private String title;
    private Map<String, String> extra;

    /**
     * Check if the process that ran this session is still alive.
     */
    public boolean isProcessAlive() {
        if (pid <= 0) return false;
        try {
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    public ObjectNode toJson() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("kompileSessionId", kompileSessionId);
        node.put("conversationId", conversationId != null ? conversationId : "");
        node.put("agent", agent);
        node.put("projectDirectory", projectDirectory);
        node.put("launchMode", launchMode);
        node.put("startedAt", startedAt);
        node.put("endedAt", endedAt != null ? endedAt : "");
        node.put("status", status);
        node.put("pid", pid);
        node.put("title", title != null ? title : "");
        if (extra != null && !extra.isEmpty()) {
            ObjectNode extraNode = node.putObject("extra");
            extra.forEach(extraNode::put);
        }
        return node;
    }

    public static SessionEntry fromJson(ObjectNode node) {
        SessionEntry e = new SessionEntry();
        e.kompileSessionId = node.path("kompileSessionId").asText("");
        e.conversationId = node.path("conversationId").asText("");
        e.agent = node.path("agent").asText("");
        e.projectDirectory = node.path("projectDirectory").asText("");
        e.launchMode = node.path("launchMode").asText("");
        e.startedAt = node.path("startedAt").asText("");
        e.endedAt = node.path("endedAt").asText("");
        e.status = node.path("status").asText("unknown");
        e.pid = node.path("pid").asLong(0);
        e.title = node.path("title").asText("");
        e.extra = new LinkedHashMap<>();
        if (node.has("extra") && node.get("extra").isObject()) {
            node.get("extra").fields().forEachRemaining(
                    field -> e.extra.put(field.getKey(), field.getValue().asText("")));
        }
        return e;
    }

    @Override
    public String toString() {
        return String.format("[%s] %s %s @ %s (%s) — %s",
                kompileSessionId, agent, launchMode, projectDirectory, status,
                title != null && !title.isEmpty() ? title : "(untitled)");
    }
}
