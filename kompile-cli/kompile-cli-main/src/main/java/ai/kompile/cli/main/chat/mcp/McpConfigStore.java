/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.mcp;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persistent custom MCP server configuration shared by the CLI command and chat runtimes.
 *
 * <p>User configuration lives in {@code ~/.kompile/config/mcp-servers.json}; project
 * configuration uses the portable {@code .mcp.json} convention. Project entries override
 * user entries with the same name. Mutations use a sibling lock and atomic replacement so
 * concurrent CLI invocations cannot silently lose one another's updates.</p>
 */
public final class McpConfigStore {
    public static final String USER_CONFIG_FILE = "mcp-servers.json";
    public static final String PROJECT_CONFIG_FILE = ".mcp.json";
    public static final String PROJECT_DASHBOARD_FIELD = "kompile.dashboard";
    private static final int MAX_DASHBOARD_REFRESH_TOOLS = 64;
    public static final Set<String> RESERVED_SERVER_NAMES = Set.of(
            "kompile", "kompile-app", "kompile-model-staging");

    private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern POSIX_ENV_REFERENCE = Pattern.compile(
            "\\$(?:\\{([A-Za-z_][A-Za-z0-9_]*)}|([A-Za-z_][A-Za-z0-9_]*))");
    private static final Pattern WINDOWS_ENV_REFERENCE = Pattern.compile(
            "%([A-Za-z_][A-Za-z0-9_]*)%");

    public enum Scope {
        USER,
        PROJECT;

