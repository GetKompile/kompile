/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.agent.spin;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Materializes one installed spin into a persistent, project-scoped Kompile workspace. */
public final class SpinWorkspace {
    private static final ObjectMapper JSON = JsonUtils.standardMapper();
    private static final long MAX_CONTEXT_RESOURCE_BYTES = 1024L * 1024L;

    private SpinWorkspace() { }

    public static Prepared prepare(SpinDefinition definition, Path spinHome) throws IOException {
        return prepare(definition, spinHome, false);
    }

    static Prepared prepareForInstall(SpinDefinition definition, Path spinHome)
            throws IOException {
        return prepare(definition, spinHome, true);
    }

    private static Prepared prepare(
            SpinDefinition definition, Path spinHome, boolean pruneStaleManagedFiles)
            throws IOException {
        Path home = spinHome.toAbsolutePath().normalize();
        rejectSymlinkComponents(home, "spin home");
        Files.createDirectories(home);
        Path workspace = home.resolve("workspace").normalize();
        if (!workspace.startsWith(home)) throw new IOException("Spin workspace escapes its home");
        rejectSymlinkComponents(workspace, "spin workspace");
        Files.createDirectories(workspace);

        copyWorkspaceTemplate(definition.root().resolve("workspace"), workspace);
        if (pruneStaleManagedFiles) {
            resetManagedDirectory(workspace, workspace.resolve(".kompile/roles"));
            resetManagedDirectory(workspace, workspace.resolve(".kompile/skills"));
        } else {
            createManagedDirectory(workspace, workspace.resolve(".kompile/roles"));
            createManagedDirectory(workspace, workspace.resolve(".kompile/skills"));
        }
        createManagedDirectory(workspace, workspace.resolve("data/input_documents"));
        createManagedDirectory(workspace, workspace.resolve("data/crawls"));

        if (definition.projectBacked()) {
            materializeProject(definition, workspace, pruneStaleManagedFiles);
        }
        writeRole(definition, workspace);
        writeInstructions(definition, workspace);
        materializeSkills(definition, workspace);
        writeMcpConfiguration(definition, home, workspace);
        writeModelConfiguration(definition, workspace);
        writeSpinMetadata(definition, home, workspace);
        markBundledExecutables(definition);

        SpinDefinition.ModelAsset model = definition.defaultModel();
        if (model != null && definition.projectBacked()) {
            Path payload = projectPayload(definition, workspace);
            Path path = payload.resolve(definition.project().template().relativize(model.path()));
            Path tokenizer = model.tokenizer() == null ? null
                    : payload.resolve(definition.project().template().relativize(model.tokenizer()));
            model = new SpinDefinition.ModelAsset(model.id(), workspace.relativize(path).toString(), path,
                    tokenizer == null ? null : workspace.relativize(tokenizer).toString(), tokenizer,
                    model.isDefault(), model.provider());
        }
        return new Prepared(home, workspace, definition.root(), definition.roleName(), model);
    }

    private static Path projectPayload(SpinDefinition definition, Path workspace) {
        return workspace.resolve("data/models/spin-assets").resolve(definition.project().contentId());
    }

