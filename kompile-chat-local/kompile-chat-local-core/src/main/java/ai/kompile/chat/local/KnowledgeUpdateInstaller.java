package ai.kompile.chat.local;

import ai.kompile.graph.reasoning.unified.MiniJson;
import ai.kompile.project.knowledge.KnowledgeUpdateManifest;
import ai.kompile.project.knowledge.PortableKnowledge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Verifies and installs a revision-aware portable knowledge delta without touching the SDX model.
 *
 * <p>The update archive contains one manifest and only changed payloads. Unchanged final-inventory
 * files are verified and copied from the active knowledge root into a new immutable revision root.
 * The caller validates the returned graph runtime and atomically publishes the new paths.</p>
 */
public final class KnowledgeUpdateInstaller {
    public static final String MANIFEST_ENTRY = "knowledge-manifest.json";
    public static final String PAYLOAD_ROOT = "knowledge/";
    public static final long MAX_ARCHIVE_BYTES = 4L * 1024 * 1024 * 1024;
    public static final int MAX_MANIFEST_BYTES = 8 * 1024 * 1024;
    public static final int MAX_ENTRIES = 100_000;

    private KnowledgeUpdateInstaller() {
    }

    public static InstalledKnowledgeUpdate install(
            Path archive,
            String expectedProjectId,
            String expectedBaseRevision,
            Path currentKnowledgeRoot,
            Path installationsRoot) throws IOException {
        Path archivePath = requireRegularFile(archive, "Knowledge update archive");
        if (Files.size(archivePath) > MAX_ARCHIVE_BYTES) {
            throw new IOException("Knowledge update archive exceeds the mobile size limit");
        }
        String projectId = PortableKnowledge.requireIdentity(expectedProjectId, "expectedProjectId");
        String baseRevision = PortableKnowledge.requireRevision(expectedBaseRevision, "expectedBaseRevision");
        Path currentRoot = requireDirectory(currentKnowledgeRoot, "Current knowledge root");
        Path outputRoot = requireDirectoryOrCreate(installationsRoot, "Knowledge update installation root");

        try (ZipFile zip = new ZipFile(archivePath.toFile())) {
            Map<String, ZipEntry> zipEntries = indexZip(zip);
            ZipEntry rawManifest = zipEntries.get(MANIFEST_ENTRY);
            if (rawManifest == null || rawManifest.isDirectory()) {
                throw new IOException("Knowledge update is missing " + MANIFEST_ENTRY);
            }
            KnowledgeUpdateManifest manifest = parseManifest(readBounded(
                    zip.getInputStream(rawManifest), MAX_MANIFEST_BYTES, MANIFEST_ENTRY));
            if (!projectId.equals(manifest.projectId())) {
                throw new IOException("Knowledge update belongs to project " + manifest.projectId()
                        + ", not " + projectId);
            }
            if (!baseRevision.equals(manifest.baseRevision())) {
                throw new IOException("Knowledge update base revision " + manifest.baseRevision()
                        + " does not match active revision " + baseRevision);
            }
            validateZipInventory(zipEntries, manifest);
            return installValidated(zip, zipEntries, manifest, currentRoot, outputRoot);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid knowledge update manifest: " + invalid.getMessage(), invalid);
        }
    }

