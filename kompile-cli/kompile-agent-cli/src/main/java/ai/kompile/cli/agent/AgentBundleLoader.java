/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.agent;

import ai.kompile.cli.common.mcp.McpSseClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Loads, validates, materializes, and packs portable .kagent bundles. */
public final class AgentBundleLoader {
    public static final String MANIFEST = "agent.yaml";
    public static final String JSON_MANIFEST = "agent.json";

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper json = new ObjectMapper();

    public LoadedBundle load(Path source) throws IOException {
        Objects.requireNonNull(source, "source");
        Path normalized = source.toAbsolutePath().normalize();
        if (Files.isDirectory(normalized)) {
            List<String> entries = listEntries(normalized);
            JsonNode manifest = readManifest(normalized, entries);
            validateManifest(manifest, entries);
            return new LoadedBundle(normalized, normalized, manifest, entries, false, false);
        }
        if (!Files.isRegularFile(normalized)) throw new IOException("Bundle does not exist: " + source);
        List<String> entries = new ArrayList<>();
        JsonNode manifest;
        try (ZipFile zip = new ZipFile(normalized.toFile())) {
            zip.stream().forEach(entry -> {
                String name = entry.getName().replace('\\', '/');
                validateEntryName(name);
                entries.add(name);
            });
            manifest = readManifest(zip, entries);
        }
        validateManifest(manifest, entries);
        return new LoadedBundle(normalized, null, manifest, List.copyOf(entries), true, false);
    }