    /** Publish verified project assets first, and its canonical inventory last. */
    private static void materializeProject(SpinDefinition definition, Path workspace, boolean installing)
            throws IOException {
        Path payload = projectPayload(definition, workspace);
        rejectSymlinkComponents(payload, "spin project assets");
        Path manifestPath = workspace.resolve("kompile.project.json");
        rejectSymlinkComponents(manifestPath, "spin project manifest");
        ObjectNode existing = null;
        if (Files.exists(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            JsonNode read = JSON.readTree(manifestPath.toFile());
            if (read == null || !read.isObject()) throw new IOException("Invalid workspace project manifest");
            existing = (ObjectNode) read;
            if (!definition.id().equals(existing.path("metadata").path("spin.owner").asText())) {
                throw new IOException("Refusing to replace a project not owned by this spin");
            }
            if (!installing && definition.project().contentId().equals(
                    existing.path("metadata").path("spin.contentId").asText())) return;
        }
        if (!Files.exists(payload, LinkOption.NOFOLLOW_LINKS)) {
            createManagedDirectory(workspace, payload.getParent());
            Path staging = payload.resolveSibling(".stage-" + UUID.randomUUID());
            try {
                copyWorkspaceTemplate(definition.project().template(), staging);
                verifyProjectCopy(definition.project().template(), staging);
                try { Files.move(staging, payload, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException failure) { Files.move(staging, payload); }
            } finally {
                if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                    resetManagedDirectory(workspace, staging);
                    Files.delete(staging);
                }
            }
        } else {
            verifyProjectCopy(definition.project().template(), payload);
        }
        String prefix = workspace.relativize(payload).toString().replace('\\', '/') + "/";
        ObjectNode curated = definition.project().manifest();
        ObjectNode result = existing == null ? curated.deepCopy() : existing.deepCopy();
        for (String field : List.of("models", "pipelines", "scripts", "crawlProfiles", "workflows", "components", "modules")) {
            ArrayNode merged = JSON.createArrayNode();
            java.util.Set<String> ids = new java.util.HashSet<>();
            if (existing != null) for (JsonNode item : SpinProjectComposition.array(existing, field)) {
                if (definition.id().equals(item.path("metadata").path("spin.owner").asText())) continue;
                merged.add(item.deepCopy());
                ids.add(item.path("id").asText());
            }
            for (JsonNode item : SpinProjectComposition.array(curated, field)) {
                if (!item.isObject()) throw new IOException("Project " + field + " entry must be an object");
                String id = item.path("id").asText("");
                if (id.isBlank() || !ids.add(id)) throw new IOException("Project " + field + " id collision: " + id);
                ObjectNode managed = (ObjectNode) item.deepCopy();
                if (!managed.path("metadata").isObject()) managed.putObject("metadata");
                ((ObjectNode) managed.path("metadata")).put("spin.owner", definition.id());
                if ("models".equals(field)) {
                    relocate(managed, "path", prefix);
                    relocate(managed, "stagingRegistryPath", prefix);
                    relocateMetadata(managed, prefix);
                } else if ("pipelines".equals(field)) {
                    relocate(managed, "definitionPath", prefix);
                    relocate(managed, "registryPath", prefix);
                } else if ("scripts".equals(field)) {
                    relocate(managed, "path", prefix);
                    relocate(managed, "workingDirectory", prefix);
                } else if ("crawlProfiles".equals(field)) {
                    JsonNode sources = managed.path("sources");
                    if (sources.isArray()) {
                        ArrayNode remapped = JSON.createArrayNode();
                        for (JsonNode source : sources) {
                            String value = source.asText();
                            remapped.add(value.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*") ? value : prefix + value);
                        }
                        managed.set("sources", remapped);
                    }
                }
                merged.add(managed);
            }
            result.set(field, merged);
        }
        result.set("distribution", curated.path("distribution").deepCopy());
        if (!result.path("metadata").isObject()) result.putObject("metadata");
        ObjectNode metadata = (ObjectNode) result.path("metadata");
        metadata.put("spin.owner", definition.id());
        metadata.put("spin.contentId", definition.project().contentId());
        metadata.put("spin.defaultModel", curated.path("metadata").path("spin.defaultModel").asText(""));
        // Seed configuration only once; never export or overwrite the user's subsequent secrets.
        copyWorkspaceTemplate(definition.project().template().resolve("config"), workspace.resolve("config"));
        writeJsonAtomic(workspace, manifestPath, result);
    }

    private static void verifyProjectCopy(Path source, Path target) throws IOException {
        rejectSymlinkComponents(target, "spin project payload");
        try (var walk = Files.walk(source)) {
            for (Path file : walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                Path copied = target.resolve(source.relativize(file));
                rejectSymlinkComponents(copied, "spin project payload");
                if (!Files.isRegularFile(copied, LinkOption.NOFOLLOW_LINKS)
                        || !ai.kompile.utils.HashUtils.sha256Hex(file).equals(ai.kompile.utils.HashUtils.sha256Hex(copied))) {
                    throw new IOException("Spin project asset verification failed: " + source.relativize(file));
                }
            }
        }
    }

    private static void relocate(ObjectNode node, String field, String prefix) throws IOException {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) return;
        String path = value.asText();
        if (!value.isTextual() || path.contains("\\") || Path.of(path).isAbsolute()
                || java.util.Arrays.asList(path.split("/")).contains("..") || path.contains(":")) {
            throw new IOException("Project " + field + " must be a portable relative path");
        }
        node.put(field, prefix + path);
    }

    private static void relocateMetadata(ObjectNode node, String prefix) throws IOException {
        ObjectNode metadata = (ObjectNode) node.path("metadata");
        List<String> paths = new ArrayList<>();
        metadata.fieldNames().forEachRemaining(key -> { if (key.endsWith(".path")) paths.add(key); });
        for (String key : paths) relocate(metadata, key, prefix);
    }

    private static void writeRole(SpinDefinition definition, Path workspace) throws IOException {
        JsonNode role = definition.role();
        StringBuilder markdown = new StringBuilder("---\n");
        markdown.append("name: ").append(definition.roleName()).append('\n');
        markdown.append("display_name: ").append(safeLine(firstNonBlank(
                role.path("displayName").asText(null), definition.displayName()))).append('\n');
        markdown.append("description: ").append(safeLine(firstNonBlank(
                role.path("description").asText(null),
                "Packaged Kompile spin " + definition.displayName()))).append('\n');
        markdown.append("category: spin\n");
        markdown.append("model: ").append(safeLine(
                role.path("model").asText("default"))).append('\n');
        markdown.append("can_spawn: ").append(role.path("canSpawn").asBoolean(false)).append('\n');
        String tools = csv(role.path("tools"));
        if (!tools.isBlank()) markdown.append("tools: ").append(tools).append('\n');
        String denied = csv(role.path("denyTools"));
        if (!denied.isBlank()) markdown.append("deny_tools: ").append(denied).append('\n');
        markdown.append("---\n");
        String prompt = definition.systemPrompt().strip();
        if (prompt.isBlank()) {
            prompt = "You are the " + definition.displayName() + " assistant.";
        }
        markdown.append(prompt).append('\n');
        writeAtomic(workspace, workspace.resolve(".kompile/roles/"
                + definition.roleName() + ".md"), markdown.toString());
    }

    private static void writeInstructions(SpinDefinition definition, Path workspace)
            throws IOException {
        StringBuilder content = new StringBuilder();
        Path template = definition.root().resolve("workspace/AGENTS.md");
        if (Files.isRegularFile(template, LinkOption.NOFOLLOW_LINKS)) {
            content.append(readText(template, "workspace AGENTS.md").strip());
        }
        JsonNode instructions = definition.instructions();
        if (instructions.isArray()) {
            for (JsonNode item : instructions) {
                String path = resourcePath(item);
                if (path.isBlank()) continue;
                Path source = resolveResource(definition.root(), path, "instruction");
                if (content.length() > 0) content.append("\n\n");
                content.append("# Spin instruction: ").append(path).append("\n\n")
                        .append(readText(source, "instruction " + path).strip());
                if (content.length() > MAX_CONTEXT_RESOURCE_BYTES) {
                    throw new IOException("Packaged spin instructions exceed "
                            + MAX_CONTEXT_RESOURCE_BYTES + " characters");
                }
            }
        }
        if (content.isEmpty()) {
            content.append("# ").append(definition.displayName()).append(" Workspace\n\n")
                    .append("This workspace is managed by the installed Kompile spin.\n");
        }
        content.append('\n');
        writeAtomic(workspace, workspace.resolve("AGENTS.md"), content.toString());
    }

    private static void materializeSkills(SpinDefinition definition, Path workspace)
            throws IOException {
        JsonNode skills = definition.skills();
        if (!skills.isArray()) return;
        for (JsonNode item : skills) {
            String path = resourcePath(item);
            if (path.isBlank()) continue;
            Path source = resolveResource(definition.root(), path, "skill");
            String configuredName = item.isObject() ? item.path("name").asText("").trim() : "";
            Path target;
            if (!configuredName.isBlank()) {
                if (!configuredName.matches("[A-Za-z][A-Za-z0-9_-]{0,127}")) {
                    throw new IOException("Invalid packaged skill name: " + configuredName);
                }
                target = workspace.resolve(".kompile/skills").resolve(configuredName)
                        .resolve("SKILL.md");
            } else if ("SKILL.md".equals(source.getFileName().toString())
                    && source.getParent() != null && source.getParent().getFileName() != null) {
                target = workspace.resolve(".kompile/skills")
                        .resolve(source.getParent().getFileName().toString()).resolve("SKILL.md");
            } else {
                target = workspace.resolve(".kompile/skills").resolve(source.getFileName());
            }
            writeAtomic(workspace, target, readText(source, "skill " + path));
        }
    }

    private static void writeMcpConfiguration(SpinDefinition definition, Path home,
                                              Path workspace) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode servers = root.putObject("mcpServers");
        Map<String, String> substitutions = Map.of(
                "${SPIN_ROOT}", definition.root().toString(),
                "${SPIN_HOME}", home.toString(),
                "${SPIN_WORKSPACE}", workspace.toString());

        for (SpinDefinition.McpServerAsset server : definition.mcpServers()) {
            ObjectNode config = (ObjectNode) server.config().deepCopy();
            config.remove(List.of("id", "bundled", "executable"));
            if (server.bundled()) {
                if (isWindowsScript(server.commandPath())) {
                    ArrayNode arguments = JSON.createArrayNode().add("/d").add("/c")
                            .add(server.commandPath().toString());
                    JsonNode configuredArguments = config.path("args");
                    if (configuredArguments.isArray()) configuredArguments.forEach(arguments::add);
                    config.put("command", "cmd.exe");
                    config.set("args", arguments);
                } else {
                    config.put("command", server.commandPath().toString());
                }
            }
            JsonNode cwd = config.get("cwd");
            if (cwd != null && cwd.isTextual() && !cwd.asText().isBlank()) {
                String expanded = substitute(cwd.asText(), substitutions);
                Path configured = Path.of(expanded);
                if (!configured.isAbsolute()) configured = definition.root().resolve(configured);
                Path normalized = configured.toAbsolutePath().normalize();
                if (!normalized.startsWith(definition.root())) {
                    throw new IOException("MCP cwd escapes the installed spin: " + cwd.asText());
                }
                config.put("cwd", normalized.toString());
            }
            substituteText(config, substitutions);
            JsonNode configuredEnv = config.get("env");
            ObjectNode env = configuredEnv == null
                    ? config.putObject("env") : (ObjectNode) configuredEnv;
            if (!env.has("KOMPILE_SPIN_ROOT")) env.put("KOMPILE_SPIN_ROOT", definition.root().toString());
            if (!env.has("KOMPILE_SPIN_HOME")) env.put("KOMPILE_SPIN_HOME", home.toString());
            if (!env.has("KOMPILE_SPIN_WORKSPACE")) env.put("KOMPILE_SPIN_WORKSPACE", workspace.toString());
            servers.set(server.id(), config);
        }
        writeJsonAtomic(workspace, workspace.resolve(".mcp.json"), root);
    }

