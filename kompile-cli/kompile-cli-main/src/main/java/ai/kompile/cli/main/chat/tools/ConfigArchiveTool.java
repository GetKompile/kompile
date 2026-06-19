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

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.config.ComponentFilter;
import ai.kompile.cli.common.config.ConfigArchiveManifest;
import ai.kompile.cli.common.config.ConfigArchiveService;
import ai.kompile.cli.common.config.ArchiveInfo;
import ai.kompile.cli.common.config.ConfigArchiveService.ImportResult;
import ai.kompile.cli.common.config.ImportMode;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * MCP tool for managing Kompile configuration archives.
 *
 * <p>Supports exporting, importing, listing, previewing, and deleting
 * configuration archives that bundle kompile configs, chat provider settings
 * (Claude, Codex, Qwen, OpenCode, Gemini), and system prompts.
 *
 * <p>Exposed via both the CLI chat tool registry (passthrough/emulated passthrough)
 * and the MCP stdio server.
 */
public class ConfigArchiveTool implements CliTool {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    @Override
    public String id() { return "config_archive"; }

    @Override
    public String description() {
        return "Manage Kompile configuration archives. Export, import, list, preview, and delete "
                + "archives that bundle kompile app configs, chat provider settings "
                + "(Claude, Codex, Qwen, OpenCode, Gemini), system prompts, and harness configs. "
                + "Actions: 'export' (save current configs to archive), "
                + "'import' (restore configs from archive), "
                + "'list' (show saved archives), "
                + "'preview' (show archive contents without importing), "
                + "'delete' (remove a saved archive). "
                + "Import supports 'append' mode (merge with existing, keep existing non-JSON) "
                + "and 'override' mode (replace existing). "
                + "Use 'components' to filter which config categories to include.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        addStringProp(props, "action",
                "Action to perform: 'export', 'import', 'list', 'preview', or 'delete'");
        addStringProp(props, "description",
                "Description to embed in the archive manifest (for 'export')");
        addStringProp(props, "fileName",
                "Archive file name for 'import', 'preview', or 'delete' "
                        + "(from ~/.kompile/archives/). Use 'list' to see available files.");
        addStringProp(props, "mode",
                "Import mode: 'append' (merge with existing configs, default) "
                        + "or 'override' (replace existing configs)");

        ObjectNode componentsNode = props.putObject("components");
        componentsNode.put("type", "array");
        componentsNode.put("description",
                "Components to include (default: all). Valid values: "
                        + "kompile-app-configs, kompile-chat-config, kompile-harness-config, "
                        + "kompile-other-configs, system-prompts, claude, codex, qwen, opencode, gemini, pi");
        componentsNode.putObject("items").put("type", "string");

