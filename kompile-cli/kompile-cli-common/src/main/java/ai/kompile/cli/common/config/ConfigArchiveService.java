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

package ai.kompile.cli.common.config;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Handles export and import of Kompile configuration archives.
 *
 * <p>An archive is a zip file containing:
 * <ul>
 *   <li>{@code manifest.json} — metadata about what's inside</li>
 *   <li>{@code kompile/config/*.json} — files from ~/.kompile/config/</li>
 *   <li>{@code kompile/*.json} — top-level kompile configs (chat-config, harness-config, etc.)</li>
 *   <li>{@code kompile/system-prompt.md} — global system prompt</li>
 *   <li>{@code kompile/system-prompts/*.md} — per-agent system prompts</li>
 *   <li>{@code chat-providers/claude/*} — Claude Code config files</li>
 *   <li>{@code chat-providers/codex/*} — OpenAI Codex config files</li>
 *   <li>{@code chat-providers/qwen/*} — Qwen Code config files</li>
 *   <li>{@code chat-providers/opencode/*} — OpenCode config files</li>
 *   <li>{@code chat-providers/gemini/*} — Gemini CLI config files</li>
 *   <li>{@code chat-providers/pi/*} — Pi coding agent config files</li>
 * </ul>
 */
public class ConfigArchiveService {

    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final String MANIFEST_ENTRY = "manifest.json";
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    // Top-level kompile config files (stored at ~/.kompile/)
    private static final String[] TOP_LEVEL_CONFIGS = {
            "chat-config.json",
            "harness-config.json",
            "staging-settings.json",
            "perf-data.json"
    };

    /**
     * Export all configurations to a zip archive.
     *
     * @param outputPath where to write the archive (null = auto-generate in ~/.kompile/archives/)
     * @param description optional description embedded in the manifest
     * @return the path to the created archive
     */
    public static Path exportArchive(Path outputPath, String description) throws IOException {
        return exportArchive(outputPath, description, ComponentFilter.all());
    }

    /**
     * Export selected configurations to a zip archive.
     *
     * @param outputPath where to write the archive (null = auto-generate in ~/.kompile/archives/)
     * @param description optional description embedded in the manifest
     * @param filter controls which components to include
     * @return the path to the created archive
     */
    public static Path exportArchive(Path outputPath, String description, ComponentFilter filter)
            throws IOException {
        if (outputPath == null) {
            Path archivesDir = KompileHome.homeDirectory().toPath().resolve("archives");
            Files.createDirectories(archivesDir);
            String timestamp = TIMESTAMP_FMT.format(Instant.now());
            outputPath = archivesDir.resolve("kompile-config-" + timestamp + ".zip");
        }

        Files.createDirectories(outputPath.getParent());
        ConfigArchiveManifest manifest = new ConfigArchiveManifest();
        manifest.setDescription(description);

        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(outputPath)))) {

            if (filter.includeKompileAppConfigs()) collectKompileConfigDir(zos, manifest);
            collectTopLevelConfigs(zos, manifest, filter);
            if (filter.includeSystemPrompts()) collectSystemPrompts(zos, manifest);
            collectChatProviderConfigs(zos, manifest, filter);

            // Write manifest as the last entry — serialize to bytes first
            // to avoid ObjectMapper.writeValue() closing the ZipOutputStream
            byte[] manifestBytes = MAPPER.writeValueAsBytes(manifest);
            zos.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
            zos.write(manifestBytes);
            zos.closeEntry();
        }

        return outputPath;
    }

    /**
     * Export all configurations to a byte array (for REST API responses).
     */
    public static byte[] exportArchiveToBytes(String description) throws IOException {
        return exportArchiveToBytes(description, ComponentFilter.all());
    }

    /**
     * Export selected configurations to a byte array (for REST API responses).
     */
    public static byte[] exportArchiveToBytes(String description, ComponentFilter filter)
            throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ConfigArchiveManifest manifest = new ConfigArchiveManifest();
        manifest.setDescription(description);

        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            if (filter.includeKompileAppConfigs()) collectKompileConfigDir(zos, manifest);
            collectTopLevelConfigs(zos, manifest, filter);
            if (filter.includeSystemPrompts()) collectSystemPrompts(zos, manifest);
            collectChatProviderConfigs(zos, manifest, filter);

            byte[] manifestBytes = MAPPER.writeValueAsBytes(manifest);
            zos.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
            zos.write(manifestBytes);
            zos.closeEntry();
        }

        return baos.toByteArray();
    }

    /**
     * Read the manifest from an archive without extracting.
     */
    public static ConfigArchiveManifest readManifest(Path archivePath) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(archivePath)))) {
            return readManifestFromStream(zis);
        }
    }

    /**
     * Read the manifest from a byte array archive.
     */
    public static ConfigArchiveManifest readManifest(byte[] archiveBytes) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(archiveBytes))) {
            return readManifestFromStream(zis);
        }
    }

    private static ConfigArchiveManifest readManifestFromStream(ZipInputStream zis) throws IOException {
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            if (MANIFEST_ENTRY.equals(entry.getName())) {
                return MAPPER.readValue(zis, ConfigArchiveManifest.class);
            }
        }
        throw new IOException("Archive does not contain a manifest.json");
    }

    /**
     * Import an archive from a file path.
     *
     * @param archivePath path to the zip archive
     * @param mode APPEND (merge) or OVERRIDE (replace)
     * @return summary of what was imported
     */
    public static ImportResult importArchive(Path archivePath, ImportMode mode) throws IOException {
        return importArchive(archivePath, mode, ComponentFilter.all());
    }

    /**
     * Import selected components from an archive file.
     */
    public static ImportResult importArchive(Path archivePath, ImportMode mode, ComponentFilter filter)
            throws IOException {
        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(archivePath)))) {
            return importFromStream(zis, mode, filter);
        }
    }

    /**
     * Import an archive from a byte array (for REST API uploads).
     */
    public static ImportResult importArchive(byte[] archiveBytes, ImportMode mode) throws IOException {
        return importArchive(archiveBytes, mode, ComponentFilter.all());
    }

    /**
     * Import selected components from a byte array archive.
     */
    public static ImportResult importArchive(byte[] archiveBytes, ImportMode mode, ComponentFilter filter)
            throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(archiveBytes))) {
            return importFromStream(zis, mode, filter);
        }
    }

    /**
     * List saved archives in ~/.kompile/archives/.
     */
    public static List<ArchiveInfo> listArchives() throws IOException {
        Path archivesDir = KompileHome.homeDirectory().toPath().resolve("archives");
        if (!Files.isDirectory(archivesDir)) {
            return Collections.emptyList();
        }

        List<ArchiveInfo> archives = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(archivesDir, "*.zip")) {
            for (Path p : stream) {
                ArchiveInfo.ArchiveInfoBuilder builder = ArchiveInfo.builder()
                        .fileName(p.getFileName().toString())
                        .filePath(p.toString())
                        .sizeBytes(Files.size(p))
                        .lastModified(Files.getLastModifiedTime(p).toInstant().toString());
                try {
                    builder.manifest(readManifest(p));
                } catch (IOException ignored) {
                    // corrupt archive — still list it
                }
                archives.add(builder.build());
            }
        }

        archives.sort(Comparator.comparing(ArchiveInfo::getLastModified).reversed());
        return archives;
    }

    /**
     * Delete a saved archive.
     */
    public static boolean deleteArchive(String fileName) throws IOException {
        Path archivesDir = KompileHome.homeDirectory().toPath().resolve("archives");
        Path target = archivesDir.resolve(fileName);
        // Prevent path traversal
        if (!target.normalize().startsWith(archivesDir.normalize())) {
            throw new IOException("Invalid archive file name");
        }
        return Files.deleteIfExists(target);
    }

    // ─── Collection helpers ─────────────────────────────────────────

    private static void collectKompileConfigDir(ZipOutputStream zos, ConfigArchiveManifest manifest)
            throws IOException {
        Path configDir = KompileHome.configDirectory().toPath();
        if (!Files.isDirectory(configDir)) return;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(configDir, "*.json")) {
            for (Path file : stream) {
                String entryName = "kompile/config/" + file.getFileName();
                addFileToZip(zos, file, entryName);
                manifest.getKompileConfigs().add(file.getFileName().toString());
            }
        }
    }

    private static void collectTopLevelConfigs(ZipOutputStream zos, ConfigArchiveManifest manifest,
                                               ComponentFilter filter) throws IOException {
        Path homeDir = KompileHome.homeDirectory().toPath();
        for (String name : TOP_LEVEL_CONFIGS) {
            // Apply per-file component filtering
            if ("chat-config.json".equals(name) && !filter.includeKompileChatConfig()) continue;
            if ("harness-config.json".equals(name) && !filter.includeKompileHarnessConfig()) continue;
            if (("staging-settings.json".equals(name) || "perf-data.json".equals(name))
                    && !filter.includeKompileOtherConfigs()) continue;

            Path file = homeDir.resolve(name);
            if (Files.isRegularFile(file)) {
                String entryName = "kompile/" + name;
                addFileToZip(zos, file, entryName);
                manifest.getKompileConfigs().add(name);
            }
        }
    }

    private static void collectSystemPrompts(ZipOutputStream zos, ConfigArchiveManifest manifest)
            throws IOException {
        Path homeDir = KompileHome.homeDirectory().toPath();

        // Global system prompt
        Path globalPrompt = homeDir.resolve("system-prompt.md");
        if (Files.isRegularFile(globalPrompt)) {
            addFileToZip(zos, globalPrompt, "kompile/system-prompt.md");
            manifest.getSystemPrompts().add("system-prompt.md");
        }

        // Per-agent system prompts
        Path promptsDir = homeDir.resolve("system-prompts");
        if (Files.isDirectory(promptsDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(promptsDir, "*.md")) {
                for (Path file : stream) {
                    String entryName = "kompile/system-prompts/" + file.getFileName();
                    addFileToZip(zos, file, entryName);
                    manifest.getSystemPrompts().add("system-prompts/" + file.getFileName());
                }
            }
        }
    }

    private static void collectChatProviderConfigs(ZipOutputStream zos, ConfigArchiveManifest manifest,
                                                    ComponentFilter filter) throws IOException {
        Path home = Path.of(System.getProperty("user.home"));

        if (filter.includeProvider("claude"))
            collectProviderDir(zos, manifest, "claude",
                    home.resolve(".claude"), List.of("settings.json", "settings.local.json"));

        if (filter.includeProvider("codex"))
            collectProviderDir(zos, manifest, "codex",
                    home.resolve(".codex"), List.of("config.toml", "instructions.md"));

        if (filter.includeProvider("qwen"))
            collectProviderDir(zos, manifest, "qwen",
                    home.resolve(".qwen"), List.of("settings.json"));

        if (filter.includeProvider("opencode")) {
            Path opencodeXdg = home.resolve(".config").resolve("opencode");
            Path opencodeLegacy = home.resolve(".opencode");
            Path opencodeDir = Files.isDirectory(opencodeXdg) ? opencodeXdg : opencodeLegacy;
            collectProviderDir(zos, manifest, "opencode",
                    opencodeDir, List.of("config.json", "settings.json"));
        }

        if (filter.includeProvider("gemini"))
            collectProviderDir(zos, manifest, "gemini",
                    home.resolve(".gemini"), List.of("settings.json"));

        if (filter.includeProvider("pi"))
            collectProviderDir(zos, manifest, "pi",
                    home.resolve(".pi"), List.of("config.json", "settings.json"));
    }

    private static void collectProviderDir(ZipOutputStream zos, ConfigArchiveManifest manifest,
                                           String providerName, Path dir, List<String> fileNames)
            throws IOException {
        if (!Files.isDirectory(dir)) return;

        List<String> collected = new ArrayList<>();
        for (String name : fileNames) {
            Path file = dir.resolve(name);
            if (Files.isRegularFile(file)) {
                String entryName = "chat-providers/" + providerName + "/" + name;
                addFileToZip(zos, file, entryName);
                collected.add(name);
            }
        }
        if (!collected.isEmpty()) {
            manifest.getChatProviderConfigs().put(providerName, collected);
        }
    }

    // ─── Import logic ───────────────────────────────────────────────

    private static ImportResult importFromStream(ZipInputStream zis, ImportMode mode,
                                                   ComponentFilter filter) throws IOException {
        ImportResult result = new ImportResult();
        Path kompileHome = KompileHome.homeDirectory().toPath();
        Path userHome = Path.of(System.getProperty("user.home"));

        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            String name = entry.getName();
            if (entry.isDirectory() || MANIFEST_ENTRY.equals(name)) {
                continue;
            }

            // Apply component filter
            if (!filter.acceptsEntry(name)) {
                // Still need to consume the bytes even though we skip
                readAllBytes(zis);
                result.getSkipped().add(name + " (component excluded)");
                continue;
            }

            byte[] data = readAllBytes(zis);
            Path target = resolveImportTarget(name, kompileHome, userHome);
            if (target == null) {
                result.getSkipped().add(name);
                continue;
            }

            // Prevent path traversal
            Path expectedParent = name.startsWith("chat-providers/") ? userHome : kompileHome;
            if (!target.normalize().startsWith(expectedParent.normalize())) {
                result.getSkipped().add(name + " (path traversal blocked)");
                continue;
            }

            boolean existed = Files.isRegularFile(target);
            if (mode == ImportMode.APPEND && existed && name.endsWith(".json")) {
                // Merge JSON objects
                try {
                    mergeJsonFile(target, data);
                    result.getMerged().add(name);
                } catch (Exception e) {
                    // Not valid JSON for merging — skip in append mode
                    result.getSkipped().add(name + " (merge failed: " + e.getMessage() + ")");
                }
            } else if (mode == ImportMode.APPEND && existed) {
                // Non-JSON file exists — don't overwrite in append mode
                result.getSkipped().add(name + " (exists, append mode)");
            } else {
                // Override mode, or file doesn't exist yet
                Files.createDirectories(target.getParent());
                Files.write(target, data);
                if (existed) {
                    result.getOverwritten().add(name);
                } else {
                    result.getCreated().add(name);
                }
            }
        }

        return result;
    }

    private static Path resolveImportTarget(String entryName, Path kompileHome, Path userHome) {
        if (entryName.startsWith("kompile/config/")) {
            String fileName = entryName.substring("kompile/config/".length());
            return kompileHome.resolve("config").resolve(fileName);
        } else if (entryName.startsWith("kompile/system-prompts/")) {
            String fileName = entryName.substring("kompile/system-prompts/".length());
            return kompileHome.resolve("system-prompts").resolve(fileName);
        } else if (entryName.startsWith("kompile/")) {
            String fileName = entryName.substring("kompile/".length());
            return kompileHome.resolve(fileName);
        } else if (entryName.startsWith("chat-providers/claude/")) {
            String fileName = entryName.substring("chat-providers/claude/".length());
            return userHome.resolve(".claude").resolve(fileName);
        } else if (entryName.startsWith("chat-providers/codex/")) {
            String fileName = entryName.substring("chat-providers/codex/".length());
            return userHome.resolve(".codex").resolve(fileName);
        } else if (entryName.startsWith("chat-providers/qwen/")) {
            String fileName = entryName.substring("chat-providers/qwen/".length());
            return userHome.resolve(".qwen").resolve(fileName);
        } else if (entryName.startsWith("chat-providers/opencode/")) {
            String fileName = entryName.substring("chat-providers/opencode/".length());
            return userHome.resolve(".config").resolve("opencode").resolve(fileName);
        } else if (entryName.startsWith("chat-providers/gemini/")) {
            String fileName = entryName.substring("chat-providers/gemini/".length());
            return userHome.resolve(".gemini").resolve(fileName);
        } else if (entryName.startsWith("chat-providers/pi/")) {
            String fileName = entryName.substring("chat-providers/pi/".length());
            return userHome.resolve(".pi").resolve(fileName);
        }
        return null;
    }

    private static void mergeJsonFile(Path existing, byte[] incoming) throws IOException {
        JsonNode existingNode = MAPPER.readTree(existing.toFile());
        JsonNode incomingNode = MAPPER.readTree(incoming);

        if (existingNode.isObject() && incomingNode.isObject()) {
            ObjectNode merged = (ObjectNode) existingNode;
            Iterator<Map.Entry<String, JsonNode>> fields = incomingNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                merged.set(field.getKey(), field.getValue());
            }
            MAPPER.writeValue(existing.toFile(), merged);
        } else {
            // Not both objects — just overwrite
            Files.write(existing, incoming);
        }
    }

    private static void addFileToZip(ZipOutputStream zos, Path file, String entryName) throws IOException {
        zos.putNextEntry(new ZipEntry(entryName));
        Files.copy(file, zos);
        zos.closeEntry();
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    // ─── Result types ───────────────────────────────────────────────

    /**
     * Summary of an import operation.
     */
    public static class ImportResult {
        private final List<String> created = new ArrayList<>();
        private final List<String> overwritten = new ArrayList<>();
        private final List<String> merged = new ArrayList<>();
        private final List<String> skipped = new ArrayList<>();

        public List<String> getCreated() { return created; }
        public List<String> getOverwritten() { return overwritten; }
        public List<String> getMerged() { return merged; }
        public List<String> getSkipped() { return skipped; }

        public int totalProcessed() {
            return created.size() + overwritten.size() + merged.size();
        }
    }

}
