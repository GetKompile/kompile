/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.cli.main.cloud;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.config.ImportMode;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Captures and restores portable, non-secret Kompile settings bundles. */
public class CloudSettingsBundleService {

    public static final int FORMAT_VERSION = 1;
    private static final int MAX_FILES = 256;
    private static final int MAX_PAYLOAD_BYTES = 1_048_576;
    private static final int MAX_LOCAL_FILE_BYTES = 1_048_576;
    private static final int MAX_ROLLBACK_BYTES = 4_194_304;
    private static final int MAX_DEPTH = 32;
    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();
    private static final Pattern PROFILE_KEY = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern JSON_FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*\\.json");
    private static final Pattern ENV_REFERENCE = Pattern.compile(
            "\\$(?:\\{[A-Za-z_][A-Za-z0-9_]*}|[A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern URI_USER_INFO = Pattern.compile(
            "(?i)^(?:jdbc:)?[a-z][a-z0-9+.-]*://[^/@\\s:]+:[^/@\\s]+@.+$");
    private static final Pattern CREDENTIAL_ASSIGNMENT = Pattern.compile(
            "(?i)(?:^|[?;&,\\s])(?:[a-z0-9_-]*(?:password|passwd|pwd|token|api[-_]?key|secret|credential|authorization|cookie|access[-_]?key)[a-z0-9_-]*)\\s*=");
    private static final Pattern AUTHORIZATION_HEADER = Pattern.compile(
            "(?i)(?:^|[\\r\\n,;])\\s*(?:proxy-)?authorization\\s*:\\s*(?:bearer|basic)\\s+\\S+");
    private static final Pattern AUTHORIZATION_VALUE = Pattern.compile(
            "(?i)^(?:bearer|basic)\\s+\\S+$");
    private static final Pattern ORACLE_JDBC_CREDENTIAL = Pattern.compile(
            "(?i)^jdbc:oracle:[^:]+:[^/\\s:]+/[^@\\s]+@.+$");
    private static final Set<String> BUNDLE_FIELDS = Set.of("formatVersion", "files");

    private static final Set<String> USER_TOP_LEVEL_FILES = Set.of(
            "chat-config.json",
            "harness-config.json",
            "staging-settings.json",
            "code-graph-reasoning.json",
            "lsp-servers.json");

    private static final Set<String> PROJECT_DOT_KOMPILE_FILES = Set.of(
            "chat-config.json",
            "enforcer-config.json",
            "agent-defaults.json",
            "agent-flags.json",
            "code-graph-reasoning.json",
            "chat-reminders.json",
            "terminal.json",
            "resume.json");

    private static final Set<String> FORBIDDEN_FILE_NAMES = Set.of(
            "auth.json",
            "saas-credentials.json",
            "credentials.json",
            "connections.json",
            "channel-connections.json",
            "registration.json",
            "state.json",
            "perf-data.json");

    private final Path userHome;
    private final BeforeWrite beforeWrite;

    public CloudSettingsBundleService() {
        this(KompileHome.homeDirectory().toPath());
    }

    CloudSettingsBundleService(Path userHome) {
        this(userHome, path -> { });
    }

    CloudSettingsBundleService(Path userHome, BeforeWrite beforeWrite) {
        this.userHome = userHome.toAbsolutePath().normalize();
        this.beforeWrite = beforeWrite;
    }

    public CaptureResult capture(Scope scope, Path projectRoot) throws IOException {
        if (scope == null) {
            throw new IllegalArgumentException("scope is required");
        }
        ObjectNode bundle = MAPPER.createObjectNode();
        bundle.put("formatVersion", FORMAT_VERSION);
        ObjectNode files = bundle.putObject("files");
        List<String> redacted = new ArrayList<>();
        CaptureBudget budget = new CaptureBudget();

        if (scope.includesUser()) {
            captureDirectory(userHome, userHome.resolve("config"),
                    "user/config/", files, redacted, budget);
            for (String name : USER_TOP_LEVEL_FILES.stream().sorted().collect(Collectors.toList())) {
                captureFile(userHome, userHome.resolve(name),
                        "user/" + name, files, redacted, budget);
            }
        }

        if (scope.includesProject()) {
            Path root = requireProjectRoot(projectRoot);
            captureDirectory(root, root.resolve("config"),
                    "project/config/", files, redacted, budget);
            captureDirectory(root, root.resolve(".kompile").resolve("config"),
                    "project/.kompile/config/", files, redacted, budget);
            for (String name : PROJECT_DOT_KOMPILE_FILES.stream().sorted().collect(Collectors.toList())) {
                captureFile(root, root.resolve(".kompile").resolve(name),
                        "project/.kompile/" + name, files, redacted, budget);
            }
        }

        validateBundle(bundle);
        List<String> captured = new ArrayList<>();
        files.fieldNames().forEachRemaining(captured::add);
        return new CaptureResult(bundle, captured, redacted);
    }