    private static InstalledKnowledgeUpdate installValidated(
            ZipFile zip,
            Map<String, ZipEntry> zipEntries,
            KnowledgeUpdateManifest manifest,
            Path currentRoot,
            Path outputRoot) throws IOException {
        Map<String, PortableKnowledge.Entry> changed = new HashMap<>();
        for (PortableKnowledge.Entry entry : manifest.changed()) {
            changed.put(entry.path(), entry);
        }

        String prefix = safeSegment(manifest.projectId()) + "-" + manifest.revision().substring(0, 12);
        Path pending = outputRoot.resolve("." + prefix + "-" + UUID.randomUUID() + ".pending");
        Path activated = outputRoot.resolve(prefix + "-" + UUID.randomUUID());
        boolean moved = false;
        try {
            Files.createDirectory(pending);
            for (PortableKnowledge.Entry entry : manifest.inventory()) {
                Path target = resolveContained(pending, entry.path());
                Files.createDirectories(target.getParent());
                PortableKnowledge.Entry delta = changed.get(entry.path());
                if (delta != null) {
                    ZipEntry payload = zipEntries.get(PAYLOAD_ROOT + entry.path());
                    Files.createFile(target);
                    ProjectBundleResolver.streamVerified(
                            zip,
                            payload,
                            new ProjectBundleResolver.InventoryEntry(
                                    entry.path(), entry.size(), entry.sha256()),
                            target);
                } else {
                    copyVerifiedCurrent(currentRoot, entry, target);
                }
            }

            Path graph = resolveContained(pending, manifest.defaultGraph());
            if (!Files.isRegularFile(graph, LinkOption.NOFOLLOW_LINKS)
                    || !manifest.defaultGraph().toLowerCase(Locale.ROOT).endsWith(".kgraph")) {
                throw new IOException("Knowledge update default graph is missing or not a .kgraph");
            }
            Path descriptor = resolveContained(pending, PortableKnowledge.PROJECT_DESCRIPTOR);
            if (!Files.isRegularFile(descriptor, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Knowledge update is missing the project descriptor");
            }

            moveAtomically(pending, activated);
            moved = true;
            List<Path> sources = manifest.inventory().stream()
                    .map(PortableKnowledge.Entry::path)
                    .filter(path -> path.startsWith(PortableKnowledge.MARKDOWN_ROOT))
                    .map(path -> activated.resolve(path).normalize())
                    .toList();
            return new InstalledKnowledgeUpdate(
                    activated,
                    activated.resolve(manifest.defaultGraph()).normalize(),
                    activated.resolve("data/markdown"),
                    sources,
                    manifest.projectId(),
                    manifest.projectName(),
                    manifest.baseRevision(),
                    manifest.revision(),
                    manifest.defaultGraph());
        } catch (IOException | RuntimeException failure) {
            ProjectArchiveInstaller.deleteTree(moved ? activated : pending);
            throw failure;
        }
    }

    private static void copyVerifiedCurrent(
            Path currentRoot,
            PortableKnowledge.Entry entry,
            Path target) throws IOException {
        Path source = resolveContained(currentRoot, entry.path());
        if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Active knowledge is missing unchanged entry: " + entry.path());
        }
        if (Files.size(source) != entry.size()) {
            throw new IOException("Active knowledge size mismatch for unchanged entry: " + entry.path());
        }
        try (InputStream input = Files.newInputStream(source)) {
            if (!entry.sha256().equals(PortableKnowledge.sha256(input))) {
                throw new IOException("Active knowledge checksum mismatch for unchanged entry: " + entry.path());
            }
        }
        Files.createFile(target);
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        if (Files.size(target) != entry.size()) {
            throw new IOException("Copied knowledge size mismatch for: " + entry.path());
        }
    }

    private static Map<String, ZipEntry> indexZip(ZipFile zip) throws IOException {
        Map<String, ZipEntry> entries = new LinkedHashMap<>();
        Set<String> caseKeys = new HashSet<>();
        Enumeration<? extends ZipEntry> enumeration = zip.entries();
        int count = 0;
        while (enumeration.hasMoreElements()) {
            ZipEntry entry = enumeration.nextElement();
            if (++count > MAX_ENTRIES) {
                throw new IOException("Knowledge update contains too many ZIP entries");
            }
            String name = entry.getName();
            validateZipPath(name);
            if (entries.putIfAbsent(name, entry) != null
                    || !caseKeys.add(name.toLowerCase(Locale.ROOT))) {
                throw new IOException("Knowledge update has a duplicate or case-colliding ZIP entry: " + name);
            }
        }
        return entries;
    }

