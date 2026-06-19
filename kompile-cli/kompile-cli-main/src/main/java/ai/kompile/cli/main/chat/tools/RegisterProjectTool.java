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

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.common.mcp.InstanceDiscovery;
import ai.kompile.cli.common.registry.InstanceInfo;
import ai.kompile.cli.common.registry.InstanceRegistry;
import ai.kompile.cli.main.codeindex.IndexFileStore;
import ai.kompile.cli.main.codeindex.LocalCodeIndexer;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * MCP tool for registering the current coding project with a kompile-app
 * endpoint. Pushes project metadata, code index stats, and optionally
 * triggers server-side indexing — making the project seamlessly available
 * for RAG, code search, and graph queries through the kompile platform.
 *
 * <p>Actions:</p>
 * <ul>
 *   <li>{@code register} — register (or re-register) the project with a kompile endpoint</li>
 *   <li>{@code status} — check registration status and connection health</li>
 *   <li>{@code discover} — list all discoverable kompile-app instances</li>
 *   <li>{@code sync} — push latest index metadata to the registered endpoint</li>
 *   <li>{@code activate} — set this project as the active project on the endpoint</li>
 * </ul>
 */
public class RegisterProjectTool implements CliTool {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    @Override
    public String id() {
        return "register_project";
    }

    @Override
    public String description() {
        return "Register the current coding project with a kompile endpoint. "
                + "Actions: 'register' (register project with endpoint, push metadata and index stats), "
                + "'status' (check registration and endpoint health), "
                + "'discover' (list all discoverable kompile-app instances), "
                + "'sync' (push latest code index metadata to the endpoint), "
                + "'activate' (set this project as the active project on the endpoint).";
    }

    @Override
    public String permissionKey() {
        return "register_project";
    }

    @Override
    public McpToolAnnotations mcpAnnotations() {
        return McpToolAnnotations.NETWORK;
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description",
                "Action: 'register' (register project with endpoint), "
                        + "'status' (check registration and health), "
                        + "'discover' (list kompile instances), "
                        + "'sync' (push index metadata), "
                        + "'activate' (set as active project).");

        ObjectNode url = props.putObject("url");
        url.put("type", "string");
        url.put("description",
                "Kompile-app endpoint URL (e.g. http://localhost:8080). "
                        + "If omitted, auto-discovers running instances.");

        ObjectNode directory = props.putObject("directory");
        directory.put("type", "string");
        directory.put("description",
                "Project directory to register (default: current working directory).");

        ObjectNode projectId = props.putObject("project_id");
        projectId.put("type", "string");
        projectId.put("description",
                "Project identifier (default: derived from directory name).");

        ObjectNode projectDescription = props.putObject("project_description");
        projectDescription.put("type", "string");
        projectDescription.put("description",
                "Human-readable project description.");

        ObjectNode tags = props.putObject("tags");
        tags.put("type", "string");
        tags.put("description",
                "Comma-separated tags for the project (e.g. 'java,spring,ml').");

        ObjectNode triggerIndex = props.putObject("trigger_index");
        triggerIndex.put("type", "boolean");
        triggerIndex.put("description",
                "If true, trigger server-side indexing after registration (default: false).");

        ObjectNode autoActivate = props.putObject("auto_activate");
        autoActivate.put("type", "boolean");
        autoActivate.put("description",
                "If true, activate the project after registration (default: true).");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        String action = params.has("action") ? params.get("action").asText() : "register";