    public ApplyResult apply(JsonNode bundle, ImportMode mode, Path projectRoot) throws IOException {
        if (mode == null) {
            throw new IllegalArgumentException("mode is required");
        }
        validateBundle(bundle);
        try (SettingsLock ignored = acquireSettingsLock()) {
            return applyLocked(bundle, mode, projectRoot);
        }
    }

    private ApplyResult applyLocked(JsonNode bundle, ImportMode mode, Path projectRoot)
            throws IOException {
        List<PreparedWrite> writes = new ArrayList<>();
        long rollbackBytes = 0;
        long replacementBytes = 0;
        Iterator<Map.Entry<String, JsonNode>> entries = bundle.path("files").fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            ResolvedTarget resolved = resolveTarget(entry.getKey(), projectRoot);
            ensureNoSymlink(resolved.trustRoot, resolved.target);

            byte[] originalBytes = readExistingBytes(resolved);
            if (originalBytes != null) {
                rollbackBytes += originalBytes.length;
                if (rollbackBytes > MAX_ROLLBACK_BYTES) {
                    throw new IOException("Existing settings exceed the "
                            + MAX_ROLLBACK_BYTES + " byte rollback budget");
                }
            }
            ObjectNode existing = parseExistingObject(resolved.target, originalBytes);
            ObjectNode incoming = (ObjectNode) entry.getValue();
            ObjectNode output;
            if (mode == ImportMode.APPEND && existing != null) {
                output = deepMerge(existing.deepCopy(), incoming);
            } else {
                output = incoming.deepCopy();
            }
            if (existing != null) {
                preserveSensitiveFields(existing, output, entry.getKey());
            }
            byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(output);
            replacementBytes += bytes.length;
            if (bytes.length > MAX_LOCAL_FILE_BYTES || replacementBytes > MAX_ROLLBACK_BYTES) {
                throw new IOException("Applied settings exceed the local write budget");
            }
            writes.add(new PreparedWrite(entry.getKey(), resolved, bytes, originalBytes));
        }