    private static void writeModelConfiguration(SpinDefinition definition, Path workspace)
            throws IOException {
        ArrayNode models = JSON.createArrayNode();
        for (SpinDefinition.ModelAsset model : definition.models()) {
            ObjectNode item = models.addObject();
            item.put("id", model.id());
            item.put("path", model.path().toString());
            if (model.tokenizer() != null) item.put("tokenizer", model.tokenizer().toString());
            item.put("provider", model.provider());
            item.put("default", model.isDefault());
        }
        ObjectNode catalog = JSON.createObjectNode();
        catalog.set("models", models);
        if (!definition.projectBacked()) {
            writeJsonAtomic(workspace, workspace.resolve(".kompile/spin-models.json"), catalog);
        }

        SpinDefinition.ModelAsset model = definition.defaultModel();
        if (model == null) {
            removeManagedChatConfiguration(definition, workspace);
            return;
        }
        ObjectNode chat = JSON.createObjectNode();
        chat.put("managedBySpin", definition.id());
        chat.put("provider", firstNonBlank(model.provider(), "kompile-local"));
        chat.put("model", model.id());
        chat.put("defaultAgent", definition.roleName());
        chat.put("chatMode", "standard");
        chat.put("defaultMemory", false);
        writeJsonAtomic(workspace, workspace.resolve(".kompile/chat-config.json"), chat);
    }

