/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.agent.spin;

import ai.kompile.cli.agent.AgentBundleLoader;
import ai.kompile.utils.HashUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validated view of a spin source or installed release.
 *
 * <p>A spin is deliberately a superset of a portable agent bundle: {@code spin.yaml}
 * adds install/runtime metadata while the existing {@code agent.yaml} remains the
 * authority for the prompt, role, skills, MCP servers, and model assets.</p>
 */
public final class SpinDefinition {
    public static final String MANIFEST = "spin.yaml";
    public static final String CHECKSUM_MANIFEST = "manifest.sha256";

    private static final long MAX_MANIFEST_BYTES = 1024L * 1024L;
    private static final long MAX_SYSTEM_PROMPT_BYTES = 1024L * 1024L;
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}");
    private static final Pattern ROLE = Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,127}");
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,255}");
    private static final Pattern SENSITIVE_ENVIRONMENT_NAME = Pattern.compile(
            "(?i).*(TOKEN|SECRET|PASSWORD|API_KEY|PRIVATE_KEY|ACCESS_KEY|AUTH|CREDENTIAL).*");
    private static final Pattern ENVIRONMENT_REFERENCE = Pattern.compile(
            "(?:\\$\\{?[A-Za-z_][A-Za-z0-9_]*}?|%[A-Za-z_][A-Za-z0-9_]*%)");

    private final Path root;
    private final JsonNode spinManifest;
    private final JsonNode agentManifest;
    private final List<String> entries;
    private final String id;
    private final String version;
    private final String displayName;
    private final String commandName;
    private final String delivery;
    private final String roleName;
    private final List<ModelAsset> models;
    private final List<McpServerAsset> mcpServers;

    private SpinDefinition(Path root,
                           JsonNode spinManifest,
                           JsonNode agentManifest,
                           List<String> entries,
                           String id,
                           String version,
                           String displayName,
                           String commandName,
                           String delivery,
                           String roleName,
                           List<ModelAsset> models,
                           List<McpServerAsset> mcpServers) {
        this.root = root;
        this.spinManifest = spinManifest;
        this.agentManifest = agentManifest;
        this.entries = List.copyOf(entries);
        this.id = id;
        this.version = version;
        this.displayName = displayName;
        this.commandName = commandName;
        this.delivery = delivery;
        this.roleName = roleName;
        this.models = List.copyOf(models);
        this.mcpServers = List.copyOf(mcpServers);
    }

    public static SpinDefinition load(Path source) throws IOException {
        return load(source, false);
    }

    static SpinDefinition load(Path source, boolean embeddedRuntimeProvided) throws IOException {
        if (source == null) throw new IOException("Spin source is required");
        Path root = source.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Spin source must be a regular directory, not a symbolic link: " + root);
        }

        Path spinPath = root.resolve(MANIFEST);
        if (!Files.isRegularFile(spinPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Spin is missing " + MANIFEST + ": " + root);
        }
        if (Files.size(spinPath) > MAX_MANIFEST_BYTES) {
            throw new IOException(MANIFEST + " exceeds " + MAX_MANIFEST_BYTES + " bytes");
        }

        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        JsonNode spin = yaml.readTree(Files.readString(spinPath));
        if (spin == null || !spin.isObject()) {
            throw new IOException(MANIFEST + " must contain an object");
        }
        String schema = spin.path("schemaVersion").asText("1").trim();
        if (!("1".equals(schema) || "v1".equalsIgnoreCase(schema))) {
            throw new IOException("Unsupported spin schema: " + schema);
        }

        Path agentPath = Files.isRegularFile(root.resolve(AgentBundleLoader.MANIFEST),
                LinkOption.NOFOLLOW_LINKS)
                ? root.resolve(AgentBundleLoader.MANIFEST)
                : root.resolve(AgentBundleLoader.JSON_MANIFEST);
        if (Files.isRegularFile(agentPath, LinkOption.NOFOLLOW_LINKS)
                && Files.size(agentPath) > MAX_MANIFEST_BYTES) {
            throw new IOException(agentPath.getFileName() + " exceeds "
                    + MAX_MANIFEST_BYTES + " bytes");
        }

        JsonNode agent;
        List<String> entries;
        AgentBundleLoader loader = new AgentBundleLoader();
        try (AgentBundleLoader.LoadedBundle loaded = loader.load(root)) {
            agent = loaded.manifest().deepCopy();
            entries = List.copyOf(loaded.entries());
        }

        JsonNode metadata = spin.path("metadata");
        String id = firstNonBlank(metadata.path("id").asText(null),
                metadata.path("name").asText(null));
        requireMatch("metadata.id", id, ID);
        String version = metadata.path("version").asText("").trim();
        requireMatch("metadata.version", version, VERSION);
        String displayName = firstNonBlank(metadata.path("displayName").asText(null), id);
        String commandName = firstNonBlank(metadata.path("command").asText(null), id);
        requireMatch("metadata.command", commandName, ID);

        String delivery = spin.path("runtime").path("delivery").asText("thin")
                .trim().toLowerCase(Locale.ROOT);
        if (!Set.of("thin", "embedded").contains(delivery)) {
            throw new IOException("runtime.delivery must be thin or embedded");
        }
        JsonNode role = agent.path("role");
        String roleName = firstNonBlank(role.path("name").asText(null), id);
        requireMatch("agent role name", roleName, ROLE);
        validateToolNames(role.path("tools"), "role.tools");
        validateToolNames(role.path("denyTools"), "role.denyTools");

        List<ModelAsset> models = parseModels(root, agent.path("models"));
        boolean needsLocalModelRuntime = requiresLocalModelRuntime(models);
        if ("embedded".equals(delivery) && !embeddedRuntimeProvided
                && !hasRuntimeDistribution(root.resolve("runtime"), needsLocalModelRuntime)) {
            throw new IOException(needsLocalModelRuntime
                    ? "Embedded spin with a default local model must include Kompile, kompile-agent, and model-serving"
                    : "Embedded spin must include both Kompile and kompile-agent runtime launchers");
        }
        List<McpServerAsset> servers = parseMcpServers(root, agent.path("mcp").path("servers"));

        return new SpinDefinition(root, spin.deepCopy(), agent, entries, id, version,
                displayName, commandName, delivery, roleName, models, servers);
    }

    private static List<ModelAsset> parseModels(Path root, JsonNode node) throws IOException {
        if (node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray()) throw new IOException("agent models must be an array");
        List<ModelAsset> result = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        int defaults = 0;
        for (JsonNode item : node) {
            if (!item.isObject()) throw new IOException("Every model entry must be an object");
            String id = item.path("id").asText("").trim();
            requireMatch("model id", id, MODEL_ID);
            if (!ids.add(id)) throw new IOException("Duplicate model id: " + id);
            String pathValue = item.path("path").asText("").trim();
            Path path = requireBundledPath(root, pathValue, "model " + id, false);
            String tokenizerValue = item.path("tokenizer").asText("").trim();
            Path tokenizer = tokenizerValue.isBlank() ? null
                    : requireBundledPath(root, tokenizerValue, "model tokenizer " + id, true);
            boolean isDefault = item.path("default").asBoolean(false);
            if (isDefault) defaults++;
            String provider = item.path("provider").asText("kompile-local").trim();
            if (isDefault && "kompile-local".equalsIgnoreCase(provider)) {
                validateLocalChatModel(path, id);
                if (tokenizer == null) {
                    Path inferred = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                            ? path.resolve("tokenizer.json") : path.resolveSibling("tokenizer.json");
                    if (!Files.isRegularFile(inferred, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Default local model " + id
                                + " requires a bundled tokenizer.json");
                    }
                    tokenizer = inferred;
                    tokenizerValue = root.relativize(inferred).toString();
                }
            }
            String expectedSha256 = item.path("sha256").asText("").trim().toLowerCase(Locale.ROOT);
            if (!expectedSha256.isBlank()) {
                if (!expectedSha256.matches("[0-9a-f]{64}")) {
                    throw new IOException("Invalid sha256 for model " + id);
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("sha256 can only be declared for a model file: " + pathValue);
                }
                String actual = HashUtils.sha256Hex(path);
                if (!actual.equals(expectedSha256)) {
                    throw new IOException("Model checksum mismatch for " + id);
                }
            }
            result.add(new ModelAsset(id, pathValue.replace('\\', '/'), path,
                    tokenizerValue.isBlank() ? null : tokenizerValue.replace('\\', '/'),
                    tokenizer, isDefault, provider));
        }
        if (defaults > 1) throw new IOException("Only one bundled model may be marked default");
        return result;
    }

    private static void validateLocalChatModel(Path path, String id) throws IOException {
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".gguf") && !name.endsWith(".sdz")) {
                throw new IOException("Default kompile-local model " + id
                        + " must be a .gguf/.sdz file or a directory containing one");
            }
            return;
        }
        try (var files = Files.list(path)) {
            boolean supported = files.filter(candidate -> Files.isRegularFile(
                            candidate, LinkOption.NOFOLLOW_LINKS))
                    .map(candidate -> candidate.getFileName().toString().toLowerCase(Locale.ROOT))
                    .anyMatch(name -> name.endsWith(".gguf") || name.endsWith(".sdz"));
            if (!supported) {
                throw new IOException("Default kompile-local model directory " + id
                        + " contains no .gguf or .sdz model");
            }
        }
    }

    private static List<McpServerAsset> parseMcpServers(Path root, JsonNode configured)
            throws IOException {
        JsonNode servers = configured;
        if (servers.isObject()) servers = servers.path("items");
        if (servers.isMissingNode() || servers.isNull()) return List.of();
        if (!servers.isArray()) throw new IOException("mcp.servers must be an array");
        List<McpServerAsset> result = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode server : servers) {
            String id = server.path("id").asText("").trim();
            if (id.isBlank() || !ids.add(id)) {
                throw new IOException(id.isBlank() ? "Every MCP server needs an id"
                        : "Duplicate MCP server id: " + id);
            }
            boolean bundled = server.path("bundled").asBoolean(false);
            boolean executable = server.path("executable").asBoolean(bundled);
            Path commandPath = null;
            if (bundled) {
                String command = server.path("command").asText("").trim();
                commandPath = requireBundledPath(root, command, "MCP server " + id, true);
            }
            validateSecretReferences(server.path("env"), id);
            result.add(new McpServerAsset(id, server.deepCopy(), bundled,
                    executable, commandPath));
        }
        return result;
    }

    private static void validateSecretReferences(JsonNode env, String serverId) throws IOException {
        if (env.isMissingNode() || env.isNull()) return;
        if (!env.isObject()) {
            throw new IOException("MCP server " + serverId + " env must be an object");
        }
        var fields = env.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (!SENSITIVE_ENVIRONMENT_NAME.matcher(entry.getKey()).matches()) continue;
            String value = entry.getValue().asText("").trim();
            if (!ENVIRONMENT_REFERENCE.matcher(value).matches()) {
                throw new IOException("MCP server " + serverId + " embeds a value for sensitive environment key "
                        + entry.getKey() + "; use a $VAR, ${VAR}, or %VAR% reference instead");
            }
        }
    }

    private static Path requireBundledPath(Path root, String value, String field,
                                           boolean regularFile) throws IOException {
        if (value == null || value.isBlank()) throw new IOException(field + " path is required");
        Path relative;
        try {
            relative = Path.of(value);
        } catch (RuntimeException e) {
            throw new IOException("Invalid " + field + " path: " + value, e);
        }
        if (relative.isAbsolute()) throw new IOException(field + " path must be bundle-relative: " + value);
        Path normalizedRelative = relative.normalize();
        if (normalizedRelative.toString().isBlank() || normalizedRelative.startsWith("..")) {
            throw new IOException(field + " path escapes the spin: " + value);
        }
        Path resolved = root.resolve(normalizedRelative).normalize();
        if (!resolved.startsWith(root)) throw new IOException(field + " path escapes the spin: " + value);
        rejectSymlinkPath(root, resolved, field);
        boolean exists = regularFile
                ? Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)
                : Files.exists(resolved, LinkOption.NOFOLLOW_LINKS);
        if (!exists) throw new IOException(field + " path does not exist: " + value);
        return resolved;
    }

    private static void rejectSymlinkPath(Path root, Path target, String field) throws IOException {
        Path current = root;
        Path relative = root.relativize(target);
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IOException(field + " path contains a symbolic link: " + current);
            }
        }
    }

    private static void validateToolNames(JsonNode node, String field) throws IOException {
        if (node.isMissingNode() || node.isNull() || node.isTextual()) return;
        if (!node.isArray()) throw new IOException(field + " must be a string or array");
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new IOException(field + " contains an invalid tool name");
            }
        }
    }

    static boolean hasRuntimeDistribution(Path runtimeRoot) {
        return hasRuntimeDistribution(runtimeRoot, false);
    }

    static boolean hasRuntimeDistribution(Path runtimeRoot, boolean requireModelServing) {
        boolean kompile = Files.isRegularFile(runtimeRoot.resolve("bin/kompile"), LinkOption.NOFOLLOW_LINKS)
                || Files.isRegularFile(runtimeRoot.resolve("bin/kompile.exe"), LinkOption.NOFOLLOW_LINKS)
                || Files.isRegularFile(runtimeRoot.resolve("lib/kompile-cli.jar"), LinkOption.NOFOLLOW_LINKS);
        boolean agent = Files.isRegularFile(runtimeRoot.resolve("bin/kompile-agent"), LinkOption.NOFOLLOW_LINKS)
                || Files.isRegularFile(runtimeRoot.resolve("bin/kompile-agent.exe"), LinkOption.NOFOLLOW_LINKS)
                || Files.isRegularFile(runtimeRoot.resolve("lib/kompile-agent.jar"), LinkOption.NOFOLLOW_LINKS);
        boolean modelServing = hasModelServingRuntime(runtimeRoot);
        return kompile && agent && (!requireModelServing || modelServing);
    }

    static boolean hasModelServingRuntime(Path runtimeRoot) {
        return Files.isRegularFile(runtimeRoot.resolve("bin/kompile-model-serving"), LinkOption.NOFOLLOW_LINKS)
                || Files.isRegularFile(runtimeRoot.resolve("bin/kompile-model-serving.exe"), LinkOption.NOFOLLOW_LINKS)
                || Files.isRegularFile(runtimeRoot.resolve("lib/kompile-model-serving.jar"), LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean requiresLocalModelRuntime(List<ModelAsset> models) {
        return models.stream().anyMatch(model -> model.isDefault()
                && "kompile-local".equalsIgnoreCase(model.provider()));
    }

    private static void requireMatch(String field, String value, Pattern pattern) throws IOException {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IOException(field + " is missing or invalid: " + value);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    public String systemPrompt() throws IOException {
        JsonNode prompt = agentManifest.get("systemPrompt");
        if (prompt == null || prompt.isNull()) return "";
        if (prompt.isObject()) {
            String path = prompt.path("path").asText("");
            Path file = requireBundledPath(root, path, "systemPrompt", true);
            if (Files.size(file) > MAX_SYSTEM_PROMPT_BYTES) {
                throw new IOException("systemPrompt exceeds " + MAX_SYSTEM_PROMPT_BYTES + " bytes");
            }
            return Files.readString(file);
        }
        String inline = prompt.asText("");
        if (inline.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > MAX_SYSTEM_PROMPT_BYTES) {
            throw new IOException("inline systemPrompt exceeds "
                    + MAX_SYSTEM_PROMPT_BYTES + " bytes");
        }
        return inline;
    }

    public JsonNode role() { return agentManifest.path("role"); }
    public JsonNode instructions() { return agentManifest.path("instructions"); }
    public JsonNode skills() { return agentManifest.path("skills"); }
    public JsonNode graphs() { return agentManifest.path("graphs"); }
    public Path root() { return root; }
    public JsonNode spinManifest() { return spinManifest.deepCopy(); }
    public JsonNode agentManifest() { return agentManifest.deepCopy(); }
    public List<String> entries() { return entries; }
    public String id() { return id; }
    public String version() { return version; }
    public String displayName() { return displayName; }
    public String commandName() { return commandName; }
    public String delivery() { return delivery; }
    public String roleName() { return roleName; }
    public List<ModelAsset> models() { return models; }
    public List<McpServerAsset> mcpServers() { return mcpServers; }
    public boolean requiresLocalModelRuntime() { return requiresLocalModelRuntime(models); }

    public ModelAsset defaultModel() {
        return models.stream().filter(ModelAsset::isDefault).findFirst().orElse(null);
    }

    public record ModelAsset(String id, String relativePath, Path path,
                             String relativeTokenizerPath, Path tokenizer,
                             boolean isDefault, String provider) { }

    public record McpServerAsset(String id, JsonNode config, boolean bundled,
                                 boolean executable, Path commandPath) { }
}
