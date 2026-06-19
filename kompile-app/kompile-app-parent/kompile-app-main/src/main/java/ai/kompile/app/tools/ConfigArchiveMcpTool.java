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

package ai.kompile.app.tools;

import ai.kompile.cli.common.config.ComponentFilter;
import ai.kompile.cli.common.config.ConfigArchiveManifest;
import ai.kompile.cli.common.config.ConfigArchiveService;
import ai.kompile.cli.common.config.ArchiveInfo;
import ai.kompile.cli.common.config.ConfigArchiveService.ImportResult;
import ai.kompile.cli.common.config.ImportMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * MCP tool for managing Kompile configuration archives via the kompile-app SSE server.
 *
 * <p>Exposes config archive export, import, list, preview, and delete operations
 * as Spring AI @Tool methods, available to all agents connected via the MCP SSE endpoint.
 */
@Component
public class ConfigArchiveMcpTool {

    private static final Logger logger = LoggerFactory.getLogger(ConfigArchiveMcpTool.class);

    // ── Input records ──────────────────────────────────────────────

    public record ExportInput(String description, List<String> components) {}
    public record ImportInput(String fileName, String mode, List<String> components) {}
    public record ListInput() {}
    public record PreviewInput(String fileName) {}
    public record DeleteInput(String fileName) {}

    // ── Tool methods ───────────────────────────────────────────────