    private static void removeManagedChatConfiguration(
            SpinDefinition definition, Path workspace) throws IOException {
        Path config = workspace.resolve(".kompile/chat-config.json");
        if (!Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(config)) return;
        JsonNode existing = JSON.readTree(config.toFile());
        if (definition.id().equals(existing.path("managedBySpin").asText())) {
            Files.delete(config);
        }
    }

    private static void writeSpinMetadata(SpinDefinition definition, Path home, Path workspace)
            throws IOException {
        ObjectNode metadata = JSON.createObjectNode();
        metadata.put("schemaVersion", "1");
        metadata.put("id", definition.id());
        metadata.put("version", definition.version());
        metadata.put("displayName", definition.displayName());
        metadata.put("delivery", definition.delivery());
        metadata.put("releaseRoot", definition.root().toString());
        metadata.put("spinHome", home.toString());
        metadata.put("workspace", workspace.toString());
        metadata.put("role", definition.roleName());
        writeJsonAtomic(workspace, workspace.resolve(".kompile/spin.json"), metadata);
    }

    private static void markBundledExecutables(SpinDefinition definition) throws IOException {
        for (SpinDefinition.McpServerAsset server : definition.mcpServers()) {
            if (!server.bundled() || !server.executable()) continue;
            if (!isWindows()
                    && !server.commandPath().toFile().setExecutable(true, false)
                    && !Files.isExecutable(server.commandPath())) {
                throw new IOException("Could not make bundled MCP command executable: "
                        + server.commandPath());
            }
        }
        Path runtimeBin = definition.root().resolve("runtime/bin");
        if (Files.isDirectory(runtimeBin, LinkOption.NOFOLLOW_LINKS)) {
            try (var files = Files.list(runtimeBin)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    if (!isWindows() && !file.toFile().setExecutable(true, false)
                            && !Files.isExecutable(file)) {
                        throw new IOException("Could not make embedded runtime executable: " + file);
                    }
                }
            }
        }
    }

    private static void copyWorkspaceTemplate(Path source, Path workspace) throws IOException {
        if (!Files.exists(source)) return;
        if (Files.isSymbolicLink(source)
                || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("workspace/ template must be a regular directory");
        }
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                if (Files.isSymbolicLink(dir)) {
                    throw new IOException("Symbolic links are not allowed in workspace templates: " + dir);
                }
                Path target = workspace.resolve(source.relativize(dir).toString()).normalize();
                requireWithin(workspace, target);
                rejectSymlinkComponents(target, "workspace template target");
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (Files.isSymbolicLink(file) || !attrs.isRegularFile()) {
                    throw new IOException("Only regular workspace template files are allowed: " + file);
                }
                Path target = workspace.resolve(source.relativize(file).toString()).normalize();
                requireWithin(workspace, target);
                rejectSymlinkComponents(target, "workspace template target");
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static Path resolveResource(Path root, String relative, String label)
            throws IOException {
        Path value = Path.of(relative);
        if (value.isAbsolute() || value.normalize().startsWith("..")) {
            throw new IOException("Packaged " + label + " path escapes the spin: " + relative);
        }
        Path resolved = root.resolve(value).normalize();
        if (!resolved.startsWith(root)
                || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(resolved)) {
            throw new IOException("Packaged " + label + " does not exist: " + relative);
        }
        return resolved;
    }

    private static String resourcePath(JsonNode item) {
        return item.isTextual() ? item.asText("").trim() : item.path("path").asText("").trim();
    }

    private static String readText(Path source, String label) throws IOException {
        if (Files.size(source) > MAX_CONTEXT_RESOURCE_BYTES) {
            throw new IOException("Packaged " + label + " exceeds "
                    + MAX_CONTEXT_RESOURCE_BYTES + " bytes");
        }
        return Files.readString(source, StandardCharsets.UTF_8);
    }

    private static String csv(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        if (node.isTextual()) return safeLine(node.asText(""));
        if (!node.isArray()) return "";
        List<String> values = new ArrayList<>();
        node.forEach(value -> {
            if (value.isTextual() && !value.asText().isBlank()) {
                values.add(safeLine(value.asText().trim()));
            }
        });
        return String.join(", ", values);
    }

    private static void substituteText(JsonNode node, Map<String, String> values) {
        if (node.isObject()) {
            var fields = node.fields();
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
            fields.forEachRemaining(entries::add);
            for (var entry : entries) {
                JsonNode value = entry.getValue();
                if (value.isTextual()) {
                    ((ObjectNode) node).put(entry.getKey(), substitute(value.asText(), values));
                } else {
                    substituteText(value, values);
                }
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                JsonNode value = array.get(i);
                if (value.isTextual()) array.set(i,
                        JSON.getNodeFactory().textNode(substitute(value.asText(), values)));
                else substituteText(value, values);
            }
        }
    }

    private static String substitute(String value, Map<String, String> values) {
        String result = value;
        for (var entry : values.entrySet()) result = result.replace(entry.getKey(), entry.getValue());
        return result;
    }

    private static void writeJsonAtomic(Path workspace, Path target, JsonNode value)
            throws IOException {
        writeAtomic(workspace, target,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
    }

    private static void writeAtomic(Path workspace, Path target, String content)
            throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        requireWithin(workspace.toAbsolutePath().normalize(), normalized);
        rejectSymlinkComponents(normalized, "managed workspace file");
        Files.createDirectories(normalized.getParent());
        Path temporary = normalized.resolveSibling("." + normalized.getFileName()
                + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, normalized, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, normalized, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void requireWithin(Path root, Path target) throws IOException {
        if (!target.startsWith(root)) throw new IOException("Managed path escapes workspace: " + target);
    }

    private static void createManagedDirectory(Path workspace, Path directory)
            throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        requireWithin(workspace.toAbsolutePath().normalize(), normalized);
        rejectSymlinkComponents(normalized, "managed workspace directory");
        Files.createDirectories(normalized);
    }

    private static void resetManagedDirectory(Path workspace, Path directory)
            throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        requireWithin(workspace.toAbsolutePath().normalize(), normalized);
        rejectSymlinkComponents(normalized, "managed workspace directory");
        if (Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    if (Files.isSymbolicLink(dir)) {
                        throw new IOException("Managed directory contains a symbolic link: " + dir);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    if (Files.isSymbolicLink(file) || !attrs.isRegularFile()) {
                        throw new IOException("Managed directory contains an unsafe file: " + file);
                    }
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException failure)
                        throws IOException {
                    if (failure != null) throw failure;
                    if (!dir.equals(normalized)) Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        Files.createDirectories(normalized);
    }

    private static void rejectSymlinkComponents(Path path, String label) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current == null ? part : current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(current)) {
                throw new IOException(label + " contains a symbolic link: " + current);
            }
        }
    }

    private static String safeLine(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static boolean isWindowsScript(Path path) {
        if (!isWindows()) return false;
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".cmd") || name.endsWith(".bat");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    public record Prepared(Path home, Path workspace, Path releaseRoot,
                           String roleName, SpinDefinition.ModelAsset defaultModel) { }
}
