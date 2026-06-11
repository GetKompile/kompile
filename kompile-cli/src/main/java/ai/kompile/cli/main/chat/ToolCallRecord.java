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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * A single indexed tool call record from a passthrough or emulated passthrough session.
 * Persisted as JSONL for efficient append and search.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ToolCallRecord {

    @JsonProperty("id")
    private String id;

    @JsonProperty("sessionId")
    private String sessionId;

    @JsonProperty("toolName")
    private String toolName;

    @JsonProperty("toolInput")
    private String toolInput;

    @JsonProperty("toolInputSummary")
    private String toolInputSummary;

    @JsonProperty("timestamp")
    private String timestamp;

    @JsonProperty("source")
    private String source;

    @JsonProperty("agentName")
    private String agentName;

    @JsonProperty("isError")
    private boolean isError;

    @JsonProperty("durationMs")
    private long durationMs;

    @JsonProperty("category")
    private String category;

    @JsonProperty("projectDirectory")
    private String projectDirectory;

    public ToolCallRecord() {}

    public ToolCallRecord(String id, String sessionId, String toolName, String toolInput,
                          String toolInputSummary, Instant timestamp, String source,
                          String agentName, boolean isError, long durationMs, String category,
                          String projectDirectory) {
        this.id = id;
        this.sessionId = sessionId;
        this.toolName = toolName;
        this.toolInput = toolInput;
        this.toolInputSummary = toolInputSummary;
        this.timestamp = timestamp.toString();
        this.source = source;
        this.agentName = agentName;
        this.isError = isError;
        this.durationMs = durationMs;
        this.category = category;
        this.projectDirectory = projectDirectory;
    }

    public String getId() { return id; }
    public String getSessionId() { return sessionId; }
    public String getToolName() { return toolName; }
    public String getToolInput() { return toolInput; }
    public String getToolInputSummary() { return toolInputSummary; }
    public String getTimestamp() { return timestamp; }
    @JsonIgnore
    public Instant getTimestampInstant() { return Instant.parse(timestamp); }
    public String getSource() { return source; }
    public String getAgentName() { return agentName; }
    public boolean isError() { return isError; }
    public long getDurationMs() { return durationMs; }
    public String getCategory() { return category; }
    public String getProjectDirectory() { return projectDirectory; }

    /**
     * Determine the category for a tool based on its name.
     */
    public static String categorize(String toolName) {
        if (toolName == null) return "general";
        String lower = toolName.toLowerCase();

        if (lower.equals("read") || lower.equals("write") || lower.equals("edit") || lower.equals("patch")
                || lower.contains("file") || lower.contains("directory") || lower.equals("glob")
                || lower.equals("list_files")) {
            return "filesystem";
        }
        if (lower.equals("bash") || lower.equals("shell") || lower.contains("command")) {
            return "shell";
        }
        if (lower.equals("grep") || lower.contains("search") || lower.equals("websearch")) {
            return "search";
        }
        if (lower.contains("rag") || lower.contains("query")) {
            return "rag";
        }
        if (lower.equals("agent") || lower.contains("subagent") || lower.contains("task")) {
            return "agent";
        }
        if (lower.contains("model") || lower.contains("embedding") || lower.contains("samediff")
                || lower.contains("nd4j")) {
            return "model";
        }
        if (lower.contains("index") || lower.contains("ingest")) {
            return "indexing";
        }
        if (lower.contains("document") || lower.contains("loader") || lower.contains("chunk")) {
            return "document";
        }
        if (lower.contains("web") || lower.equals("webfetch")) {
            return "web";
        }
        if (lower.contains("config") || lower.contains("setting") || lower.contains("profile")) {
            return "configuration";
        }
        if (lower.contains("notebook") || lower.equals("notebookedit")) {
            return "notebook";
        }
        if (lower.contains("todo") || lower.contains("plan")) {
            return "planning";
        }
        return "general";
    }
}