        schema.putArray("required").add("action");
        return schema;
    }

    private static void addStringProp(ObjectNode props, String name, String desc) {
        ObjectNode p = props.putObject(name);
        p.put("type", "string");
        p.put("description", desc);
    }

    @Override
    public String permissionKey() { return "config"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Manage configuration archives");

        String action = params.path("action").asText("");
        try {
            return switch (action) {
                case "export" -> doExport(params);
                case "import" -> doImport(params);
                case "list" -> doList();
                case "preview" -> doPreview(params);
                case "delete" -> doDelete(params);
                default -> ToolResult.error("Unknown action: '" + action
                        + "'. Use: export, import, list, preview, or delete.");
            };
        } catch (Exception e) {
            return ToolResult.error("Config archive error: " + e.getMessage());
        }
    }

    private ToolResult doExport(JsonNode params) throws Exception {
        String description = params.path("description").asText(null);
        ComponentFilter filter = buildFilter(params);

        Path archivePath = ConfigArchiveService.exportArchive(null, description, filter);
        ConfigArchiveManifest manifest = ConfigArchiveService.readManifest(archivePath);

        StringBuilder sb = new StringBuilder();
        sb.append("Archive created: ").append(archivePath.getFileName()).append("\n");
        sb.append("Path: ").append(archivePath).append("\n\n");
        appendManifestSummary(sb, manifest);

        return ToolResult.success("config_archive: exported",
                sb.toString(),
                Map.of("fileName", archivePath.getFileName().toString(),
                        "path", archivePath.toString(),
                        "configCount", manifest.getKompileConfigs().size(),
                        "providerCount", manifest.getChatProviderConfigs().size()));
    }

    private ToolResult doImport(JsonNode params) throws Exception {
        String fileName = params.path("fileName").asText("");
        if (fileName.isEmpty()) {
            return ToolResult.error("'fileName' is required for import. Use 'list' to see available archives.");
        }

        // Validate filename
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            return ToolResult.error("Invalid file name: " + fileName);
        }

        Path archivePath = ai.kompile.cli.common.KompileHome.homeDirectory()
                .toPath().resolve("archives").resolve(fileName);
        if (!Files.isRegularFile(archivePath)) {
            return ToolResult.error("Archive not found: " + fileName
                    + ". Use 'list' to see available archives.");
        }

        String modeStr = params.path("mode").asText("append");
        ImportMode mode;
        try {
            mode = ImportMode.valueOf(modeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Invalid mode: '" + modeStr + "'. Use 'append' or 'override'.");
        }

        ComponentFilter filter = buildFilter(params);
        ImportResult result = ConfigArchiveService.importArchive(archivePath, mode, filter);

        StringBuilder sb = new StringBuilder();
        sb.append("Import complete (mode: ").append(modeStr.toLowerCase()).append(")\n\n");
        if (!result.getCreated().isEmpty()) {
            sb.append("Created (").append(result.getCreated().size()).append("):\n");
            for (String f : result.getCreated()) sb.append("  + ").append(f).append("\n");
        }
        if (!result.getOverwritten().isEmpty()) {
            sb.append("Overwritten (").append(result.getOverwritten().size()).append("):\n");
            for (String f : result.getOverwritten()) sb.append("  ~ ").append(f).append("\n");
        }
        if (!result.getMerged().isEmpty()) {
            sb.append("Merged (").append(result.getMerged().size()).append("):\n");
            for (String f : result.getMerged()) sb.append("  * ").append(f).append("\n");
        }
        if (!result.getSkipped().isEmpty()) {
            sb.append("Skipped (").append(result.getSkipped().size()).append("):\n");
            for (String f : result.getSkipped()) sb.append("  - ").append(f).append("\n");
        }
        sb.append("\nTotal processed: ").append(result.totalProcessed()).append(" files");

        return ToolResult.success("config_archive: imported " + fileName,
                sb.toString(),
                Map.of("mode", modeStr.toLowerCase(),
                        "created", result.getCreated().size(),
                        "overwritten", result.getOverwritten().size(),
                        "merged", result.getMerged().size(),
                        "skipped", result.getSkipped().size(),
                        "totalProcessed", result.totalProcessed()));
    }

    private ToolResult doList() throws Exception {
        List<ArchiveInfo> archives = ConfigArchiveService.listArchives();
        if (archives.isEmpty()) {
            return ToolResult.success("config_archive: list",
                    "No saved archives found in ~/.kompile/archives/\n"
                            + "Use action='export' to create one.",
                    Map.of("total", 0));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Saved archives (").append(archives.size()).append("):\n\n");
        for (ArchiveInfo info : archives) {
            sb.append("  ").append(info.getFileName());
            sb.append("  (").append(formatSize(info.getSizeBytes())).append(")");
            sb.append("  ").append(info.getLastModified()).append("\n");
            ConfigArchiveManifest m = info.getManifest();
            if (m != null) {
                int configCount = m.getKompileConfigs() != null ? m.getKompileConfigs().size() : 0;
                Set<String> providers = m.getChatProviderConfigs() != null
                        ? m.getChatProviderConfigs().keySet() : Set.of();
                int promptCount = m.getSystemPrompts() != null ? m.getSystemPrompts().size() : 0;
                sb.append("    ").append(configCount).append(" configs");
                if (!providers.isEmpty()) {
                    sb.append(", providers: ").append(String.join(", ", providers));
                }
                if (promptCount > 0) {
                    sb.append(", ").append(promptCount).append(" prompts");
                }
                if (m.getDescription() != null) {
                    sb.append(" — ").append(m.getDescription());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        return ToolResult.success("config_archive: list",
                sb.toString(),
                Map.of("total", archives.size()));
    }

    private ToolResult doPreview(JsonNode params) throws Exception {
        String fileName = params.path("fileName").asText("");
        if (fileName.isEmpty()) {
            return ToolResult.error("'fileName' is required for preview. Use 'list' to see available archives.");
        }

        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            return ToolResult.error("Invalid file name: " + fileName);
        }

        Path archivePath = ai.kompile.cli.common.KompileHome.homeDirectory()
                .toPath().resolve("archives").resolve(fileName);
        if (!Files.isRegularFile(archivePath)) {
            return ToolResult.error("Archive not found: " + fileName);
        }

        ConfigArchiveManifest manifest = ConfigArchiveService.readManifest(archivePath);

        StringBuilder sb = new StringBuilder();
        sb.append("Archive: ").append(fileName).append("\n");
        sb.append("Created: ").append(manifest.getCreatedAt()).append("\n");
        sb.append("Host:    ").append(manifest.getHostname()).append("\n");
        if (manifest.getDescription() != null) {
            sb.append("Note:    ").append(manifest.getDescription()).append("\n");
        }
        sb.append("\n");
        appendManifestSummary(sb, manifest);

        return ToolResult.success("config_archive: preview " + fileName,
                sb.toString(),
                Map.of("fileName", fileName,
                        "createdAt", manifest.getCreatedAt(),
                        "hostname", manifest.getHostname()));
    }

    private ToolResult doDelete(JsonNode params) throws Exception {
        String fileName = params.path("fileName").asText("");
        if (fileName.isEmpty()) {
            return ToolResult.error("'fileName' is required for delete. Use 'list' to see available archives.");
        }

        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            return ToolResult.error("Invalid file name: " + fileName);
        }

        boolean deleted = ConfigArchiveService.deleteArchive(fileName);
        if (deleted) {
            return ToolResult.success("config_archive: deleted " + fileName,
                    "Archive deleted: " + fileName,
                    Map.of("fileName", fileName, "deleted", true));
        } else {
            return ToolResult.error("Archive not found: " + fileName);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private ComponentFilter buildFilter(JsonNode params) {
        JsonNode componentsNode = params.path("components");
        if (!componentsNode.isArray() || componentsNode.isEmpty()) {
            return ComponentFilter.all();
        }

        ComponentFilter filter = ComponentFilter.none();
        for (JsonNode c : componentsNode) {
            String component = c.asText("");
            if (!component.isEmpty()) {
                filter.enable(component);
            }
        }
        return filter.hasAnyEnabled() ? filter : ComponentFilter.all();
    }

    private void appendManifestSummary(StringBuilder sb, ConfigArchiveManifest manifest) {
        List<String> configs = manifest.getKompileConfigs();
        if (configs != null && !configs.isEmpty()) {
            sb.append("Kompile configs (").append(configs.size()).append("):\n");
            for (String c : configs) {
                sb.append("  - ").append(c).append("\n");
            }
        }

        Map<String, List<String>> providers = manifest.getChatProviderConfigs();
        if (providers != null && !providers.isEmpty()) {
            sb.append("\nChat provider configs:\n");
            for (Map.Entry<String, List<String>> e : providers.entrySet()) {
                sb.append("  ").append(e.getKey()).append(":\n");
                for (String f : e.getValue()) {
                    sb.append("    - ").append(f).append("\n");
                }
            }
        }

        List<String> prompts = manifest.getSystemPrompts();
        if (prompts != null && !prompts.isEmpty()) {
            sb.append("\nSystem prompts (").append(prompts.size()).append("):\n");
            for (String p : prompts) {
                sb.append("  - ").append(p).append("\n");
            }
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