    @Tool(name = "config_archive_export",
            description = "Export current Kompile configuration to a zip archive. "
                    + "Bundles kompile app configs, chat config, harness config, system prompts, "
                    + "and chat provider settings (Claude, Codex, Qwen, OpenCode, Gemini). "
                    + "Optionally provide a description and a list of components to include.")
    public Map<String, Object> exportArchive(ExportInput input) {
        try {
            ComponentFilter filter = buildFilter(input.components());
            Path archivePath = ConfigArchiveService.exportArchive(null, input.description(), filter);
            ConfigArchiveManifest manifest = ConfigArchiveService.readManifest(archivePath);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("fileName", archivePath.getFileName().toString());
            response.put("path", archivePath.toString());
            response.put("kompileConfigs", manifest.getKompileConfigs());
            response.put("chatProviders", manifest.getChatProviderConfigs().keySet());
            response.put("systemPrompts", manifest.getSystemPrompts());
            if (manifest.getDescription() != null) {
                response.put("description", manifest.getDescription());
            }
            return response;
        } catch (Exception e) {
            logger.error("Failed to export config archive", e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "config_archive_import",
            description = "Import a saved Kompile configuration archive. "
                    + "Requires fileName (from list). "
                    + "Mode: 'append' (merge with existing, default) or 'override' (replace). "
                    + "Optionally provide a list of components to include.")
    public Map<String, Object> importArchive(ImportInput input) {
        try {
            String fileName = input.fileName();
            if (fileName == null || fileName.isEmpty()) {
                return Map.of("status", "error", "error",
                        "fileName is required. Use config_archive_list to see available archives.");
            }

            if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
                return Map.of("status", "error", "error", "Invalid file name");
            }

            Path archivePath = ai.kompile.cli.common.KompileHome.homeDirectory()
                    .toPath().resolve("archives").resolve(fileName);
            if (!Files.isRegularFile(archivePath)) {
                return Map.of("status", "error", "error",
                        "Archive not found: " + fileName);
            }

            String modeStr = input.mode() != null ? input.mode() : "append";
            ImportMode mode;
            try {
                mode = ImportMode.valueOf(modeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                return Map.of("status", "error", "error",
                        "Invalid mode: '" + modeStr + "'. Use 'append' or 'override'.");
            }

            ComponentFilter filter = buildFilter(input.components());
            ImportResult result = ConfigArchiveService.importArchive(archivePath, mode, filter);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("mode", modeStr.toLowerCase());
            response.put("fileName", fileName);
            response.put("created", result.getCreated());
            response.put("overwritten", result.getOverwritten());
            response.put("merged", result.getMerged());
            response.put("skipped", result.getSkipped());
            response.put("totalProcessed", result.totalProcessed());
            return response;
        } catch (Exception e) {
            logger.error("Failed to import config archive", e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "config_archive_list",
            description = "List all saved configuration archives in ~/.kompile/archives/. "
                    + "Shows file name, size, date, config count, and provider names for each archive.")
    public Map<String, Object> listArchives(ListInput input) {
        try {
            List<ArchiveInfo> archives = ConfigArchiveService.listArchives();

            List<Map<String, Object>> archiveList = new ArrayList<>();
            for (ArchiveInfo info : archives) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("fileName", info.getFileName());
                entry.put("sizeBytes", info.getSizeBytes());
                entry.put("lastModified", info.getLastModified());
                ConfigArchiveManifest m = info.getManifest();
                if (m != null) {
                    entry.put("configCount", m.getKompileConfigs() != null
                            ? m.getKompileConfigs().size() : 0);
                    entry.put("chatProviders", m.getChatProviderConfigs() != null
                            ? m.getChatProviderConfigs().keySet() : Set.of());
                    entry.put("promptCount", m.getSystemPrompts() != null
                            ? m.getSystemPrompts().size() : 0);
                    if (m.getDescription() != null) {
                        entry.put("description", m.getDescription());
                    }
                }
                archiveList.add(entry);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("archives", archiveList);
            response.put("total", archives.size());
            return response;
        } catch (Exception e) {
            logger.error("Failed to list config archives", e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "config_archive_preview",
            description = "Preview the contents of a saved configuration archive without importing it. "
                    + "Shows manifest metadata: creation date, hostname, included configs, "
                    + "chat providers, and system prompts.")
    public Map<String, Object> previewArchive(PreviewInput input) {
        try {
            String fileName = input.fileName();
            if (fileName == null || fileName.isEmpty()) {
                return Map.of("status", "error", "error",
                        "fileName is required. Use config_archive_list to see available archives.");
            }

            if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
                return Map.of("status", "error", "error", "Invalid file name");
            }

            Path archivePath = ai.kompile.cli.common.KompileHome.homeDirectory()
                    .toPath().resolve("archives").resolve(fileName);
            if (!Files.isRegularFile(archivePath)) {
                return Map.of("status", "error", "error",
                        "Archive not found: " + fileName);
            }

            ConfigArchiveManifest manifest = ConfigArchiveService.readManifest(archivePath);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("fileName", fileName);
            response.put("createdAt", manifest.getCreatedAt());
            response.put("hostname", manifest.getHostname());
            if (manifest.getDescription() != null) {
                response.put("description", manifest.getDescription());
            }
            response.put("kompileConfigs", manifest.getKompileConfigs());
            response.put("chatProviderConfigs", manifest.getChatProviderConfigs());
            response.put("systemPrompts", manifest.getSystemPrompts());
            return response;
        } catch (Exception e) {
            logger.error("Failed to preview config archive", e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "config_archive_delete",
            description = "Delete a saved configuration archive from ~/.kompile/archives/.")
    public Map<String, Object> deleteArchive(DeleteInput input) {
        try {
            String fileName = input.fileName();
            if (fileName == null || fileName.isEmpty()) {
                return Map.of("status", "error", "error",
                        "fileName is required. Use config_archive_list to see available archives.");
            }

            if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
                return Map.of("status", "error", "error", "Invalid file name");
            }

            boolean deleted = ConfigArchiveService.deleteArchive(fileName);
            if (deleted) {
                return Map.of("status", "success",
                        "message", "Archive deleted: " + fileName,
                        "fileName", fileName);
            } else {
                return Map.of("status", "error", "error",
                        "Archive not found: " + fileName);
            }
        } catch (Exception e) {
            logger.error("Failed to delete config archive", e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private ComponentFilter buildFilter(List<String> components) {
        if (components == null || components.isEmpty()) {
            return ComponentFilter.all();
        }
        ComponentFilter filter = ComponentFilter.none();
        for (String c : components) {
            if (c != null && !c.isEmpty()) {
                filter.enable(c);
            }
        }
        return filter.hasAnyEnabled() ? filter : ComponentFilter.all();
    }
}