    private static void validateZipInventory(
            Map<String, ZipEntry> zipEntries,
            KnowledgeUpdateManifest manifest) throws IOException {
        Set<String> expected = new HashSet<>();
        expected.add(MANIFEST_ENTRY);
        for (PortableKnowledge.Entry entry : manifest.changed()) {
            String zipPath = PAYLOAD_ROOT + entry.path();
            expected.add(zipPath);
            ZipEntry payload = zipEntries.get(zipPath);
            if (payload == null || payload.isDirectory()) {
                throw new IOException("Knowledge update is missing changed payload: " + entry.path());
            }
            if (payload.getSize() >= 0 && payload.getSize() != entry.size()) {
                throw new IOException("Knowledge update declared size mismatch for: " + entry.path());
            }
        }
        for (Map.Entry<String, ZipEntry> entry : zipEntries.entrySet()) {
            if (entry.getValue().isDirectory()) {
                continue;
            }
            if (!expected.contains(entry.getKey())) {
                throw new IOException("Knowledge update contains an unlisted payload: " + entry.getKey());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static KnowledgeUpdateManifest parseManifest(byte[] bytes) throws IOException {
        final Map<String, Object> root;
        try {
            root = MiniJson.parseObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid knowledge update JSON: " + invalid.getMessage(), invalid);
        }

        List<PortableKnowledge.Entry> inventory = parseEntries(root.get("inventory"), "inventory");
        List<PortableKnowledge.Entry> changed = parseEntries(root.get("changed"), "changed");
        List<String> deleted = new ArrayList<>();
        Object rawDeleted = root.get("deleted");
        if (rawDeleted != null) {
            if (!(rawDeleted instanceof List<?> values)) {
                throw new IOException("deleted must be an array");
            }
            for (Object value : values) {
                deleted.add(requiredString(value, "deleted[]"));
            }
        }

        return new KnowledgeUpdateManifest(
                requiredString(root.get("format"), "format"),
                requiredInt(root.get("formatVersion"), "formatVersion"),
                requiredString(root.get("projectId"), "projectId"),
                requiredString(root.get("projectName"), "projectName"),
                requiredString(root.get("baseRevision"), "baseRevision"),
                requiredString(root.get("revision"), "revision"),
                requiredString(root.get("defaultGraph"), "defaultGraph"),
                requiredString(root.get("createdAt"), "createdAt"),
                requiredString(root.get("generator"), "generator"),
                inventory,
                changed,
                deleted);
    }

    private static List<PortableKnowledge.Entry> parseEntries(Object raw, String field) throws IOException {
        if (!(raw instanceof List<?> values)) {
            throw new IOException(field + " must be an array");
        }
        List<PortableKnowledge.Entry> result = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new IOException(field + " entries must be objects");
            }
            result.add(new PortableKnowledge.Entry(
                    requiredString(map.get("path"), field + ".path"),
                    requiredLong(map.get("size"), field + ".size"),
                    requiredString(map.get("sha256"), field + ".sha256")));
        }
        return result;
    }

    private static byte[] readBounded(InputStream input, int limit, String label) throws IOException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 64 * 1024))) {
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > limit) {
                    throw new IOException(label + " exceeds the size limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String requiredString(Object value, String field) throws IOException {
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IOException(field + " must be a non-empty string");
        }
        return string;
    }

    private static int requiredInt(Object value, String field) throws IOException {
        long number = requiredLong(value, field);
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IOException(field + " is outside the integer range");
        }
        return (int) number;
    }

    private static long requiredLong(Object value, String field) throws IOException {
        if (!(value instanceof Number number)) {
            throw new IOException(field + " must be a number");
        }
        long result = number.longValue();
        if (number.doubleValue() != (double) result) {
            throw new IOException(field + " must be an integer");
        }
        return result;
    }

    private static void validateZipPath(String value) throws IOException {
        if (value == null || value.isBlank() || value.startsWith("/") || value.indexOf('\\') >= 0
                || value.indexOf('\0') >= 0 || value.indexOf(':') >= 0) {
            throw new IOException("Unsafe knowledge update ZIP path: " + value);
        }
        String path = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        if (path.isBlank()) {
            throw new IOException("Unsafe knowledge update ZIP path: " + value);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IOException("Unsafe knowledge update ZIP path: " + value);
            }
        }
    }

    private static Path requireRegularFile(Path path, String label) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " is not a regular file: " + normalized);
        }
        return normalized;
    }

    private static Path requireDirectory(Path path, String label) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)
                || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " is not a real directory: " + normalized);
        }
        return normalized;
    }

    private static Path requireDirectoryOrCreate(Path path, String label) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        Path normalized = path.toAbsolutePath().normalize();
        Files.createDirectories(normalized);
        return requireDirectory(normalized, label);
    }

    private static Path resolveContained(Path root, String relative) throws IOException {
        String path = PortableKnowledge.requirePortablePath(relative);
        Path target = root.resolve(path).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Knowledge payload escapes its root: " + relative);
        }
        return target;
    }

    private static String safeSegment(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank() || ".".equals(safe) || "..".equals(safe)) {
            return "project";
        }
        return safe.length() <= 80 ? safe : safe.substring(0, 80);
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target);
        }
    }

    public record InstalledKnowledgeUpdate(
            Path knowledgeRoot,
            Path graphPath,
            Path sourcesRoot,
            List<Path> sourcePaths,
            String projectId,
            String projectName,
            String baseRevision,
            String revision,
            String defaultGraph) {
        public InstalledKnowledgeUpdate {
            sourcePaths = List.copyOf(sourcePaths);
        }

        public void delete() throws IOException {
            ProjectArchiveInstaller.deleteTree(knowledgeRoot);
        }
    }
}