        List<String> created = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<PreparedWrite> attempted = new ArrayList<>();
        try {
            for (PreparedWrite write : writes) {
                beforeWrite.run(write.target.target);
                attempted.add(write);
                atomicWriteIfUnchanged(write);
                write.applied = true;
                (write.existed() ? updated : created).add(write.bundlePath);
            }
        } catch (IOException | RuntimeException failure) {
            rollback(attempted, failure);
            throw failure;
        }
        return new ApplyResult(created, updated);
    }

    private SettingsLock acquireSettingsLock() throws IOException {
        Files.createDirectories(userHome);
        ensureNoSymlink(userHome, userHome);
        Path lockPath = userHome.resolve(".cloud-settings.lock");
        ensureNoSymlink(userHome, lockPath);
        FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        try {
            return new SettingsLock(channel, channel.lock());
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    public void validateBundle(JsonNode bundle) {
        if (bundle == null || !bundle.isObject()) {
            throw new IllegalArgumentException("Settings bundle must be a JSON object");
        }
        Iterator<String> bundleFields = bundle.fieldNames();
        while (bundleFields.hasNext()) {
            String field = bundleFields.next();
            if (!BUNDLE_FIELDS.contains(field)) {
                throw new IllegalArgumentException("Unknown settings bundle field: " + field);
            }
        }
        JsonNode version = bundle.get("formatVersion");
        if (version == null || !version.isIntegralNumber()
                || !version.canConvertToInt() || version.asInt() != FORMAT_VERSION) {
            throw new IllegalArgumentException("settings.formatVersion must be 1");
        }
        JsonNode files = bundle.get("files");
        if (files == null || !files.isObject()) {
            throw new IllegalArgumentException("settings.files must be a JSON object");
        }
        if (files.size() > MAX_FILES) {
            throw new IllegalArgumentException(
                    "Settings bundle contains " + files.size() + " files; maximum is " + MAX_FILES);
        }

        Set<String> normalizedPaths = new HashSet<>();
        Iterator<Map.Entry<String, JsonNode>> entries = files.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            validateBundlePath(entry.getKey());
            if (!normalizedPaths.add(entry.getKey().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "Settings paths collide on a case-insensitive filesystem: "
                                + entry.getKey());
            }
            if (!entry.getValue().isObject()) {
                throw new IllegalArgumentException(
                        "Settings file must contain a JSON object: " + entry.getKey());
            }
            validateDepth(entry.getValue(), 1, entry.getKey());
            validateNoPlaintextSecrets(entry.getValue(), entry.getKey());
        }

        try {
            int size = MAPPER.writeValueAsBytes(bundle).length;
            if (size > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException(
                        "Settings payload is " + size + " bytes; maximum is " + MAX_PAYLOAD_BYTES);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to serialize settings bundle", e);
        }
    }

    public static void validateProfileKey(String profileKey) {
        if (profileKey == null || !PROFILE_KEY.matcher(profileKey).matches()) {
            throw new IllegalArgumentException("Profile name must match " + PROFILE_KEY.pattern());
        }
    }

    private void captureDirectory(Path trustRoot, Path directory, String prefix, ObjectNode files,
                                  List<String> redacted, CaptureBudget budget) throws IOException {
        ensureNoSymlink(trustRoot, directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) {
            return;
        }
        final List<Path> candidates;
        try (Stream<Path> stream = Files.list(directory)) {
            candidates = stream
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> JSON_FILE.matcher(path.getFileName().toString()).matches())
                    .filter(path -> !FORBIDDEN_FILE_NAMES.contains(
                            path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .collect(Collectors.toList());
        }
        for (Path candidate : candidates) {
            captureFile(trustRoot, candidate,
                    prefix + candidate.getFileName(), files, redacted, budget);
        }
    }

    private void captureFile(Path trustRoot, Path file, String bundlePath, ObjectNode files,
                             List<String> redacted, CaptureBudget budget) throws IOException {
        ensureNoSymlink(trustRoot, file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            return;
        }
        validateBundlePath(bundlePath);
        byte[] raw = readBoundedFile(trustRoot, file, budget.remainingBytes());
        budget.record(raw.length);
        validateJsonDepth(raw, file.toString());
        JsonNode parsed = MAPPER.readTree(raw);
        if (parsed == null || !parsed.isObject()) {
            throw new IOException("Settings file must contain a JSON object: " + file);
        }
        files.set(bundlePath, sanitize(parsed, bundlePath, redacted));
    }

    private static JsonNode sanitize(JsonNode node, String path, List<String> redacted) {
        if (containsCredentialText(node)) {
            redacted.add(path);
            return null;
        }
        if (node.isObject()) {
            ObjectNode result = MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String childPath = path + "." + field.getKey();
                if (isSensitiveField(field.getKey()) && !isSafeSecretPlaceholder(field.getValue())) {
                    redacted.add(childPath);
                    continue;
                }
                JsonNode sanitized = sanitize(field.getValue(), childPath, redacted);
                if (sanitized != null) {
                    result.set(field.getKey(), sanitized);
                }
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            for (int i = 0; i < node.size(); i++) {
                JsonNode sanitized = sanitize(node.get(i), path + "[" + i + "]", redacted);
                if (sanitized != null) {
                    result.add(sanitized);
                }
            }
            return result;
        }
        return node.deepCopy();
    }

    private static byte[] readExistingBytes(ResolvedTarget resolved) throws IOException {
        ensureNoSymlink(resolved.trustRoot, resolved.target);
        if (!Files.exists(resolved.target, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!Files.isRegularFile(resolved.target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Settings target is not a regular file: " + resolved.target);
        }
        return readBoundedFile(resolved.trustRoot,
                resolved.target, MAX_LOCAL_FILE_BYTES);
    }

    private static ObjectNode parseExistingObject(Path target, byte[] bytes) throws IOException {
        if (bytes == null) {
            return null;
        }
        validateJsonDepth(bytes, target.toString());
        JsonNode node = MAPPER.readTree(bytes);
        if (node == null || !node.isObject()) {
            throw new IOException("Existing settings file is not a JSON object: " + target);
        }
        return (ObjectNode) node;
    }

    private static ObjectNode deepMerge(ObjectNode target, ObjectNode incoming) {
        Iterator<Map.Entry<String, JsonNode>> fields = incoming.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode existing = target.get(field.getKey());
            if (existing != null && existing.isObject() && field.getValue().isObject()) {
                deepMerge((ObjectNode) existing, (ObjectNode) field.getValue());
            } else {
                target.set(field.getKey(), field.getValue().deepCopy());
            }
        }
        return target;
    }

    private static void preserveSensitiveFields(ObjectNode existing, ObjectNode output, String path) {
        Iterator<Map.Entry<String, JsonNode>> fields = existing.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String childPath = path + "." + field.getKey();
            if (isSensitiveField(field.getKey()) || containsCredentialText(field.getValue())) {
                output.set(field.getKey(), field.getValue().deepCopy());
                continue;
            }
            if (!containsSensitiveMaterial(field.getValue())) {
                continue;
            }
            JsonNode outputChild = output.get(field.getKey());
            if (field.getValue().isArray()) {
                // Array indexes are not stable merge keys; preserve the complete local array when
                // any element contains credentials rather than risk moving or deleting a secret.
                output.set(field.getKey(), field.getValue().deepCopy());
            } else if (field.getValue().isObject()) {
                if (outputChild == null) {
                    outputChild = output.putObject(field.getKey());
                } else if (!outputChild.isObject()) {
                    throw new IllegalArgumentException(
                            "Cloud settings conflict with local secret parent: " + childPath);
                }
                preserveSensitiveFields((ObjectNode) field.getValue(),
                        (ObjectNode) outputChild, childPath);
            }
        }
    }

    private static boolean containsSensitiveMaterial(JsonNode node) {
        if (containsCredentialText(node)) {
            return true;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSensitiveField(field.getKey())
                        || containsSensitiveMaterial(field.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsSensitiveMaterial(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private ResolvedTarget resolveTarget(String bundlePath, Path projectRoot) {
        if (bundlePath.startsWith("user/config/")) {
            return target(userHome, userHome.resolve("config"),
                    bundlePath.substring("user/config/".length()));
        }
        if (bundlePath.startsWith("user/")) {
            return target(userHome, userHome, bundlePath.substring("user/".length()));
        }
        Path root = requireProjectRoot(projectRoot);
        if (bundlePath.startsWith("project/config/")) {
            return target(root, root.resolve("config"),
                    bundlePath.substring("project/config/".length()));
        }
        if (bundlePath.startsWith("project/.kompile/config/")) {
            return target(root, root.resolve(".kompile").resolve("config"),
                    bundlePath.substring("project/.kompile/config/".length()));
        }
        if (bundlePath.startsWith("project/.kompile/")) {
            return target(root, root.resolve(".kompile"),
                    bundlePath.substring("project/.kompile/".length()));
        }
        throw new IllegalArgumentException("Unknown settings file scope: " + bundlePath);
    }

    private static ResolvedTarget target(Path trustRoot, Path destinationRoot, String relative) {
        Path normalizedTrustRoot = trustRoot.toAbsolutePath().normalize();
        Path normalizedDestinationRoot = destinationRoot.toAbsolutePath().normalize();
        Path normalizedTarget = normalizedDestinationRoot.resolve(relative).normalize();
        if (!normalizedDestinationRoot.startsWith(normalizedTrustRoot)
                || !normalizedTarget.startsWith(normalizedDestinationRoot)) {
            throw new IllegalArgumentException("Settings path escapes its scope: " + relative);
        }
        return new ResolvedTarget(normalizedTrustRoot, normalizedTarget);
    }

    private static Path requireProjectRoot(Path projectRoot) {
        if (projectRoot == null) {
            throw new IllegalArgumentException("A project directory is required for project settings");
        }
        return projectRoot.toAbsolutePath().normalize();
    }

    private static void atomicWrite(Path root, Path target, byte[] bytes) throws IOException {
        atomicWrite(root, target, bytes, null, false);
    }

    private static void atomicWriteIfUnchanged(PreparedWrite write) throws IOException {
        String expectedHash = write.originalBytes == null
                ? null : sha256(write.originalBytes);
        atomicWrite(write.target.trustRoot, write.target.target,
                write.bytes, expectedHash, true);
    }

    private static void atomicWrite(Path root, Path target, byte[] bytes,
                                    String expectedHash, boolean verifyOriginal)
            throws IOException {
        ensureNoSymlink(root, target);
        Files.createDirectories(target.getParent());
        ensureNoSymlink(root, target);

        Set<PosixFilePermission> existingPermissions = null;
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            try {
                existingPermissions = Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS);
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem.
            }
        }

        Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".tmp");
        try {
            Files.write(temporary, bytes);
            if (existingPermissions != null) {
                try {
                    Files.setPosixFilePermissions(temporary, existingPermissions);
                } catch (UnsupportedOperationException ignored) {
                    // Non-POSIX filesystem.
                }
            }
            ensureNoSymlink(root, target);
            if (verifyOriginal) {
                String actualHash = hashCurrentFile(new ResolvedTarget(root, target));
                if (!Objects.equals(expectedHash, actualHash)) {
                    throw new IOException(
                            "Settings changed locally before cloud apply: " + target);
                }
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void rollback(List<PreparedWrite> attempted, Throwable failure) {
        for (int i = attempted.size() - 1; i >= 0; i--) {
            PreparedWrite write = attempted.get(i);
            try {
                String currentHash = hashCurrentFile(write.target);
                String originalHash = write.originalBytes == null
                        ? null : sha256(write.originalBytes);
                String writtenHash = sha256(write.bytes);
                if (Objects.equals(currentHash, originalHash)) {
                    continue;
                }
                boolean safeToRestore = Objects.equals(currentHash, writtenHash)
                        || (!write.applied && currentHash == null && write.originalBytes != null);
                if (!safeToRestore) {
                    failure.addSuppressed(new IOException(
                            "Skipped rollback because settings changed concurrently: "
                                    + write.target.target));
                    continue;
                }
                if (write.originalBytes == null) {
                    ensureNoSymlink(write.target.trustRoot, write.target.target);
                    Files.deleteIfExists(write.target.target);
                } else {
                    atomicWrite(write.target.trustRoot,
                            write.target.target, write.originalBytes);
                }
            } catch (IOException | RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
    }

    private static byte[] readBoundedFile(Path trustRoot, Path file, int maximum)
            throws IOException {
        ensureNoSymlink(trustRoot, file);
        if (maximum < 0) {
            throw new IOException("Settings read budget is exhausted");
        }
        try (InputStream input = Files.newInputStream(file,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8192))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) {
                    continue;
                }
                total += count;
                if (total > maximum) {
                    throw new IOException("Settings file exceeds the remaining "
                            + maximum + " byte budget: " + file);
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static void validateJsonDepth(byte[] json, String source) throws IOException {
        try (JsonParser parser = MAPPER.getFactory().createParser(json)) {
            int depth = 0;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                    if (++depth > MAX_DEPTH) {
                        throw new IOException("Settings nesting exceeds " + MAX_DEPTH
                                + " in " + source);
                    }
                } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                    depth--;
                }
            }
        }
    }

    private static String hashCurrentFile(ResolvedTarget resolved) throws IOException {
        ensureNoSymlink(resolved.trustRoot, resolved.target);
        if (!Files.exists(resolved.target, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!Files.isRegularFile(resolved.target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Settings target is not a regular file: " + resolved.target);
        }
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(resolved.target,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
        }
        return hex(digest.digest());
    }

    private static String sha256(byte[] bytes) {
        MessageDigest digest = sha256Digest();
        return hex(digest.digest(bytes));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private static void ensureNoSymlink(Path root, Path target) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedRoot)) {
            throw new IOException("Settings target escapes its scope: " + target);
        }
        Path current = normalizedRoot;
        if (Files.isSymbolicLink(current)) {
            throw new IOException("Settings root may not be a symbolic link: " + current);
        }
        for (Path segment : normalizedRoot.relativize(normalizedTarget)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Settings path may not contain symbolic links: " + current);
            }
        }
    }

    private static void validateBundlePath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\")
                || path.contains("..") || path.contains("//")) {
            throw new IllegalArgumentException("Unsafe settings file path: " + path);
        }
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        if (!JSON_FILE.matcher(fileName).matches()
                || FORBIDDEN_FILE_NAMES.contains(fileName.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Settings file is not allowed: " + path);
        }
        if (path.startsWith("user/config/")) {
            requireSingleFile(path, "user/config/");
            return;
        }
        if (path.startsWith("user/")) {
            requireSingleFile(path, "user/");
            if (!USER_TOP_LEVEL_FILES.contains(fileName)) {
                throw new IllegalArgumentException("User settings file is not portable: " + path);
            }
            return;
        }
        if (path.startsWith("project/config/")) {
            requireSingleFile(path, "project/config/");
            return;
        }
        if (path.startsWith("project/.kompile/config/")) {
            requireSingleFile(path, "project/.kompile/config/");
            return;
        }
        if (path.startsWith("project/.kompile/")) {
            requireSingleFile(path, "project/.kompile/");
            if (!PROJECT_DOT_KOMPILE_FILES.contains(fileName)) {
                throw new IllegalArgumentException("Project settings file is not portable: " + path);
            }
            return;
        }
        throw new IllegalArgumentException("Unknown settings file scope: " + path);
    }

    private static void requireSingleFile(String path, String prefix) {
        if (path.substring(prefix.length()).contains("/")) {
            throw new IllegalArgumentException("Nested settings paths are not allowed: " + path);
        }
    }

    private static void validateDepth(JsonNode node, int depth, String path) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Settings nesting exceeds " + MAX_DEPTH + " at " + path);
        }
        if (node.isContainerNode()) {
            Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) {
                validateDepth(children.next(), depth + 1, path);
            }
        }
    }

    private static void validateNoPlaintextSecrets(JsonNode node, String path) {
        if (containsCredentialText(node)) {
            throw new IllegalArgumentException(
                    "Credential-bearing text cannot be stored in cloud settings: " + path);
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String childPath = path + "." + field.getKey();
                if (isSensitiveField(field.getKey()) && !isSafeSecretPlaceholder(field.getValue())) {
                    throw new IllegalArgumentException(
                            "Plaintext secret values cannot be stored in cloud settings: " + childPath);
                }
                validateNoPlaintextSecrets(field.getValue(), childPath);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                validateNoPlaintextSecrets(node.get(i), path + "[" + i + "]");
            }
        }
    }

    static boolean isSensitiveField(String fieldName) {
        String key = fieldName == null ? "" : fieldName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        return key.endsWith("password")
                || key.endsWith("passwd")
                || key.equals("pwd")
                || key.equals("secret")
                || key.endsWith("secret")
                || key.endsWith("secretkey")
                || key.equals("apikey")
                || key.endsWith("apikey")
                || key.equals("token")
                || key.endsWith("token")
                || key.equals("cookie")
                || key.endsWith("cookie")
                || key.equals("setcookie")
                || key.equals("credential")
                || key.endsWith("credential")
                || key.endsWith("credentials")
                || key.equals("credentialsjson")
                || key.equals("privatekey")
                || key.endsWith("privatekey")
                || key.equals("accesskey")
                || key.endsWith("accesskey")
                || key.equals("signingkey")
                || key.equals("encryptionkey")
                || key.equals("auth")
                || key.equals("authentication")
                || key.equals("authorization")
                || key.equals("proxyauthorization")
                || key.endsWith("authorizationheader")
                || key.endsWith("authheader")
                || key.endsWith("connectionstring")
                || key.equals("dsn")
                || key.endsWith("webhookurl");
    }

    private static boolean containsCredentialText(JsonNode value) {
        if (value == null || !value.isTextual()) {
            return false;
        }
        return containsCredentialText(value.asText());
    }

    private static boolean containsCredentialText(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty() || ENV_REFERENCE.matcher(text).matches()) {
            return false;
        }
        return text.contains("-----BEGIN PRIVATE KEY-----")
                || text.contains("-----BEGIN RSA PRIVATE KEY-----")
                || text.contains("-----BEGIN EC PRIVATE KEY-----")
                || URI_USER_INFO.matcher(text).matches()
                || ORACLE_JDBC_CREDENTIAL.matcher(text).matches()
                || CREDENTIAL_ASSIGNMENT.matcher(text).find()
                || AUTHORIZATION_HEADER.matcher(text).find()
                || AUTHORIZATION_VALUE.matcher(text).matches();
    }

    public static void validateDescription(String description) {
        if (description == null) {
            return;
        }
        if (description.length() > 500) {
            throw new IllegalArgumentException("description must be at most 500 characters");
        }
        for (int i = 0; i < description.length(); i++) {
            if (Character.isISOControl(description.charAt(i))) {
                throw new IllegalArgumentException(
                        "description must not contain terminal control characters");
            }
        }
        if (containsCredentialText(description)) {
            throw new IllegalArgumentException("description must not contain credentials");
        }
    }

    private static boolean isSafeSecretPlaceholder(JsonNode value) {
        if (value == null || value.isNull()) {
            return true;
        }
        if (!value.isTextual()) {
            return false;
        }
        String text = value.asText().trim();
        return text.isEmpty()
                || text.equalsIgnoreCase("none")
                || text.equalsIgnoreCase("unset")
                || text.equalsIgnoreCase("not-configured")
                || text.equals("***")
                || ENV_REFERENCE.matcher(text).matches();
    }

    public enum Scope {
        USER,
        PROJECT,
        ALL;

        public static Scope parse(String value) {
            try {
                return Scope.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("scope must be user, project, or all");
            }
        }

        boolean includesUser() {
            return this == USER || this == ALL;
        }

        boolean includesProject() {
            return this == PROJECT || this == ALL;
        }
    }

    public static final class CaptureResult {
        private final ObjectNode bundle;
        private final List<String> files;
        private final List<String> redactedPaths;

        CaptureResult(ObjectNode bundle, List<String> files, List<String> redactedPaths) {
            this.bundle = bundle;
            this.files = List.copyOf(files);
            this.redactedPaths = List.copyOf(redactedPaths);
        }

        public ObjectNode getBundle() {
            return bundle.deepCopy();
        }

        public List<String> getFiles() {
            return files;
        }

        public List<String> getRedactedPaths() {
            return redactedPaths;
        }
    }

    public static final class ApplyResult {
        private final List<String> created;
        private final List<String> updated;

        ApplyResult(List<String> created, List<String> updated) {
            this.created = List.copyOf(created);
            this.updated = List.copyOf(updated);
        }

        public List<String> getCreated() {
            return created;
        }

        public List<String> getUpdated() {
            return updated;
        }

        public int totalProcessed() {
            return created.size() + updated.size();
        }
    }

    private static final class SettingsLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private SettingsLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }

    @FunctionalInterface
    interface BeforeWrite {
        void run(Path target) throws IOException;
    }

    private static final class ResolvedTarget {
        final Path trustRoot;
        final Path target;

        ResolvedTarget(Path trustRoot, Path target) {
            this.trustRoot = trustRoot;
            this.target = target;
        }
    }

    private static final class CaptureBudget {
        private int files;
        private int bytes;

        int remainingBytes() throws IOException {
            if (files >= MAX_FILES) {
                throw new IOException("Settings capture exceeds the " + MAX_FILES + " file limit");
            }
            return MAX_PAYLOAD_BYTES - bytes;
        }

        void record(int fileBytes) throws IOException {
            files++;
            bytes += fileBytes;
            if (files > MAX_FILES || bytes > MAX_PAYLOAD_BYTES) {
                throw new IOException("Settings capture exceeds the configured bundle limits");
            }
        }
    }

    private static final class PreparedWrite {
        final String bundlePath;
        final ResolvedTarget target;
        final byte[] bytes;
        final byte[] originalBytes;
        boolean applied;

        PreparedWrite(String bundlePath, ResolvedTarget target, byte[] bytes,
                      byte[] originalBytes) {
            this.bundlePath = bundlePath;
            this.target = target;
            this.bytes = bytes;
            this.originalBytes = originalBytes;
        }

        boolean existed() {
            return originalBytes != null;
        }
    }
}
