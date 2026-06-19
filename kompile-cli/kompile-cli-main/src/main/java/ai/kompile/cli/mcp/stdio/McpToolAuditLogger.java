/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ToolCallIndex;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Persists MCP tool execution decisions into the shared tool-call catalog.
 * <p>
 * The Spring MCP registry writes to {@code McpActionLogService}, but CLI stdio
 * and daemon sessions execute tools in-process. This logger gives those contexts
 * the same searchable audit trail under {@code ~/.kompile/conversations/tool-calls}.
 */
public class McpToolAuditLogger {

    private final String sessionId;
    private final String agentName;
    private final String source;
    private final String projectDirectory;
    private final ObjectMapper objectMapper;

    public McpToolAuditLogger(String sessionId, String agentName, String source,
                              Path projectDirectory, ObjectMapper objectMapper) {
        this.sessionId = sessionId != null && !sessionId.isBlank()
                ? sessionId
                : "mcp-" + System.currentTimeMillis();
        this.agentName = agentName != null && !agentName.isBlank() ? agentName : "kompile-mcp";
        this.source = source != null && !source.isBlank() ? source : "mcp";
        this.projectDirectory = projectDirectory != null
                ? projectDirectory.toAbsolutePath().normalize().toString()
                : null;
        this.objectMapper = objectMapper != null ? objectMapper : JsonUtils.standardMapper();
    }

    public void recordExecuted(String toolName, Map<String, Object> arguments,
                               boolean isError, long durationMs) {
        record(toolName, arguments, null, "executed", null, isError, durationMs);
    }

    public void recordDecision(String toolName,
                               Map<String, Object> originalArguments,
                               Map<String, Object> effectiveArguments,
                               String decision,
                               String reason,
                               boolean isError,
                               long durationMs) {
        record(toolName, originalArguments, effectiveArguments, decision, reason, isError, durationMs);
    }

    private void record(String toolName,
                        Map<String, Object> originalArguments,
                        Map<String, Object> effectiveArguments,
                        String decision,
                        String reason,
                        boolean isError,
                        long durationMs) {
        try {
            String input = serializeInput(originalArguments, effectiveArguments, decision, reason);
            ToolCallIndex.getInstance().record(
                    sessionId,
                    toolName,
                    input,
                    agentName,
                    source,
                    isError,
                    Math.max(0, durationMs),
                    projectDirectory
            );
        } catch (Exception e) {
            System.err.println("[MCP] Failed to write tool audit record for " + toolName + ": " + e.getMessage());
        }
    }

    private String serializeInput(Map<String, Object> originalArguments,
                                  Map<String, Object> effectiveArguments,
                                  String decision,
                                  String reason) throws Exception {
        Map<String, Object> original = originalArguments != null
                ? new LinkedHashMap<>(originalArguments)
                : Map.of();

        boolean hasAuditMetadata = decision != null && !"executed".equals(decision)
                || (reason != null && !reason.isBlank())
                || (effectiveArguments != null && !Objects.equals(original, effectiveArguments));

        if (!hasAuditMetadata) {
            return objectMapper.writeValueAsString(original);
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("arguments", original);
        if (effectiveArguments != null && !Objects.equals(original, effectiveArguments)) {
            envelope.put("effectiveArguments", new LinkedHashMap<>(effectiveArguments));
        }

        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("decision", decision != null ? decision : "executed");
        if (reason != null && !reason.isBlank()) {
            audit.put("reason", reason);
        }
        audit.put("source", source);
        audit.put("agent", agentName);
        envelope.put("audit", audit);

        return objectMapper.writeValueAsString(envelope);
    }
}