        public static Scope parse(String value) {
            if (value == null || value.isBlank()) return PROJECT;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "user", "global" -> USER;
                case "project", "local" -> PROJECT;
                default -> throw new IllegalArgumentException(
                        "Unknown MCP scope '" + value + "'. Use user or project.");
            };
        }
    }

    public enum View {
        EFFECTIVE,
        USER,
        PROJECT;

        public static View parse(String value) {
            if (value == null || value.isBlank()) return EFFECTIVE;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "effective", "all" -> EFFECTIVE;
                case "user", "global" -> USER;
                case "project", "local" -> PROJECT;
                default -> throw new IllegalArgumentException(
                        "Unknown MCP view '" + value + "'. Use effective, user, or project.");
            };
        }
    }

    public enum Transport {
        STDIO,
        HTTP,
        SSE
    }

    public record ConfiguredServer(String name, Scope scope, ObjectNode config) {
        public ConfiguredServer {
            config = config.deepCopy();
        }
    }

    /** Trusted project-owned declaration for a model-free MCP dashboard. */
    public record DashboardConfig(
            String serverName,
            String tool,
            ObjectNode arguments,
            Set<String> refreshAfterTools) {
        public DashboardConfig {
            arguments = arguments == null ? JsonUtils.standardMapper().createObjectNode()
                    : arguments.deepCopy();
            refreshAfterTools = refreshAfterTools == null
                    ? Set.of() : Set.copyOf(refreshAfterTools);
        }

        @Override
        public ObjectNode arguments() {
            return arguments.deepCopy();
        }
    }

    private final Path workspace;
    private final Path userConfig;
    private final ObjectMapper mapper;

    public McpConfigStore(Path workspace) {
        this(workspace, KompileHome.configDirectory().toPath().resolve(USER_CONFIG_FILE),
                JsonUtils.standardMapper());
    }

    McpConfigStore(Path workspace, Path userConfig, ObjectMapper mapper) {
        this.workspace = (workspace == null ? Path.of(System.getProperty("user.dir")) : workspace)
                .toAbsolutePath().normalize();
        this.userConfig = userConfig.toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    public Path workspace() {
        return workspace;
    }

    public Path path(Scope scope) {
        return scope == Scope.USER ? userConfig : workspace.resolve(PROJECT_CONFIG_FILE);
    }

    public static Path projectLockPath(Path workspace) {
        Path normalized = workspace.toAbsolutePath().normalize();
        return KompileHome.runtimeDirectory().toPath().resolve(
                "mcp-project-" + Integer.toHexString(normalized.toString().hashCode()) + ".lock");
    }

    public boolean hasProjectConfig() {
        return Files.isRegularFile(path(Scope.PROJECT));
    }

    /** True only when the project file declares at least one non-Kompile server. */
    public boolean hasProjectCustomServers() {
        if (!hasProjectConfig()) return false;
        try {
            return !readServers(Scope.PROJECT).isEmpty();
        } catch (IOException e) {
            // Invalid project-controlled JSON still requires an explicit trust decision
            // before the loader reports its parse failure.
            return true;
        }
    }

    /**
     * A project dashboard is executable project configuration and therefore takes
     * part in the same interactive workspace-trust decision as project MCP servers.
     */
    public boolean hasProjectDashboardConfig() {
        if (!hasProjectConfig()) return false;
        try {
            return readRoot(path(Scope.PROJECT)).has(PROJECT_DASHBOARD_FIELD);
        } catch (IOException e) {
            return true;
        }
    }

    /** Read and validate the optional project-scoped dashboard declaration. */
    public Optional<DashboardConfig> projectDashboard() throws IOException {
        if (!hasProjectConfig()) return Optional.empty();
        ObjectNode root = readRoot(path(Scope.PROJECT));
        JsonNode node = root.get(PROJECT_DASHBOARD_FIELD);
        if (node == null || node.isNull()) return Optional.empty();
        if (!node.isObject()) {
            throw new IOException(PROJECT_DASHBOARD_FIELD + " must be a JSON object");
        }

        String tool = node.path("tool").asText("").trim();
        if (!tool.matches("mcp__[A-Za-z0-9_-]+__[A-Za-z0-9_-]+")) {
            throw new IOException(PROJECT_DASHBOARD_FIELD
                    + ".tool must be an exact namespaced MCP tool id");
        }
        if (!node.path("readOnly").asBoolean(false)) {
            throw new IOException(PROJECT_DASHBOARD_FIELD
                    + ".readOnly must be true for automatic dashboard loading");
        }
        ObjectNode projectServers = serversObject(root);
        String serverName = node.path("server").asText("").trim();
        if (serverName.isBlank()) {
            List<String> projectServerNames = new ArrayList<>();
            projectServers.fieldNames().forEachRemaining(projectServerNames::add);
            List<String> matchingProjectServers = projectServerNames.stream()
                    .filter(name -> tool.startsWith(
                            "mcp__" + normalizeToolSegment(name) + "__"))
                    .toList();
            if (matchingProjectServers.size() != 1) {
                throw new IOException(PROJECT_DASHBOARD_FIELD
                        + ".server is required when the tool id does not identify exactly one project server");
            }
            serverName = matchingProjectServers.get(0);
        }
        JsonNode projectServer = projectServers.get(serverName);
        if (RESERVED_SERVER_NAMES.contains(serverName)
                || projectServer == null || !projectServer.isObject()) {
            throw new IOException(PROJECT_DASHBOARD_FIELD
                    + ".server must name a non-reserved server declared in the same project file");
        }
        String expectedPrefix = "mcp__" + normalizeToolSegment(serverName) + "__";
        if (!tool.startsWith(expectedPrefix)) {
            throw new IOException(PROJECT_DASHBOARD_FIELD
                    + ".tool must belong to configured server '" + serverName + "'");
        }

        JsonNode configuredArguments = node.get("arguments");
        if (configuredArguments != null && !configuredArguments.isObject()) {
            throw new IOException(PROJECT_DASHBOARD_FIELD + ".arguments must be a JSON object");
        }
        ObjectNode arguments = configuredArguments == null
                ? mapper.createObjectNode() : ((ObjectNode) configuredArguments).deepCopy();

        JsonNode configuredRefreshTools = node.get("refreshAfterTools");
        if (configuredRefreshTools != null && !configuredRefreshTools.isArray()) {
            throw new IOException(PROJECT_DASHBOARD_FIELD
                    + ".refreshAfterTools must be a JSON array");
        }
        LinkedHashSet<String> refreshAfterTools = new LinkedHashSet<>();
        if (configuredRefreshTools != null) {
            for (JsonNode value : configuredRefreshTools) {
                String candidate = value.asText("").trim();
                if (!candidate.matches("mcp__[A-Za-z0-9_-]+__[A-Za-z0-9_-]+")) {
                    throw new IOException(PROJECT_DASHBOARD_FIELD
                            + ".refreshAfterTools contains an invalid tool id");
                }
                refreshAfterTools.add(candidate);
                if (refreshAfterTools.size() > MAX_DASHBOARD_REFRESH_TOOLS) {
                    throw new IOException(PROJECT_DASHBOARD_FIELD
                            + ".refreshAfterTools exceeds " + MAX_DASHBOARD_REFRESH_TOOLS);
                }
            }
        }
        return Optional.of(new DashboardConfig(
                serverName, tool, arguments, refreshAfterTools));
    }

    private static String normalizeToolSegment(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    public List<ConfiguredServer> list(View view) throws IOException {
        Map<String, ConfiguredServer> servers = switch (view) {
            case USER -> readServers(Scope.USER);
            case PROJECT -> readServers(Scope.PROJECT);
            case EFFECTIVE -> effectiveServers(true);
        };
        List<ConfiguredServer> result = new ArrayList<>(servers.values());
        result.sort(Comparator.comparing(ConfiguredServer::name));
        return List.copyOf(result);
    }

    public Optional<ConfiguredServer> find(String name, View view) throws IOException {
        if (name == null) return Optional.empty();
        return list(view).stream().filter(server -> server.name().equals(name)).findFirst();
    }

    /** Effective user + optional project configuration, with project entries winning. */
    public Map<String, ConfiguredServer> effectiveServers(boolean includeProject) throws IOException {
        Map<String, ConfiguredServer> effective = new LinkedHashMap<>(readServers(Scope.USER));
        if (includeProject) effective.putAll(readServers(Scope.PROJECT));
        return effective;
    }

    public void put(String name, ObjectNode config, Scope scope, boolean replace) throws IOException {
        validateServerName(name);
        validateConfig(name, config);
        mutate(scope, root -> {
            ObjectNode servers = serversObject(root);
            if (servers.has(name) && !replace) {
                throw new IllegalArgumentException("MCP server '" + name
                        + "' already exists in " + scope.name().toLowerCase(Locale.ROOT)
                        + " scope. Pass --replace to update it.");
            }
            servers.set(name, config.deepCopy());
            return root;
        });
    }

    public boolean remove(String name, Scope scope) throws IOException {
        validateServerName(name);
        final boolean[] removed = {false};
        mutate(scope, root -> {
            ObjectNode servers = serversObject(root);
            removed[0] = servers.remove(name) != null;
            return removed[0] ? root : null;
        });
        return removed[0];
    }

    public boolean setEnabled(String name, Scope scope, boolean enabled) throws IOException {
        validateServerName(name);
        final boolean[] found = {false};
        mutate(scope, root -> {
            ObjectNode servers = serversObject(root);
            JsonNode existing = servers.get(name);
            if (existing != null && existing.isObject()) {
                ((ObjectNode) existing).put("enabled", enabled);
                found[0] = true;
            }
            return found[0] ? root : null;
        });
        return found[0];
    }

    public static void validateServerName(String name) {
        if (name == null || !SERVER_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "MCP server names must start with a letter or number and contain only letters, numbers, '.', '_', or '-'.");
        }
        if (RESERVED_SERVER_NAMES.contains(name)) {
            throw new IllegalArgumentException("MCP server name '" + name
                    + "' is reserved for Kompile's built-in integration.");
        }
    }

    public static void validateConfig(String name, JsonNode config) {
        if (config == null || !config.isObject()) {
            throw new IllegalArgumentException("MCP server '" + name + "' must be a JSON object.");
        }
        Transport transport = transportOf(config);
        if (transport == Transport.STDIO && config.path("command").asText("").isBlank()) {
            throw new IllegalArgumentException("MCP stdio server '" + name + "' requires command.");
        }
        if (transport != Transport.STDIO && endpointOf(config).isBlank()) {
            throw new IllegalArgumentException("MCP " + transport.name().toLowerCase(Locale.ROOT)
                    + " server '" + name + "' requires a URL.");
        }
        if (transport != Transport.STDIO) {
            URI endpoint;
            try {
                endpoint = URI.create(endpointOf(config));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid MCP URL for '" + name + "'", e);
            }
            if (!("http".equalsIgnoreCase(endpoint.getScheme())
                    || "https".equalsIgnoreCase(endpoint.getScheme()))) {
                throw new IllegalArgumentException("MCP URL must use http or https for '" + name + "'");
            }
            if (endpoint.getUserInfo() != null) {
                throw new IllegalArgumentException("MCP URL must not embed user credentials for '"
                        + name + "'; use environment-backed headers instead");
            }
        }
    }

    /**
     * Infer legacy entries while preferring an explicit type/transport field.
     * A bare url remains legacy SSE-compatible; use type=http for Streamable HTTP.
     */
    public static Transport transportOf(JsonNode config) {
        String explicit = config.path("type").asText(config.path("transport").asText(""))
                .trim().toLowerCase(Locale.ROOT);
        if (!explicit.isBlank()) {
            return switch (explicit) {
                case "stdio", "local" -> Transport.STDIO;
                case "http", "streamable-http", "streamable_http", "remote" -> Transport.HTTP;
                case "sse" -> Transport.SSE;
                default -> throw new IllegalArgumentException("Unsupported MCP transport '" + explicit
                        + "'. Use stdio, http, or sse.");
            };
        }
        if (!config.path("httpUrl").asText("").isBlank()) return Transport.HTTP;
        if (!config.path("url").asText("").isBlank()) return Transport.SSE;
        return Transport.STDIO;
    }

    public static String endpointOf(JsonNode config) {
        String httpUrl = config.path("httpUrl").asText("").trim();
        return httpUrl.isBlank() ? config.path("url").asText("").trim() : httpUrl;
    }

    public static long timeoutSeconds(JsonNode config) {
        if (config.has("timeoutSeconds")) return Math.max(1L, config.path("timeoutSeconds").asLong(300L));
        if (config.has("timeout")) {
            long millis = Math.max(1L, config.path("timeout").asLong(300_000L));
            return Math.max(1L, (millis + 999L) / 1000L);
        }
        return 300L;
    }

    public static Map<String, String> expandedStringMap(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> result.put(
                    entry.getKey(), expandEnvironmentReferences(entry.getValue().asText(""))));
        }
        return result;
    }

    public static String expandEnvironmentReferences(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        String expanded = replaceEnvironmentReferences(value, POSIX_ENV_REFERENCE, true);
        return replaceEnvironmentReferences(expanded, WINDOWS_ENV_REFERENCE, false);
    }

    private static String replaceEnvironmentReferences(
            String value, Pattern pattern, boolean posix) {
        Matcher matcher = pattern.matcher(value);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String variable = posix
                    ? (matcher.group(1) != null ? matcher.group(1) : matcher.group(2))
                    : matcher.group(1);
            String replacement = System.getenv().getOrDefault(variable, "");
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    /** Return a display-safe copy that never reveals configured env/header values. */
    public static ObjectNode redacted(ObjectNode config) {
        ObjectNode copy = config.deepCopy();
        redactObject(copy, "env");
        redactObject(copy, "headers");
        redactObject(copy, "httpHeaders");
        redactUrlField(copy, "url");
        redactUrlField(copy, "httpUrl");
        return copy;
    }

    public static String redactedEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return endpoint == null ? "" : endpoint;
        try {
            URI uri = URI.create(endpoint);
            return new URI(uri.getScheme(), uri.getUserInfo() == null ? null : "<redacted>",
                    uri.getHost(), uri.getPort(), uri.getPath(),
                    uri.getRawQuery() == null ? null : "<redacted>",
                    uri.getRawFragment() == null ? null : "<redacted>")
                    .toASCIIString();
        } catch (IllegalArgumentException | URISyntaxException e) {
            int query = endpoint.indexOf('?');
            return query < 0 ? endpoint : endpoint.substring(0, query) + "?<redacted>";
        }
    }

    private static void redactUrlField(ObjectNode root, String field) {
        JsonNode value = root.get(field);
        if (value != null && value.isTextual()) {
            root.put(field, redactedEndpoint(value.asText()));
        }
    }

    private static void redactObject(ObjectNode root, String field) {
        JsonNode node = root.get(field);
        if (node != null && node.isObject()) {
            node.fieldNames().forEachRemaining(name -> ((ObjectNode) node).put(name, "<redacted>"));
        }
    }

    private Map<String, ConfiguredServer> readServers(Scope scope) throws IOException {
        Path configPath = path(scope);
        if (!Files.isRegularFile(configPath)) return Map.of();
        ObjectNode root = readRoot(configPath);
        ObjectNode servers = serversObject(root);
        Map<String, ConfiguredServer> result = new LinkedHashMap<>();
        servers.fields().forEachRemaining(entry -> {
            if (!RESERVED_SERVER_NAMES.contains(entry.getKey())
                    && entry.getValue().isObject()) {
                result.put(entry.getKey(), new ConfiguredServer(
                        entry.getKey(), scope, (ObjectNode) entry.getValue()));
            }
        });
        return result;
    }

    private void mutate(Scope scope, UnaryOperator<ObjectNode> mutation) throws IOException {
        Path configPath = path(scope);
        Files.createDirectories(configPath.getParent());
        Path lockPath = scope == Scope.USER
                ? KompileHome.runtimeDirectory().toPath().resolve("mcp-user-config.lock")
                : projectLockPath(workspace);
        Files.createDirectories(lockPath.getParent());
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            ObjectNode root = Files.isRegularFile(configPath)
                    ? readRoot(configPath) : mapper.createObjectNode();
            ObjectNode updated;
            try {
                updated = mutation.apply(root);
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new IOException("Could not update MCP config " + configPath + ": " + e.getMessage(), e);
            }
            if (updated != null) {
                writeAtomic(configPath, updated, scope == Scope.USER);
            }
        }
    }

    private ObjectNode readRoot(Path configPath) throws IOException {
        JsonNode parsed;
        try {
            parsed = mapper.readTree(Files.readString(configPath, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IOException("Invalid MCP JSON in " + configPath + ": " + e.getMessage(), e);
        }
        if (parsed == null || !parsed.isObject()) {
            throw new IOException("MCP config must contain a JSON object: " + configPath);
        }
        JsonNode servers = parsed.get("mcpServers");
        if (servers != null && !servers.isObject()) {
            throw new IOException("mcpServers must be a JSON object in " + configPath);
        }
        return (ObjectNode) parsed;
    }

    private static ObjectNode serversObject(ObjectNode root) {
        JsonNode existing = root.get("mcpServers");
        if (existing == null) return root.putObject("mcpServers");
        if (!existing.isObject()) {
            throw new IllegalArgumentException("mcpServers must be a JSON object");
        }
        return (ObjectNode) existing;
    }

    private void writeAtomic(Path configPath, ObjectNode root, boolean privateFile) throws IOException {
        Path parent = configPath.getParent();
        Path temporary = Files.createTempFile(parent, configPath.getFileName() + ".", ".tmp");
        try {
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
            Files.writeString(temporary, json, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            if (privateFile) makePrivate(temporary);
            try {
                Files.move(temporary, configPath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
            if (privateFile) makePrivate(configPath);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void makePrivate(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Non-POSIX platforms enforce access through their native ACLs.
        }
    }
}