    public static void pack(Path source, Path output) throws IOException {
        AgentBundleLoader loader = new AgentBundleLoader();
        try (LoadedBundle bundle = loader.load(source)) {
            if (bundle.archive()) throw new IOException("Packing an archive is not supported: " + source);
            Path out = output.toAbsolutePath().normalize();
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            if (out.startsWith(bundle.source())) throw new IOException("Output archive must not be inside the source bundle");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
                for (String entry : bundle.entries().stream().sorted().toList()) {
                    Path file = bundle.source().resolve(entry).normalize();
                    if (!file.startsWith(bundle.source()) || !Files.isRegularFile(file)) continue;
                    ZipEntry zipEntry = new ZipEntry(entry);
                    zipEntry.setTime(0L);
                    zip.putNextEntry(zipEntry);
                    Files.copy(file, zip);
                    zip.closeEntry();
                }
            }
        }
    }

    private JsonNode readManifest(Path root, List<String> entries) throws IOException {
        if (entries.contains(MANIFEST)) return yaml.readTree(Files.readString(root.resolve(MANIFEST)));
        if (entries.contains(JSON_MANIFEST)) return json.readTree(Files.readString(root.resolve(JSON_MANIFEST)));
        throw new IOException("Bundle is missing " + MANIFEST);
    }

    private JsonNode readManifest(ZipFile zip, List<String> entries) throws IOException {
        String name = entries.contains(MANIFEST) ? MANIFEST : JSON_MANIFEST;
        if (!entries.contains(name)) throw new IOException("Bundle is missing " + MANIFEST);
        try (InputStream in = zip.getInputStream(zip.getEntry(name))) {
            return name.endsWith(".yaml") ? yaml.readTree(in) : json.readTree(in);
        }
    }

    private static List<String> listEntries(Path root) throws IOException {
        List<String> entries = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file)) throw new IOException("Symlinks are not allowed in bundles: " + file);
                entries.add(root.relativize(file).toString().replace('\\', '/'));
                return FileVisitResult.CONTINUE;
            }
        });
        return entries;
    }

    private static void validateEntryName(String name) {
        if (name.isBlank() || name.startsWith("/") || name.matches("^[A-Za-z]:.*")
                || name.contains("../") || name.equals("..") || name.contains("\\")) {
            throw new IllegalArgumentException("Unsafe bundle entry: " + name);
        }
    }

    private static void validateManifest(JsonNode manifest, List<String> entries) throws IOException {
        if (manifest == null || !manifest.isObject()) throw new IOException("agent.yaml must contain an object");
        String schema = manifest.path("schemaVersion").asText("1");
        if (!(schema.equals("1") || schema.equals("v1"))) throw new IOException("Unsupported agent bundle schema: " + schema);
        String name = manifest.path("metadata").path("name").asText("").trim();
        if (name.isEmpty()) throw new IOException("agent.yaml metadata.name is required");
        String engine = manifest.path("engine").asText("cli-loop");
        if (!Set.of("cli-loop", "react", "external-cli").contains(engine)) throw new IOException("Unsupported agent engine: " + engine);
        if ("external-cli".equals(engine)) {
            JsonNode command = manifest.path("command");
            if (!(command.isTextual() && !command.asText().isBlank())
                    && !(command.isArray() && !command.isEmpty())) {
                throw new IOException("external-cli bundles must declare command");
            }
        }
        JsonNode systemPrompt = manifest.get("systemPrompt");
        if (systemPrompt != null && systemPrompt.isObject()) {
            String path = systemPrompt.path("path").asText("");
            if (path.isBlank() || !entries.contains(path.replace('\\', '/'))) {
                throw new IOException("systemPrompt references a missing bundle entry: " + path);
            }
        }
        validateResourceList(manifest.path("instructions"), entries, "instructions");
        validateResourceList(manifest.path("skills"), entries, "skills");
        validateResourceList(manifest.path("graphs"), entries, "graphs");
        JsonNode servers = manifest.path("mcp").path("servers");
        if (servers.isObject()) servers = servers.path("items");
        if (servers.isMissingNode() || servers.isNull()) return;
        if (!servers.isArray()) throw new IOException("mcp.servers must be an array");
        for (JsonNode server : servers) {
            if (server.path("id").asText("").isBlank()) throw new IOException("Every MCP server needs an id");
            boolean hasUrl = !server.path("url").asText("").isBlank();
            boolean hasCommand = !server.path("command").asText("").isBlank();
            if (!hasUrl && !hasCommand) throw new IOException("MCP server must specify url or command: " + server.path("id").asText());
            if (hasUrl && hasCommand) throw new IOException("MCP server cannot specify both url and command: " + server.path("id").asText());
        }
    }

    private static void validateResourceList(JsonNode node, List<String> entries, String field) throws IOException {
        if (node.isMissingNode() || node.isNull()) return;
        if (!node.isArray()) throw new IOException(field + " must be an array");
        for (JsonNode item : node) {
            String path = item.isTextual() ? item.asText() : item.path("path").asText("");
            if (path.isBlank() || !entries.contains(path.replace('\\', '/'))) throw new IOException(field + " references a missing bundle entry: " + path);
        }
    }

    public final class LoadedBundle implements AutoCloseable {
        private final Path source;
        private Path root;
        private final JsonNode manifest;
        private final List<String> entries;
        private final boolean archive;
        private boolean temporary;

        private LoadedBundle(Path source, Path root, JsonNode manifest, List<String> entries, boolean archive, boolean temporary) {
            this.source = source;
            this.root = root;
            this.manifest = manifest;
            this.entries = entries;
            this.archive = archive;
            this.temporary = temporary;
        }

        public Path source() { return source; }
        public Path root() { return root; }
        public JsonNode manifest() { return manifest; }
        public List<String> entries() { return entries; }
        public boolean archive() { return archive; }

        /** Copy the bundle into an isolated workspace and materialize context and MCP config. */
        public Path materialize() throws IOException {
            if (temporary && root != null) return root;
            Path workspace = Files.createTempDirectory("kompile-agent-");
            try {
                if (archive) {
                    try (ZipFile zip = new ZipFile(source.toFile())) {
                        for (String name : entries) {
                            ZipEntry entry = zip.getEntry(name);
                            Path target = workspace.resolve(name).normalize();
                            if (!target.startsWith(workspace)) throw new IOException("Unsafe bundle entry: " + name);
                            if (entry.isDirectory()) Files.createDirectories(target);
                            else {
                                Files.createDirectories(target.getParent());
                                try (InputStream in = zip.getInputStream(entry)) { Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING); }
                            }
                        }
                    }
                } else {
                    copyTree(source, workspace);
                }
                root = workspace;
                temporary = true;
                materializeContext();
                materializeGraphCatalog();
                materializeMcpConfig();
                return root;
            } catch (IOException | RuntimeException e) {
                deleteTree(workspace);
                root = null;
                temporary = false;
                throw e;
            }
        }

        private void materializeContext() throws IOException {
            StringBuilder context = new StringBuilder();
            JsonNode prompt = manifest.get("systemPrompt");
            if (prompt != null && !prompt.isNull()) {
                String value = prompt.isObject() ? prompt.path("path").asText("") : prompt.asText();
                context.append(readResource(value)).append('\n');
            }
            appendResources(context, manifest.path("instructions"));
            appendResources(context, manifest.path("skills"));
            if (context.isEmpty()) return;
            Path agents = root.resolve("AGENTS.md");
            String existing = Files.exists(agents) ? Files.readString(agents) : "";
            Files.writeString(agents, context + (existing.isBlank() ? "" : "\n" + existing), StandardCharsets.UTF_8);
        }

        private void appendResources(StringBuilder target, JsonNode resources) throws IOException {
            if (!resources.isArray()) return;
            for (JsonNode item : resources) {
                String path = item.isTextual() ? item.asText() : item.path("path").asText();
                target.append("\n\n# Bundle resource: ").append(path).append("\n\n").append(readResource(path)).append('\n');
            }
        }

        private String readResource(String value) throws IOException {
            Path candidate = root.resolve(value).normalize();
            if (candidate.startsWith(root) && Files.isRegularFile(candidate)) return Files.readString(candidate);
            return value;
        }

        private void materializeMcpConfig() throws IOException {
            JsonNode servers = manifest.path("mcp").path("servers");
            if (servers.isObject()) servers = servers.path("items");
            if (!servers.isArray() || servers.isEmpty()) return;
            ObjectNode config = json.createObjectNode();
            ObjectNode mcpServers = config.putObject("mcpServers");
            for (JsonNode server : servers) {
                ObjectNode value = mcpServers.putObject(server.path("id").asText());
                if (server.has("url")) value.put("url", server.path("url").asText());
                if (server.has("command")) {
                    value.put("command", server.path("command").asText());
                    ArrayNode args = value.putArray("args");
                    if (server.path("args").isArray()) server.path("args").forEach(args::add);
                }
                if (server.path("env").isObject()) value.set("env", server.path("env"));
            }
            Files.writeString(root.resolve(".mcp.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(config));
        }

        private void materializeGraphCatalog() throws IOException {
            JsonNode graphs = manifest.path("graphs");
            if (!graphs.isArray() || graphs.isEmpty()) return;
            Path metadata = root.resolve(".kompile");
            Files.createDirectories(metadata);
            ArrayNode catalog = json.createArrayNode();
            for (JsonNode item : graphs) {
                String path = item.isTextual() ? item.asText() : item.path("path").asText();
                ObjectNode graph = catalog.addObject().put("path", path);
                if (item.isObject() && item.has("id")) graph.put("id", item.path("id").asText());
                if (item.isObject() && item.has("format")) graph.put("format", item.path("format").asText());
            }
            Files.writeString(metadata.resolve("agent-graphs.json"),
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(catalog), StandardCharsets.UTF_8);
        }

        public List<String> discoverTools() {
            List<String> result = new ArrayList<>();
            JsonNode servers = manifest.path("mcp").path("servers");
            if (servers.isObject()) servers = servers.path("items");
            if (!servers.isArray()) return result;
            for (JsonNode server : servers) {
                String url = server.path("url").asText("");
                if (!url.isBlank()) {
                    try (McpSseClient client = new McpSseClient(url)) {
                        client.connect();
                        client.initialize();
                        for (McpSseClient.ToolInfo tool : client.listTools()) result.add(server.path("id").asText() + "/" + tool.getName() + " - " + tool.getDescription());
                    } catch (Exception e) {
                        result.add(server.path("id").asText() + " [unavailable: " + e.getMessage() + "]");
                    }
                } else {
                    String command = server.path("command").asText("").trim();
                    if (!command.isBlank()) {
                        try {
                            result.addAll(discoverStdioTools(server));
                        } catch (Exception e) {
                            result.add(server.path("id").asText() + " [unavailable: " + e.getMessage() + "]");
                        }
                    }
                }
            }
            return result;
        }

        /** Backwards-compatible alias for callers that only expect HTTP/SSE discovery. */
        public List<String> discoverHttpTools() { return discoverTools(); }

        private List<String> discoverStdioTools(JsonNode server) throws IOException {
            if (root == null) materialize();
            List<String> commandLine = new ArrayList<>();
            commandLine.add(server.path("command").asText());
            if (server.path("args").isArray()) server.path("args").forEach(node -> commandLine.add(node.asText()));
            ProcessBuilder builder = new ProcessBuilder(commandLine).redirectError(ProcessBuilder.Redirect.INHERIT);
            if (root != null && Files.isDirectory(root)) builder.directory(root.toFile());
            if (server.path("env").isObject()) {
                server.path("env").fields().forEachRemaining(entry -> builder.environment().put(entry.getKey(), entry.getValue().asText()));
            }
            Process process = builder.start();
            try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter output = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
                ObjectNode params = json.createObjectNode().put("protocolVersion", "2024-11-05");
                params.putObject("capabilities");
                params.putObject("clientInfo").put("name", "kompile-agent").put("version", "0.1");
                sendStdioRequest(input, output, 1, "initialize", params);
                sendStdioNotification(output, "notifications/initialized", json.createObjectNode());
                JsonNode tools = sendStdioRequest(input, output, 2, "tools/list", json.createObjectNode());
                List<String> result = new ArrayList<>();
                for (JsonNode tool : tools.path("tools")) {
                    result.add(server.path("id").asText() + "/" + tool.path("name").asText()
                            + " - " + tool.path("description").asText(""));
                }
                return result;
            } finally {
                process.destroy();
                if (process.isAlive()) process.destroyForcibly();
            }
        }

        private JsonNode sendStdioRequest(BufferedReader input, BufferedWriter output, long id,
                                          String method, JsonNode params) throws IOException {
            ObjectNode request = json.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
            request.set("params", params);
            output.write(json.writeValueAsString(request));
            output.write('\n');
            output.flush();
            String line;
            while ((line = input.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode response = json.readTree(line);
                if (response.path("id").isNumber() && response.path("id").asLong() == id) {
                    if (response.has("error")) throw new IOException("MCP error: " + response.path("error"));
                    return response.path("result");
                }
            }
            throw new IOException("MCP stdio server closed its output");
        }

        private void sendStdioNotification(BufferedWriter output, String method, JsonNode params) throws IOException {
            ObjectNode notification = json.createObjectNode().put("jsonrpc", "2.0").put("method", method);
            notification.set("params", params);
            output.write(json.writeValueAsString(notification));
            output.write('\n');
            output.flush();
        }

        @Override
        public void close() {
            if (!temporary || root == null) return;
            deleteTree(root);
            root = null;
            temporary = false;
        }
    }

    private static void deleteTree(Path root) {
        try { Files.walk(root).sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } }); }
        catch (IOException ignored) { }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file)) throw new IOException("Symlinks are not allowed in bundles: " + file);
                Path dest = target.resolve(source.relativize(file).toString());
                Files.createDirectories(dest.getParent());
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
