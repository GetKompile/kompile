package ai.kompile.cli.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Installed, trusted-local product boundary. Browser/session state is never a policy source. */
public final class ProductChatPolicy {
    public static final String PROPERTY = "kompile.chat.product.profile";
    public static final String ENVIRONMENT = "KOMPILE_CHAT_PRODUCT_PROFILE";
    public static final String SCHEMA = "kompile.product-chat-policy.v1";
    private static final int MAX_FILE_BYTES = 1_048_576;
    private static final Set<String> FIELDS = Set.of("schemaVersion", "id", "title", "role",
            "roleFile", "mcpConfigFile", "assetsDirectory", "nativeTools", "mcpServers",
            "readCapabilities", "idleTimeoutSeconds", "turnTimeoutSeconds");
    private final Path manifestPath, workspace, roleFile, mcpConfigFile, assetsDirectory;
    public static final String ELTHORIA_CONTENT = "elthoria.content.v1";
    private final String id, title, role, digest, roleSnapshot, mcpConfigSnapshot, nativeActionPolicy;
    private final Set<String> nativeTools, mcpServers;
    private final Map<String, String> readCapabilities;
    private final int idleTimeoutSeconds, turnTimeoutSeconds;

    private ProductChatPolicy(Path manifest, Path workspace, JsonNode root, byte[] bytes) throws IOException {
        this.manifestPath = manifest;
        this.workspace = workspace;
        if (!SCHEMA.equals(text(root, "schemaVersion"))) throw invalid("schemaVersion");
        Set<String> actual = new LinkedHashSet<>();
        root.fieldNames().forEachRemaining(actual::add);
        Set<String> required = new LinkedHashSet<>(actual);
        required.remove("nativeActionPolicy");
        if (!required.equals(FIELDS)) throw invalid("manifest fields");
        nativeActionPolicy = root.has("nativeActionPolicy") ? text(root, "nativeActionPolicy") : null;
        if (nativeActionPolicy != null && !ELTHORIA_CONTENT.equals(nativeActionPolicy))
            throw invalid("nativeActionPolicy");
        id = identifier(text(root, "id"));
        title = text(root, "title");
        role = identifier(text(root, "role"));
        Path base = manifest.getParent();
        roleFile = artifact(base, text(root, "roleFile"), true);
        mcpConfigFile = artifact(base, text(root, "mcpConfigFile"), true);
        assetsDirectory = artifact(base, text(root, "assetsDirectory"), false);
        nativeTools = names(root.get("nativeTools"), false);
        mcpServers = names(root.get("mcpServers"), true);
        JsonNode caps = root.get("readCapabilities");
        if (caps == null || !caps.isObject()) throw invalid("readCapabilities");
        Map<String, String> capabilities = new LinkedHashMap<>();
        caps.fields().forEachRemaining(entry -> {
            identifier(entry.getKey());
            if (!entry.getValue().isTextual()) throw invalid("read capability tool");
            String tool = entry.getValue().textValue();
            if (!isApprovedMcpTool(tool)) throw invalid("read capability namespace");
            capabilities.put(entry.getKey(), tool);
        });
        if (capabilities.size() > 32) throw invalid("readCapabilities limit");
        readCapabilities = Collections.unmodifiableMap(capabilities);
        idleTimeoutSeconds = timeout(root, "idleTimeoutSeconds", 86400);
        turnTimeoutSeconds = timeout(root, "turnTimeoutSeconds", 3600);
        byte[] roleBytes = snapshot(roleFile);
        byte[] mcpBytes = snapshot(mcpConfigFile);
        roleSnapshot = decode(roleBytes);
        mcpConfigSnapshot = decode(mcpBytes);
        if (roleSnapshot.isBlank()) throw invalid("role prompt");
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            // Length-delimited components, including canonical workspace binding.
            for (byte[] part : new byte[][] {bytes, workspace.toString().getBytes(StandardCharsets.UTF_8), roleBytes, mcpBytes}) {
                hash.update(java.nio.ByteBuffer.allocate(4).putInt(part.length).array());
                hash.update(part);
            }
            digest = HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static Optional<Path> configuredPath() {
        String value = System.getProperty(PROPERTY);
        if (value == null || value.isBlank()) value = System.getenv(ENVIRONMENT);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(Path.of(value));
    }

    public static Optional<ProductChatPolicy> configured(Path workspace) throws IOException {
        Optional<Path> path = configuredPath();
        return path.isPresent() ? Optional.of(load(path.get(), workspace)) : Optional.empty();
    }

    public static ProductChatPolicy load(Path manifest, Path workspace) throws IOException {
        Path path = noSymlinks(manifest.toAbsolutePath().normalize());
        Path root = noSymlinks(workspace.toAbsolutePath().normalize());
        if (!Files.isDirectory(root)) throw invalid("workspace");
        byte[] bytes = snapshot(path);
        JsonNode node = strictMapper().readTree(bytes);
        if (node == null || !node.isObject()) throw invalid("manifest object");
        return new ProductChatPolicy(path, root, node, bytes);
    }

    static ObjectMapper strictMapper() {
        return new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    private static Path artifact(Path base, String value, boolean file) throws IOException {
        Path relative = Path.of(value);
        if (relative.isAbsolute()) throw invalid("absolute artifact path");
        Path result = base.resolve(relative).normalize();
        if (!result.startsWith(base) || result.equals(base)) throw invalid("artifact containment");
        // The assets directory may be absent in CLI-only installations. Existing ancestors
        // must nevertheless be symlink-free; the web host validates actual serving assets.
        Path cursor = result;
        while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) cursor = cursor.getParent();
        if (cursor != null) noSymlinks(cursor);
        if (file) {
            result = noSymlinks(result);
            if (!Files.isRegularFile(result, LinkOption.NOFOLLOW_LINKS)) throw invalid("artifact file");
        } else if (Files.exists(result) && !Files.isDirectory(result)) throw invalid("assets directory");
        return result;
    }

    private static Path noSymlinks(Path path) throws IOException {
        Path cursor = path;
        while (cursor != null) {
            if (Files.isSymbolicLink(cursor)) throw invalid("symbolic link");
            cursor = cursor.getParent();
        }
        return path.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static String decode(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }

    private static byte[] snapshot(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_FILE_BYTES)
            throw invalid("artifact size/type");
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length == 0 || bytes.length > MAX_FILE_BYTES) throw invalid("artifact size");
        return bytes;
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()
                || value.textValue().length() > 4096 || value.textValue().indexOf('\0') >= 0) throw invalid(field);
        return value.textValue();
    }

    private static String identifier(String value) {
        if (!value.matches("[A-Za-z][A-Za-z0-9_-]{0,127}")) throw invalid("identifier");
        return value;
    }

    private static Set<String> names(JsonNode node, boolean servers) {
        if (node == null || !node.isArray() || node.size() > 256) throw invalid("tool/server list");
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode entry : node) {
            if (!entry.isTextual()) throw invalid("tool/server name");
            String value = identifier(entry.textValue());
            if (!servers && value.startsWith("mcp__")) throw invalid("native namespace");
            if (!values.add(value)) throw invalid("duplicate tool/server");
        }
        return Collections.unmodifiableSet(values);
    }

    private static int timeout(JsonNode node, String field, int maximum) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < 1 || value.intValue() > maximum) throw invalid(field);
        return value.intValue();
    }

    private static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException("Invalid product chat policy: " + field);
    }

    public boolean isApprovedMcpTool(String tool) {
        if (tool == null) return false;
        for (String server : mcpServers) {
            String prefix = "mcp__" + server + "__";
            if (tool.startsWith(prefix) && tool.substring(prefix.length()).matches("[A-Za-z0-9_-]{1,128}")) return true;
        }
        return false;
    }
    public Path manifestPath() { return manifestPath; }
    public Path workspace() { return workspace; }
    public String id() { return id; }
    public String title() { return title; }
    public String role() { return role; }
    public String digest() { return digest; }
    /** Optional immutable native-action boundary; absent preserves legacy products. */
    public String nativeActionPolicy() { return nativeActionPolicy; }
    public Path roleFile() { return roleFile; }
    public Path mcpConfigFile() { return mcpConfigFile; }
    public Path assetsDirectory() { return assetsDirectory; }
    public Set<String> nativeTools() { return nativeTools; }
    public Set<String> mcpServers() { return mcpServers; }
    public Map<String, String> readCapabilities() { return readCapabilities; }
    public int idleTimeoutSeconds() { return idleTimeoutSeconds; }
    public int turnTimeoutSeconds() { return turnTimeoutSeconds; }
    /** Frozen installed role text, never re-read from the workspace. */
    public String roleSnapshot() { return roleSnapshot; }
    /** Frozen installed MCP configuration. Never include this value in browser events. */
    public String mcpConfigSnapshot() { return mcpConfigSnapshot; }
}