        return switch (action) {
            case "register" -> doRegister(params, context);
            case "status" -> doStatus(params, context);
            case "discover" -> doDiscover(params, context);
            case "sync" -> doSync(params, context);
            case "activate" -> doActivate(params, context);
            default -> ToolResult.error("Unknown action: " + action
                    + ". Use: register, status, discover, sync, activate");
        };
    }

    // ── register ──────────────────────────────────────────────────────────

    private ToolResult doRegister(JsonNode params, ToolContext context) {
        try {
            Path dir = resolveDirectory(params, context);
            String pid = resolveProjectId(params, dir);
            String url = resolveEndpointUrl(params);
            if (url == null) {
                return ToolResult.error(
                        "No kompile-app instance found. Start one with 'kompile app start' "
                                + "or specify --url explicitly.");
            }

            KompileHttpClient client = new KompileHttpClient(url);
            if (!client.isHealthy()) {
                return ToolResult.error("Kompile endpoint at " + url
                        + " is not healthy (GET /actuator/health failed).");
            }

            // Build registration payload with metadata
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("directory", dir.toAbsolutePath().toString());
            payload.put("projectId", pid);
            payload.put("name", pid);
            if (params.has("project_description")) {
                payload.put("description", params.get("project_description").asText());
            }
            if (params.has("tags")) {
                payload.put("tags", params.get("tags").asText());
            }
            boolean autoIndex = params.has("trigger_index")
                    && params.get("trigger_index").asBoolean(false);
            payload.put("autoIndex", autoIndex);

            // Collect local index metadata if available
            Map<String, Object> indexMeta = collectIndexMetadata(pid);
            if (!indexMeta.isEmpty()) {
                payload.put("indexMetadata", indexMeta);
            }

            // Register with the endpoint
            client.put("/api/code-projects/auto-register", payload, Void.class);

            // Auto-activate by default
            boolean activate = !params.has("auto_activate")
                    || params.get("auto_activate").asBoolean(true);
            if (activate) {
                try {
                    client.post("/api/code-projects/" + pid + "/activate",
                            Map.of(), Void.class);
                } catch (Exception e) {
                    // Non-fatal — project is registered even if activation fails
                }
            }

            // Trigger server-side indexing if requested
            if (autoIndex) {
                try {
                    client.post("/api/code-projects/" + pid + "/index",
                            Map.of(), Void.class);
                } catch (Exception e) {
                    // Non-fatal
                }
            }

            // Build result
            StringBuilder sb = new StringBuilder();
            sb.append("Project registered successfully.\n\n");
            sb.append("  Endpoint:   ").append(url).append('\n');
            sb.append("  Project ID: ").append(pid).append('\n');
            sb.append("  Directory:  ").append(dir.toAbsolutePath()).append('\n');
            if (activate) {
                sb.append("  Active:     yes\n");
            }
            if (!indexMeta.isEmpty()) {
                sb.append("\nIndex metadata pushed:\n");
                appendIndexSummary(sb, indexMeta);
            }
            if (autoIndex) {
                sb.append("\nServer-side indexing triggered.\n");
            }
            sb.append("\nThe project is now available for code search, RAG, and graph queries ");
            sb.append("through the kompile platform.");

            return ToolResult.success("Project Registered", sb.toString());

        } catch (Exception e) {
            return ToolResult.error("Registration failed: " + e.getMessage());
        }
    }

    // ── status ────────────────────────────────────────────────────────────

    private ToolResult doStatus(JsonNode params, ToolContext context) {
        try {
            Path dir = resolveDirectory(params, context);
            String pid = resolveProjectId(params, dir);

            StringBuilder sb = new StringBuilder();

            // Check persisted registration record
            ai.kompile.cli.common.registry.ProjectRegistration reg =
                    ai.kompile.cli.common.registry.ProjectRegistration.loadFromProject(dir);
            if (reg != null) {
                sb.append("Registration Record:\n");
                sb.append("  Endpoint:     ").append(reg.getEndpointUrl()).append('\n');
                sb.append("  Project ID:   ").append(reg.getProjectId()).append('\n');
                sb.append("  Directory:    ").append(reg.getDirectory()).append('\n');
                sb.append("  Registered:   ").append(reg.getRegisteredAt()).append('\n');
                sb.append("  Active:       ").append(reg.isActive()).append('\n');
                if (reg.getSource() != null) {
                    sb.append("  Source:       ").append(reg.getSource()).append('\n');
                }
                sb.append('\n');
            } else {
                sb.append("Registration Record: none (project not yet registered)\n\n");
            }

            // Use the persisted endpoint URL if no explicit URL given
            String url = resolveEndpointUrl(params);
            if (url == null && reg != null) {
                url = reg.getEndpointUrl();
            }

            // Local index status
            Path indexDir = LocalCodeIndexer.getIndexDir(pid);
            boolean hasLocalIndex = Files.exists(indexDir.resolve("index.db"));
            sb.append("Local Index:\n");
            if (hasLocalIndex) {
                Map<String, Object> meta = collectIndexMetadata(pid);
                sb.append("  Status:   indexed\n");
                sb.append("  Location: ").append(indexDir).append('\n');
                appendIndexSummary(sb, meta);
            } else {
                sb.append("  Status: not indexed\n");
                sb.append("  Run 'local_code_index' with action='index' to create a local index.\n");
            }

            // Remote endpoint status
            sb.append("\nRemote Endpoint:\n");
            if (url == null) {
                sb.append("  Status: no kompile-app instance discovered\n");
                sb.append("  Start one with 'kompile app start' or specify a URL.\n");
            } else {
                KompileHttpClient client = new KompileHttpClient(url);
                boolean healthy = client.isHealthy();
                sb.append("  URL:    ").append(url).append('\n');
                sb.append("  Health: ").append(healthy ? "UP" : "DOWN").append('\n');

                if (healthy) {
                    // Check if project is registered
                    try {
                        String projectJson = client.getString(
                                "/api/code-projects/" + pid);
                        sb.append("  Project '").append(pid).append("': registered\n");

                        // Parse out useful fields
                        JsonNode project = MAPPER.readTree(projectJson);
                        if (project.has("indexState")) {
                            sb.append("  Index State: ").append(
                                    project.get("indexState").asText()).append('\n');
                        }
                        if (project.has("isActive")) {
                            sb.append("  Active: ").append(
                                    project.get("isActive").asBoolean()).append('\n');
                        }
                        if (project.has("totalFiles")) {
                            sb.append("  Files: ").append(
                                    project.get("totalFiles").asInt()).append('\n');
                        }
                    } catch (Exception e) {
                        sb.append("  Project '").append(pid)
                                .append("': not registered\n");
                    }
                }
            }

            return ToolResult.success("Registration Status", sb.toString());

        } catch (Exception e) {
            return ToolResult.error("Status check failed: " + e.getMessage());
        }
    }

    // ── discover ──────────────────────────────────────────────────────────

    private ToolResult doDiscover(JsonNode params, ToolContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Kompile Instance Discovery\n\n");

        // Registry entries
        try {
            List<InstanceInfo> instances = InstanceRegistry.listAll();
            if (instances.isEmpty()) {
                sb.append("Registry: no registered instances\n");
            } else {
                sb.append("Registry (").append(instances.size()).append(" entries):\n");
                for (InstanceInfo info : instances) {
                    sb.append("  ").append(info.getName())
                            .append(" — ").append(info.getType())
                            .append(" @ ").append(info.getUrl())
                            .append(" (pid ").append(info.getPid()).append(")\n");
                    // Probe health
                    try {
                        KompileHttpClient client = new KompileHttpClient(info.getUrl());
                        sb.append("    health: ")
                                .append(client.isHealthy() ? "UP" : "DOWN")
                                .append('\n');
                    } catch (Exception e) {
                        sb.append("    health: UNREACHABLE\n");
                    }
                }
            }
        } catch (Exception e) {
            sb.append("Registry: unavailable (").append(e.getMessage()).append(")\n");
        }

        // Port scan
        sb.append("\nPort scan:\n");
        int[] probePorts = {8080, 8081, 9090, 9091};
        boolean foundAny = false;
        for (int port : probePorts) {
            try {
                KompileHttpClient client = new KompileHttpClient(
                        "http://localhost:" + port);
                if (client.isHealthy()) {
                    sb.append("  localhost:").append(port).append(" — UP\n");
                    foundAny = true;
                }
            } catch (Exception ignored) {
            }
        }
        if (!foundAny) {
            sb.append("  No instances found on standard ports.\n");
        }

        return ToolResult.success("Instance Discovery", sb.toString());
    }

    // ── sync ──────────────────────────────────────────────────────────────

    private ToolResult doSync(JsonNode params, ToolContext context) {
        try {
            Path dir = resolveDirectory(params, context);
            String pid = resolveProjectId(params, dir);
            String url = resolveEndpointUrl(params, dir);
            if (url == null) {
                return ToolResult.error("No kompile-app instance found.");
            }

            Map<String, Object> indexMeta = collectIndexMetadata(pid);
            if (indexMeta.isEmpty()) {
                return ToolResult.error(
                        "No local index found for project '" + pid
                                + "'. Run local_code_index with action='index' first.");
            }

            KompileHttpClient client = new KompileHttpClient(url);
            if (!client.isHealthy()) {
                return ToolResult.error("Endpoint at " + url + " is not healthy.");
            }

            // Push updated metadata
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("directory", dir.toAbsolutePath().toString());
            payload.put("projectId", pid);
            payload.put("name", pid);
            payload.put("indexMetadata", indexMeta);

            client.put("/api/code-projects/auto-register", payload, Void.class);

            StringBuilder sb = new StringBuilder();
            sb.append("Index metadata synced to ").append(url).append(".\n\n");
            appendIndexSummary(sb, indexMeta);

            return ToolResult.success("Metadata Synced", sb.toString());

        } catch (Exception e) {
            return ToolResult.error("Sync failed: " + e.getMessage());
        }
    }

    // ── activate ──────────────────────────────────────────────────────────

    private ToolResult doActivate(JsonNode params, ToolContext context) {
        try {
            Path dir = resolveDirectory(params, context);
            String pid = resolveProjectId(params, dir);
            String url = resolveEndpointUrl(params, dir);
            if (url == null) {
                return ToolResult.error("No kompile-app instance found.");
            }

            KompileHttpClient client = new KompileHttpClient(url);
            client.post("/api/code-projects/" + pid + "/activate",
                    Map.of(), Void.class);

            return ToolResult.success("Project Activated",
                    "Project '" + pid + "' is now the active project on " + url + ".");

        } catch (Exception e) {
            return ToolResult.error("Activation failed: " + e.getMessage());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private Path resolveDirectory(JsonNode params, ToolContext context) {
        if (params.has("directory") && !params.get("directory").asText().isBlank()) {
            return Paths.get(params.get("directory").asText()).toAbsolutePath();
        }
        return context.getWorkingDirectory().toAbsolutePath();
    }

    private String resolveProjectId(JsonNode params, Path dir) {
        if (params.has("project_id") && !params.get("project_id").asText().isBlank()) {
            return params.get("project_id").asText();
        }
        return dir.getFileName().toString();
    }

    private String resolveEndpointUrl(JsonNode params) {
        return resolveEndpointUrl(params, null);
    }

    private String resolveEndpointUrl(JsonNode params, Path projectDir) {
        if (params.has("url") && !params.get("url").asText().isBlank()) {
            return params.get("url").asText();
        }
        // Check persisted registration for this project
        if (projectDir != null) {
            ai.kompile.cli.common.registry.ProjectRegistration reg =
                    ai.kompile.cli.common.registry.ProjectRegistration.loadFromProject(projectDir);
            if (reg != null && reg.getEndpointUrl() != null) {
                return reg.getEndpointUrl();
            }
        }
        return InstanceDiscovery.discover();
    }

    /**
     * Collect code index metadata from the local index for the given project.
     * Returns an empty map if no local index exists.
     */
    private Map<String, Object> collectIndexMetadata(String projectId) {
        try {
            Path indexDir = LocalCodeIndexer.getIndexDir(projectId);
            if (!Files.exists(indexDir)) {
                return Map.of();
            }
            IndexFileStore store = new IndexFileStore(indexDir, MAPPER);
            Map<String, Object> meta = store.loadMetadata();
            if (meta.isEmpty()) {
                return Map.of();
            }

            // Build a clean summary to push
            Map<String, Object> summary = new LinkedHashMap<>();
            copyIfPresent(meta, summary, "filesProcessed");
            copyIfPresent(meta, summary, "entitiesFound");
            copyIfPresent(meta, summary, "errors");
            copyIfPresent(meta, summary, "indexedAt");
            copyIfPresent(meta, summary, "languageCounts");
            copyIfPresent(meta, summary, "filesSkipped");
            copyIfPresent(meta, summary, "filesDeleted");
            copyIfPresent(meta, summary, "filesReindexed");
            return summary;

        } catch (Exception e) {
            return Map.of();
        }
    }

    private void copyIfPresent(Map<String, Object> src, Map<String, Object> dst,
                               String key) {
        if (src.containsKey(key)) {
            dst.put(key, src.get(key));
        }
    }

    private void appendIndexSummary(StringBuilder sb, Map<String, Object> meta) {
        if (meta.containsKey("filesProcessed")) {
            sb.append("  Files:    ").append(meta.get("filesProcessed")).append('\n');
        }
        if (meta.containsKey("entitiesFound")) {
            sb.append("  Entities: ").append(meta.get("entitiesFound")).append('\n');
        }
        if (meta.containsKey("indexedAt")) {
            sb.append("  Indexed:  ").append(meta.get("indexedAt")).append('\n');
        }
        if (meta.containsKey("languageCounts")) {
            sb.append("  Languages: ").append(meta.get("languageCounts")).append('\n');
        }
        if (meta.containsKey("errors")) {
            Object errors = meta.get("errors");
            if (errors instanceof Number && ((Number) errors).intValue() > 0) {
                sb.append("  Errors:   ").append(errors).append('\n');
            }
        }
    }
}
