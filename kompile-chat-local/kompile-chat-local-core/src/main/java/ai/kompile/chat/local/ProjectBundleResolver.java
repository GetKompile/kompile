package ai.kompile.chat.local;

import ai.kompile.graph.reasoning.unified.MiniJson;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Resolves the graph owned by a Kompile project directory or {@code .kproject} archive.
 * Archive payloads are never generally extracted: only the selected graph is streamed to
 * a temporary file after manifest, size, and SHA-256 validation.
 */
public final class ProjectBundleResolver {
    // Keep these limits aligned with ProjectArchiveImportOptions.defaults().
    static final int MAX_MANIFEST_BYTES = 16 * 1024 * 1024;
    static final int MAX_PROJECT_DESCRIPTOR_BYTES = 1024 * 1024;
    static final int MAX_ENTRIES = 100_000;
    static final long MAX_ENTRY_BYTES = 20L * 1024 * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 100L * 1024 * 1024 * 1024;

    private static final String PROJECT_DESCRIPTOR = "kompile.project.json";
    private static final String GLOBAL_GRAPH = "data/graph/global.kgraph";
    private static final String PROJECT_GRAPH = "data/graph/project.kgraph";
    private static final Set<OpenOption> SECURE_READ_OPTIONS =
            Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);

    private ProjectBundleResolver() {
    }

    public static ResolvedProject resolve(Path source, String factSheetId) throws IOException {
        if (source == null) {
            throw new IllegalArgumentException("Project path is required");
        }
        String normalizedFactSheetId = normalizeFactSheetId(factSheetId);
        Path absolute = source.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute)) {
            throw new IOException("Project path must not be a symbolic link: " + absolute);
        }
        if (Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            return resolveDirectory(absolute, normalizedFactSheetId);
        }
        if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Project path does not exist or is not a regular file: " + absolute);
        }
        if (!absolute.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".kproject")) {
            throw new IOException("Project file must use the .kproject extension: " + absolute);
        }
        return resolveArchive(absolute, normalizedFactSheetId);
    }

    private static ResolvedProject resolveDirectory(Path root, String factSheetId) throws IOException {
        try (SecureDirectoryStream<Path> secureRoot = openSecureDirectory(root)) {
            byte[] descriptor;
            try {
                descriptor = readSecureFile(secureRoot, PROJECT_DESCRIPTOR, MAX_PROJECT_DESCRIPTOR_BYTES);
            } catch (IOException e) {
                throw new IOException("Not a Kompile project directory; cannot securely read "
                        + root.resolve(PROJECT_DESCRIPTOR), e);
            }
            ProjectIdentity identity = directoryIdentity(descriptor, root.resolve(PROJECT_DESCRIPTOR));
            List<String> graphs = recursiveDirectoryGraphs(secureRoot);
            String selectedRelative;
            if (factSheetId != null) {
                selectedRelative = "data/graph/factsheet-" + factSheetId + ".kgraph";
                if (!graphs.contains(selectedRelative)) {
                    throw new IOException("Requested fact-sheet graph not found: "
                            + root.resolve(selectedRelative));
                }
            } else {
                selectedRelative = selectGraph(graphs, "project directory " + root);
            }

            Path temporary = Files.createTempFile("kompile-chat-project-", ".kgraph");
            boolean success = false;
            try {
                copySecureFile(secureRoot, selectedRelative, temporary, MAX_ENTRY_BYTES);
                success = true;
                return new ResolvedProject(temporary, identity.projectId(), identity.name(), true);
            } finally {
                if (!success) Files.deleteIfExists(temporary);
            }
        }
    }

    private static List<String> recursiveDirectoryGraphs(SecureDirectoryStream<Path> root)
            throws IOException {
        List<String> graphs = new ArrayList<>();
        collectDirectoryGraphs(root, "", graphs, new int[]{0});
        graphs.sort(String::compareTo);
        return graphs;
    }

    private static void collectDirectoryGraphs(SecureDirectoryStream<Path> directory, String prefix,
                                               List<String> graphs, int[] count) throws IOException {
        List<Path> names = new ArrayList<>();
        for (Path child : directory) names.add(child.getFileName());
        names.sort((left, right) -> left.toString().compareTo(right.toString()));
        for (Path name : names) {
            if (++count[0] > MAX_ENTRIES) {
                throw new IOException("Project directory exceeds entry limit of " + MAX_ENTRIES);
            }
            String relative = prefix.isEmpty() ? name.toString() : prefix + "/" + name;
            relative = relative.replace('\\', '/');
            validateRelativePath(relative, "project path");
            BasicFileAttributes attrs = secureAttributes(directory, name);
            if (attrs.isSymbolicLink()) {
                throw new IOException("Project directory contains a symbolic link: " + relative);
            }
            if (attrs.isDirectory()) {
                try (SecureDirectoryStream<Path> child =
                             directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                    collectDirectoryGraphs(child, relative, graphs, count);
                }
            } else if (attrs.isRegularFile() && relative.endsWith(".kgraph")) {
                graphs.add(relative);
            }
        }
    }

    private static ProjectIdentity directoryIdentity(byte[] descriptorBytes, Path descriptor)
            throws IOException {
        try {
            String json = new String(descriptorBytes, StandardCharsets.UTF_8);
            Map<String, Object> object = MiniJson.parseObject(json);
            if (requiredLong(object.get("schemaVersion"), "kompile.project.json schemaVersion") != 1) {
                throw new IOException("Unsupported kompile.project.json schemaVersion");
            }
            return new ProjectIdentity(
                    requiredString(object.get("projectId"), "kompile.project.json projectId"),
                    requiredString(object.get("name"), "kompile.project.json name"),
                    Collections.unmodifiableMap(new HashMap<>(object)));
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid " + descriptor + ": " + e.getMessage(), e);
        }
    }

    private static ResolvedProject resolveArchive(Path archive, String factSheetId) throws IOException {
        return withValidatedArchive(archive, validated -> {
            Manifest manifest = validated.manifest();
            String selected = factSheetId == null
                    ? selectArchiveDefault(manifest)
                    : "data/graph/factsheet-" + factSheetId + ".kgraph";
            InventoryEntry inventoryEntry = manifest.entries().get(selected);
            if (inventoryEntry == null) {
                throw new IOException("Selected graph is not present in manifest inventory: " + selected);
            }
            if (!selected.endsWith(".kgraph")) {
                throw new IOException("Selected archive asset is not a .kgraph entry: " + selected);
            }
            ZipEntry graphEntry = validated.zipEntries().get("project/" + selected);
            if (graphEntry == null || graphEntry.isDirectory()) {
                throw new IOException("Selected graph payload is missing from archive: project/" + selected);
            }

            Path temporary = Files.createTempFile("kompile-chat-project-", ".kgraph");
            boolean success = false;
            try {
                streamVerified(validated.zip(), graphEntry, inventoryEntry, temporary);
                success = true;
                ProjectIdentity identity = validated.identity();
                return new ResolvedProject(
                        temporary, identity.projectId(), identity.name(), true);
            } finally {
                if (!success) {
                    Files.deleteIfExists(temporary);
                }
            }
        });
    }

    static <T> T withValidatedArchive(Path archive, ValidatedArchiveOperation<T> operation)
            throws IOException {
        if (archive == null) {
            throw new IllegalArgumentException("Project archive path is required");
        }
        Path absolute = archive.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute)
                || !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Project archive does not exist or is not a regular file: " + absolute);
        }
        if (!absolute.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".kproject")) {
            throw new IOException("Project file must use the .kproject extension: " + absolute);
        }
        try (ZipFile zip = new ZipFile(absolute.toFile())) {
            Map<String, ZipEntry> zipEntries = validateZipEntries(zip);
            ZipEntry manifestEntry = zipEntries.get("manifest.json");
            if (manifestEntry == null || manifestEntry.isDirectory()) {
                throw new IOException("Archive is missing outer manifest.json");
            }
            byte[] manifestBytes;
            try (InputStream input = zip.getInputStream(manifestEntry)) {
                manifestBytes = readBounded(input, MAX_MANIFEST_BYTES, "manifest.json");
            }
            Manifest manifest = parseManifest(new String(manifestBytes, StandardCharsets.UTF_8));
            validateInventoryAgainstZip(manifest, zipEntries);
            ProjectIdentity identity = verifyArchiveIdentity(zip, manifest, zipEntries);
            return operation.run(new ValidatedArchive(
                    zip, zipEntries, manifest, identity, sha256Hex(sha256().digest(manifestBytes))));
        }
    }

    static String selectArchiveDefault(Manifest manifest) throws IOException {
        if (manifest.defaultGraph() != null) {
            validateRelativePath(manifest.defaultGraph(), "defaultGraph");
            if (!manifest.entries().containsKey(manifest.defaultGraph())) {
                throw new IOException("Archive defaultGraph is not present in manifest inventory: "
                        + manifest.defaultGraph());
            }
            return manifest.defaultGraph();
        }
        List<String> graphs = manifest.entries().keySet().stream()
                .filter(path -> path.endsWith(".kgraph")).toList();
        return selectGraph(graphs, "archive manifest");
    }

    private static String selectGraph(List<String> graphs, String location) throws IOException {
        if (graphs.contains(PROJECT_GRAPH)) {
            return PROJECT_GRAPH;
        }
        if (graphs.contains(GLOBAL_GRAPH)) {
            return GLOBAL_GRAPH;
        }
        if (graphs.isEmpty()) {
            throw new IOException("No .kgraph found in " + location
                    + "; add data/graph/global.kgraph, data/graph/project.kgraph, or exactly one .kgraph");
        }
        if (graphs.size() != 1) {
            throw new IOException("Ambiguous project graphs in " + location + ": " + graphs
                    + "; select a fact sheet or provide global.kgraph/project.kgraph");
        }
        return graphs.get(0);
    }

    private static Map<String, ZipEntry> validateZipEntries(ZipFile zip) throws IOException {
        Map<String, ZipEntry> entries = new HashMap<>();
        Set<String> folded = new HashSet<>();
        int count = 0;
        long totalSize = 0;
        Enumeration<? extends ZipEntry> enumeration = zip.entries();
        while (enumeration.hasMoreElements()) {
            ZipEntry entry = enumeration.nextElement();
            if (++count > MAX_ENTRIES + 1) {
                throw new IOException("Archive exceeds ZIP entry limit of " + (MAX_ENTRIES + 1));
            }
            String name = entry.getName();
            validateArchivePath(name, "ZIP entry");
            if (entry.isDirectory()) {
                throw new IOException("Directory entries are not allowed in .kproject archives: " + name);
            }
            long size = entry.getSize();
            if (!entry.isDirectory() && (size < 0 || size > MAX_ENTRY_BYTES)) {
                throw new IOException("ZIP entry size out of bounds for " + name + ": " + size);
            }
            if (!entry.isDirectory() && !"manifest.json".equals(name)) {
                if (totalSize > MAX_TOTAL_BYTES - size) {
                    throw new IOException("Archive exceeds uncompressed size limit of " + MAX_TOTAL_BYTES);
                }
                totalSize += size;
            }
            if (entries.putIfAbsent(name, entry) != null) {
                throw new IOException("Duplicate ZIP entry: " + name);
            }
            if (!folded.add(name.toLowerCase(Locale.ROOT))) {
                throw new IOException("Case-colliding ZIP entry: " + name);
            }
        }
        return entries;
    }

    private static Manifest parseManifest(String json) throws IOException {
        final Map<String, Object> root;
        try {
            root = MiniJson.parseObject(json);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid archive manifest JSON: " + e.getMessage(), e);
        }
        if (!"kompile-project".equals(root.get("format"))) {
            throw new IOException("Unsupported archive format: " + root.get("format"));
        }
        if (requiredLong(root.get("formatVersion"), "formatVersion") != 1) {
            throw new IOException("Unsupported archive formatVersion: " + root.get("formatVersion"));
        }
        Object rawEntries = root.get("entries");
        if (!(rawEntries instanceof List<?> list)) {
            throw new IOException("Archive manifest entries must be an array");
        }
        if (list.size() > MAX_ENTRIES) {
            throw new IOException("Archive manifest exceeds entry limit of " + MAX_ENTRIES);
        }

        Map<String, InventoryEntry> entries = new HashMap<>();
        Set<String> folded = new HashSet<>();
        long total = 0;
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> item)) {
                throw new IOException("Archive manifest entry must be an object");
            }
            String path = requiredString(item.get("path"), "entries[].path");
            validateRelativePath(path, "manifest entry");
            if (entries.containsKey(path)) {
                throw new IOException("Duplicate manifest entry: " + path);
            }
            if (!folded.add(path.toLowerCase(Locale.ROOT))) {
                throw new IOException("Case-colliding manifest entry: " + path);
            }
            long size = requiredLong(item.get("size"), "entries[].size");
            if (size < 0 || size > MAX_ENTRY_BYTES) {
                throw new IOException("Manifest entry size out of bounds for " + path + ": " + size);
            }
            if (total > MAX_TOTAL_BYTES - size) {
                throw new IOException("Archive manifest exceeds total declared size limit of "
                        + MAX_TOTAL_BYTES);
            }
            total += size;
            String sha256 = requiredString(item.get("sha256"), "entries[].sha256");
            if (!(item.get("executable") instanceof Boolean)) {
                throw new IOException("Archive manifest field entries[].executable must be boolean");
            }
            if (!sha256.matches("[0-9a-fA-F]{64}")) {
                throw new IOException("Invalid SHA-256 for manifest entry: " + path);
            }
            entries.put(path, new InventoryEntry(path, size, sha256.toLowerCase(Locale.ROOT)));
        }
        String defaultGraph = null;
        if (root.containsKey("defaultGraph") && root.get("defaultGraph") != null) {
            defaultGraph = requiredString(root.get("defaultGraph"), "defaultGraph");
        }
        return new Manifest(requiredString(root.get("projectId"), "projectId"),
                requiredString(root.get("name"), "name"), defaultGraph, entries);
    }

    private static void validateInventoryAgainstZip(Manifest manifest, Map<String, ZipEntry> zipEntries)
            throws IOException {
        Set<String> expectedZipEntries = new HashSet<>();
        expectedZipEntries.add("manifest.json");
        for (InventoryEntry item : manifest.entries().values()) {
            String zipName = "project/" + item.path();
            expectedZipEntries.add(zipName);
            ZipEntry entry = zipEntries.get(zipName);
            if (entry == null) {
                throw new IOException("Manifest payload is missing from archive: " + zipName);
            }
            long zipSize = entry.getSize();
            if (zipSize >= 0 && zipSize != item.size()) {
                throw new IOException("ZIP size does not match manifest for " + item.path());
            }
        }
        for (String zipName : zipEntries.keySet()) {
            if (!expectedZipEntries.contains(zipName)) {
                throw new IOException("Archive contains a payload not declared by manifest: " + zipName);
            }
        }
    }

    private static ProjectIdentity verifyArchiveIdentity(
            ZipFile zip, Manifest manifest, Map<String, ZipEntry> zipEntries) throws IOException {
        InventoryEntry descriptor = manifest.entries().get(PROJECT_DESCRIPTOR);
        if (descriptor == null) {
            throw new IOException("Archive manifest does not inventory " + PROJECT_DESCRIPTOR);
        }
        if (descriptor.size() > MAX_PROJECT_DESCRIPTOR_BYTES) {
            throw new IOException(PROJECT_DESCRIPTOR + " exceeds size limit of "
                    + MAX_PROJECT_DESCRIPTOR_BYTES + " bytes");
        }
        ZipEntry descriptorZip = zipEntries.get("project/" + PROJECT_DESCRIPTOR);
        byte[] bytes;
        try (InputStream input = zip.getInputStream(descriptorZip)) {
            bytes = readBounded(input, MAX_PROJECT_DESCRIPTOR_BYTES, PROJECT_DESCRIPTOR);
        }
        verifyBytes(descriptor, bytes);

        final Map<String, Object> project;
        try {
            project = MiniJson.parseObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid " + PROJECT_DESCRIPTOR + ": " + e.getMessage(), e);
        }
        if (requiredLong(project.get("schemaVersion"), "kompile.project.json schemaVersion") != 1) {
            throw new IOException("Unsupported kompile.project.json schemaVersion");
        }
        String projectId = requiredString(project.get("projectId"), "kompile.project.json projectId");
        String name = requiredString(project.get("name"), "kompile.project.json name");
        if (!manifest.projectId().equals(projectId)) {
            throw new IOException("Archive projectId does not match " + PROJECT_DESCRIPTOR);
        }
        if (!manifest.name().equals(name)) {
            throw new IOException("Archive project name does not match " + PROJECT_DESCRIPTOR);
        }
        return new ProjectIdentity(
                projectId, name, Collections.unmodifiableMap(new HashMap<>(project)));
    }

    static void streamVerified(ZipFile zip, ZipEntry entry, InventoryEntry expected, Path target)
            throws IOException {
        MessageDigest digest = sha256();
        long count = 0;
        try (InputStream input = zip.getInputStream(entry);
             OutputStream output = Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (count > expected.size() - read) {
                    throw new IOException("Archive payload exceeds declared size: " + expected.path());
                }
                count += read;
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        if (count != expected.size()) {
            throw new IOException("Archive payload size mismatch for " + expected.path()
                    + ": expected " + expected.size() + ", read " + count);
        }
        String actual = sha256Hex(digest.digest());
        if (!actual.equals(expected.sha256())) {
            throw new IOException("Archive payload SHA-256 mismatch for " + expected.path());
        }
    }

    private static void verifyBytes(InventoryEntry expected, byte[] bytes) throws IOException {
        if (bytes.length != expected.size()) {
            throw new IOException("Payload size mismatch for " + expected.path());
        }
        String actual = sha256Hex(sha256().digest(bytes));
        if (!actual.equals(expected.sha256())) {
            throw new IOException("Payload SHA-256 mismatch for " + expected.path());
        }
    }

    private static byte[] readBounded(InputStream input, int limit, String label) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (total > limit - read) {
                throw new IOException(label + " exceeds size limit of " + limit + " bytes");
            }
            total += read;
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static SecureDirectoryStream<Path> openSecureDirectory(Path root) throws IOException {
        Path parent = root.getParent();
        Path name = root.getFileName();
        if (parent == null || name == null) {
            throw new IOException("Project directory must have a parent: " + root);
        }
        try (DirectoryStream<Path> parentStream = Files.newDirectoryStream(parent)) {
            if (!(parentStream instanceof SecureDirectoryStream<?>)) {
                throw new IOException("Project filesystem does not support race-safe directory access");
            }
            @SuppressWarnings("unchecked")
            SecureDirectoryStream<Path> secureParent = (SecureDirectoryStream<Path>) parentStream;
            return secureParent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS);
        }
    }

    private static BasicFileAttributes secureAttributes(
            SecureDirectoryStream<Path> directory, Path name) throws IOException {
        BasicFileAttributeView view = directory.getFileAttributeView(
                name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) throw new IOException("Cannot securely inspect project path: " + name);
        return view.readAttributes();
    }

    private static byte[] readSecureFile(SecureDirectoryStream<Path> root, String relative, int limit)
            throws IOException {
        return withSecureFile(root, relative, channel -> {
            if (channel.size() > limit) {
                throw new IOException(relative + " exceeds size limit of " + limit + " bytes");
            }
            channel.position(0);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            long total = 0;
            int read;
            while ((read = channel.read(buffer)) >= 0) {
                if (read == 0) {
                    buffer.clear();
                    continue;
                }
                total += read;
                if (total > limit) {
                    throw new IOException(relative + " exceeds size limit of " + limit + " bytes");
                }
                output.write(buffer.array(), 0, read);
                buffer.clear();
            }
            return output.toByteArray();
        });
    }

    private static void copySecureFile(SecureDirectoryStream<Path> root, String relative,
                                       Path target, long limit) throws IOException {
        withSecureFile(root, relative, channel -> {
            channel.position(0);
            try (OutputStream output = Files.newOutputStream(target,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
                long total = 0;
                int read;
                while ((read = channel.read(buffer)) >= 0) {
                    if (read == 0) {
                        buffer.clear();
                        continue;
                    }
                    if (total > limit - read) {
                        throw new IOException(relative + " exceeds size limit of " + limit + " bytes");
                    }
                    total += read;
                    output.write(buffer.array(), 0, read);
                    buffer.clear();
                }
                if (channel.size() != total) {
                    throw new IOException("Project graph changed while being copied: " + relative);
                }
            }
            return null;
        });
    }

    private static <T> T withSecureFile(SecureDirectoryStream<Path> root, String relative,
                                        SecureFileOperation<T> operation) throws IOException {
        validateRelativePath(relative, "project path");
        String[] parts = relative.split("/");
        List<SecureDirectoryStream<Path>> opened = new ArrayList<>();
        SecureDirectoryStream<Path> current = root;
        try {
            for (int i = 0; i < parts.length - 1; i++) {
                SecureDirectoryStream<Path> child = current.newDirectoryStream(
                        Path.of(parts[i]), LinkOption.NOFOLLOW_LINKS);
                opened.add(child);
                current = child;
            }
            Path name = Path.of(parts[parts.length - 1]);
            BasicFileAttributes before = secureAttributes(current, name);
            if (!before.isRegularFile() || before.isSymbolicLink()) {
                throw new IOException("Project path is not a regular file or is a symbolic link: " + relative);
            }
            try (SeekableByteChannel channel = current.newByteChannel(name, SECURE_READ_OPTIONS)) {
                BasicFileAttributes openedAttributes = secureAttributes(current, name);
                requireSameSecureFile(relative, before, openedAttributes);
                T result = operation.run(channel);
                BasicFileAttributes finishedAttributes = secureAttributes(current, name);
                requireSameSecureFile(relative, openedAttributes, finishedAttributes);
                return result;
            }
        } finally {
            for (int i = opened.size() - 1; i >= 0; i--) opened.get(i).close();
        }
    }

    private static void requireSameSecureFile(
            String relative, BasicFileAttributes before, BasicFileAttributes after) throws IOException {
        if (!after.isRegularFile() || after.isSymbolicLink()
                || (before.fileKey() != null && after.fileKey() != null
                && !before.fileKey().equals(after.fileKey()))) {
            throw new IOException("Project path changed or became a symbolic link while loading: " + relative);
        }
    }

    private static void validateArchivePath(String path, String label) throws IOException {
        if (path == null || path.isEmpty() || path.indexOf('\\') >= 0 || path.startsWith("/")
                || path.matches("^[A-Za-z]:.*") || path.codePoints().anyMatch(Character::isISOControl)) {
            throw new IOException(label + " has unsafe path: " + path);
        }
        Path normalized = Path.of(path).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..") || !normalized.toString()
                .replace('\\', '/').equals(path.endsWith("/") ? path.substring(0, path.length() - 1) : path)) {
            throw new IOException(label + " has unsafe path: " + path);
        }
    }

    private static void validateRelativePath(String path, String label) throws IOException {
        validateArchivePath(path, label);
        if (path.endsWith("/")) {
            throw new IOException(label + " must name a file: " + path);
        }
        if (path.startsWith("project/")) {
            throw new IOException(label + " must be relative to project/: " + path);
        }
    }

    private static String normalizeFactSheetId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String trimmed = id.trim();
        if (!trimmed.matches("[A-Za-z0-9._-]+") || trimmed.equals(".") || trimmed.equals("..")) {
            throw new IllegalArgumentException("Invalid fact-sheet ID: " + id);
        }
        return trimmed;
    }

    private static String requiredString(Object value, String field) throws IOException {
        String result = optionalString(value);
        if (result == null) {
            throw new IOException("Archive manifest field " + field + " must be a non-empty string");
        }
        return result;
    }

    private static String optionalString(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static long requiredLong(Object value, String field) throws IOException {
        if (!(value instanceof Number number)) {
            throw new IOException("Archive manifest field " + field + " must be an integer");
        }
        long result = number.longValue();
        double exact = number.doubleValue();
        if (!Double.isFinite(exact) || exact != result) {
            throw new IOException("Archive manifest field " + field + " must be an integer");
        }
        return result;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK does not provide SHA-256", impossible);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] encoded = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            encoded[i * 2] = alphabet[value >>> 4];
            encoded[i * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(encoded);
    }

    @FunctionalInterface
    private interface SecureFileOperation<T> {
        T run(SeekableByteChannel channel) throws IOException;
    }

    @FunctionalInterface
    interface ValidatedArchiveOperation<T> {
        T run(ValidatedArchive archive) throws IOException;
    }

    record ProjectIdentity(String projectId, String name, Map<String, Object> descriptor) {}
    record InventoryEntry(String path, long size, String sha256) {}
    record Manifest(String projectId, String name, String defaultGraph,
                    Map<String, InventoryEntry> entries) {}
    record ValidatedArchive(ZipFile zip, Map<String, ZipEntry> zipEntries, Manifest manifest,
                            ProjectIdentity identity, String revision) {}

    public static final class ResolvedProject implements AutoCloseable {
        private final Path graphPath;
        private final String projectId;
        private final String projectName;
        private final boolean temporary;
        private boolean closed;

        private ResolvedProject(Path graphPath, String projectId, String projectName, boolean temporary) {
            this.graphPath = graphPath;
            this.projectId = projectId;
            this.projectName = projectName;
            this.temporary = temporary;
        }

        public Path graphPath() {
            return graphPath;
        }

        public String projectId() {
            return projectId;
        }

        public String projectName() {
            return projectName;
        }

        @Override
        public void close() throws IOException {
            if (!closed && temporary) {
                Files.deleteIfExists(graphPath);
            }
            closed = true;
        }
    }
}
