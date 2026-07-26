package ai.kompile.project.archive;

import ai.kompile.project.KompileProjectManifest;
import ai.kompile.project.KompileProjectStore;
import ai.kompile.project.knowledge.KnowledgeInventory;
import ai.kompile.project.knowledge.KnowledgeUpdateManifest;
import ai.kompile.project.knowledge.PortableKnowledge;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class ProjectArchiveService {
    public static final String FORMAT = "kompile-project";
    public static final int MIN_SUPPORTED_FORMAT_VERSION = 1;
    public static final int FORMAT_VERSION = 2;
    public static final String MANIFEST_ENTRY = "manifest.json";
    public static final String PAYLOAD_PREFIX = "project/";
    public static final String KNOWLEDGE_MANIFEST_ENTRY = "knowledge-manifest.json";
    public static final String KNOWLEDGE_PAYLOAD_PREFIX = "knowledge/";
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String COMPLETE_PROJECT_GRAPH = "data/graph/project.kgraph";
    private static final String GLOBAL_GRAPH = "data/graph/global.kgraph";
    private static final long MAX_SECRET_SCAN_BYTES = 4L * 1024 * 1024;
    private static final long MAX_PROJECT_DESCRIPTOR_BYTES = 1024L * 1024;
    private static final Set<OpenOption> SECURE_READ_OPTIONS =
            Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(?:^|[,{\\s])[\"']?([A-Za-z0-9_.-]*(?:password|passwd|client[_-]?secret|"
                    + "api[_-]?key|access[_-]?token|refresh[_-]?token|private[_-]?key|"
                    + "secret[_-]?key))[\"']?\\s*[:=]\\s*(\"[^\"]*\"|'[^']*'|[^,\\s#}]+)");

    private final ObjectMapper mapper;
    private final KompileProjectStore store;
    private final ExportHook exportHook;
    private final PublicationHook publicationHook;

    public ProjectArchiveService() {
        this(new KompileProjectStore(), relative -> { }, destination -> { });
    }

    public ProjectArchiveService(KompileProjectStore store) {
        this(store, relative -> { }, destination -> { });
    }

    ProjectArchiveService(KompileProjectStore store, ExportHook exportHook) {
        this(store, exportHook, destination -> { });
    }

    ProjectArchiveService(KompileProjectStore store, ExportHook exportHook,
                          PublicationHook publicationHook) {
        this.store = store;
        this.exportHook = Objects.requireNonNull(exportHook, "exportHook");
        this.publicationHook = Objects.requireNonNull(publicationHook, "publicationHook");
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public ProjectArchiveResult exportProject(Path root, Path output) throws IOException {
        return exportProject(root, output, ProjectArchiveExportOptions.defaults());
    }

    public ProjectArchiveResult exportProject(Path root, Path output,
                                               ProjectArchiveExportOptions options) throws IOException {
        ProjectArchiveExportOptions effectiveOptions = options == null
                ? ProjectArchiveExportOptions.defaults() : options;
        if (effectiveOptions.includeSensitiveFiles()) {
            throw new IOException(".kproject archives never include sensitive files; "
                    + "use a separate encrypted backup format");
        }
        Path projectRoot = root.toAbsolutePath().normalize();
        Path destination = output.toAbsolutePath().normalize();
        if (destination.getFileName() == null || !destination.getFileName().toString()
                .toLowerCase(Locale.ROOT).endsWith(".kproject")) {
            throw new IOException("Archive output must use the .kproject extension: " + destination);
        }
        if (!Files.isDirectory(projectRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Project root is not a directory: " + projectRoot);
        }
        if (destination.getParent() == null) {
            throw new IOException("Archive output must have a parent directory");
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Archive output already exists: " + destination);
        }
        Files.createDirectories(destination.getParent());

        Path temp = destination.resolveSibling("." + destination.getFileName() + ".tmp-" + UUID.randomUUID());
        Set<String> excludedPaths = excludedProjectPaths(projectRoot, destination, temp);
        ProjectArchiveManifest manifest;
        Snapshot first;
        try {
            try (OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(temp,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
                 ZipOutputStream zip = new ZipOutputStream(fileOut)) {
                first = captureSnapshot(projectRoot, excludedPaths, effectiveOptions, zip);
                manifest = new ProjectArchiveManifest(FORMAT, FORMAT_VERSION,
                        first.project().getProjectId(), first.project().getName(), Instant.now(), "kompile",
                        selectDefaultGraph(first.entries()),
                        semanticMetadata(first.project(), first.entries()), first.entries());
                byte[] manifestBytes = mapper.writeValueAsBytes(manifest);
                if (manifestBytes.length > ProjectArchiveImportOptions.defaults().maxManifestSize()) {
                    throw new IOException("Generated archive manifest exceeds supported import limit");
                }
                ZipEntry manifestEntry = new ZipEntry(MANIFEST_ENTRY);
                manifestEntry.setTime(0);
                zip.putNextEntry(manifestEntry);
                zip.write(manifestBytes);
                zip.closeEntry();
            }
            Snapshot after = captureSnapshot(projectRoot, excludedPaths, effectiveOptions, null);
            verifySnapshot(first, after);
            publicationHook.beforePublish(destination);
            movePublished(temp, destination);
            return new ProjectArchiveResult(destination, manifest, first.totalSize());
        } catch (Exception e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            if (e instanceof IOException io) throw io;
            throw new IOException("Failed to export project archive", e);
        }
    }

    /**
     * Captures the current descriptor, graph, Markdown, fact-sheet, and index inventory without
     * exporting model binaries or other desktop-only project state.
     */
    public KnowledgeInventory inspectKnowledge(Path root) throws IOException {
        Path projectRoot = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(projectRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Project root is not a directory: " + projectRoot);
        }
        Snapshot snapshot = captureSnapshot(
                projectRoot,
                Set.of(),
                new ProjectArchiveExportOptions(true, false),
                null,
                PortableKnowledge::isPortablePath,
                KNOWLEDGE_PAYLOAD_PREFIX);
        return knowledgeInventory(snapshot);
    }

    /**
     * Exports a content-addressed {@code .kupdate} delta from a client-supplied full base inventory.
     * The exporter uses the same race-safe file capture as canonical {@code .kproject} export and
     * repeats a full portable snapshot before publication.
     */
    public KnowledgeUpdateArchiveResult exportKnowledgeUpdate(
            Path root,
            Path output,
            KnowledgeInventory base) throws IOException {
        Objects.requireNonNull(base, "Base knowledge inventory is required");
        Path projectRoot = root.toAbsolutePath().normalize();
        Path destination = output.toAbsolutePath().normalize();
        if (destination.getFileName() == null
                || !destination.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".kupdate")) {
            throw new IOException("Knowledge update output must use the .kupdate extension: " + destination);
        }
        if (!Files.isDirectory(projectRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Project root is not a directory: " + projectRoot);
        }
        if (destination.getParent() == null) {
            throw new IOException("Knowledge update output must have a parent directory");
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Knowledge update output already exists: " + destination);
        }
        Files.createDirectories(destination.getParent());

        Path temp = destination.resolveSibling("." + destination.getFileName() + ".tmp-" + UUID.randomUUID());
        Set<String> excludedPaths = excludedProjectPaths(projectRoot, destination, temp);
        ProjectArchiveExportOptions options = new ProjectArchiveExportOptions(true, false);
        try {
            Snapshot first = captureSnapshot(
                    projectRoot,
                    excludedPaths,
                    options,
                    null,
                    PortableKnowledge::isPortablePath,
                    KNOWLEDGE_PAYLOAD_PREFIX);
            KnowledgeInventory current = knowledgeInventory(first);
            if (!base.projectId().equals(current.projectId())) {
                throw new IOException("Knowledge base projectId " + base.projectId()
                        + " does not match current project " + current.projectId());
            }

            Map<String, PortableKnowledge.Entry> baseEntries = new HashMap<>();
            base.entries().forEach(entry -> baseEntries.put(entry.path(), entry));
            List<PortableKnowledge.Entry> changed = new ArrayList<>();
            long changedBytes = 0;
            for (PortableKnowledge.Entry entry : current.entries()) {
                PortableKnowledge.Entry previous = baseEntries.get(entry.path());
                if (previous == null || previous.size() != entry.size()
                        || !previous.sha256().equals(entry.sha256())
                        || PortableKnowledge.PROJECT_DESCRIPTOR.equals(entry.path())) {
                    changed.add(entry);
                    changedBytes = Math.addExact(changedBytes, entry.size());
                }
            }
            Set<String> currentPaths = new HashSet<>();
            current.entries().forEach(entry -> currentPaths.add(entry.path()));
            List<String> deleted = base.entries().stream()
                    .map(PortableKnowledge.Entry::path)
                    .filter(path -> !currentPaths.contains(path))
                    .sorted()
                    .toList();

            KnowledgeUpdateManifest manifest = new KnowledgeUpdateManifest(
                    KnowledgeUpdateManifest.FORMAT,
                    PortableKnowledge.FORMAT_VERSION,
                    current.projectId(),
                    current.projectName(),
                    base.revision(),
                    current.revision(),
                    current.defaultGraph(),
                    Instant.now().toString(),
                    "kompile",
                    current.entries(),
                    changed,
                    deleted);
            Set<String> changedPaths = new HashSet<>();
            changed.forEach(entry -> changedPaths.add(entry.path()));

            Snapshot capturedChanged;
            try (OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(
                    temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
                 ZipOutputStream zip = new ZipOutputStream(fileOut)) {
                capturedChanged = captureSnapshot(
                        projectRoot,
                        excludedPaths,
                        options,
                        zip,
                        changedPaths::contains,
                        KNOWLEDGE_PAYLOAD_PREFIX);
                verifySelectedSnapshot(changed, capturedChanged.entries());
                byte[] manifestBytes = mapper.writeValueAsBytes(manifest);
                if (manifestBytes.length > ProjectArchiveImportOptions.defaults().maxManifestSize()) {
                    throw new IOException("Generated knowledge manifest exceeds supported import limit");
                }
                ZipEntry manifestEntry = new ZipEntry(KNOWLEDGE_MANIFEST_ENTRY);
                manifestEntry.setTime(0);
                zip.putNextEntry(manifestEntry);
                zip.write(manifestBytes);
                zip.closeEntry();
            }

            Snapshot after = captureSnapshot(
                    projectRoot,
                    excludedPaths,
                    options,
                    null,
                    PortableKnowledge::isPortablePath,
                    KNOWLEDGE_PAYLOAD_PREFIX);
            verifySnapshot(first, after);
            publicationHook.beforePublish(destination);
            movePublished(temp, destination);
            return new KnowledgeUpdateArchiveResult(destination, current, manifest, changedBytes);
        } catch (Exception e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            if (e instanceof IOException io) throw io;
            throw new IOException("Failed to export knowledge update", e);
        }
    }

    private KnowledgeInventory knowledgeInventory(Snapshot snapshot) throws IOException {
        List<PortableKnowledge.Entry> entries = snapshot.entries().stream()
                .map(entry -> new PortableKnowledge.Entry(entry.path(), entry.size(), entry.sha256()))
                .toList();
        String defaultGraph = selectDefaultGraph(snapshot.entries());
        if (defaultGraph == null || !PortableKnowledge.isPortablePath(defaultGraph)) {
            throw new IOException("Portable knowledge has no unambiguous default .kgraph");
        }
        return KnowledgeInventory.create(
                snapshot.project().getProjectId(),
                snapshot.project().getName(),
                defaultGraph,
                entries);
    }

    private static void verifySelectedSnapshot(
            List<PortableKnowledge.Entry> expected,
            List<ProjectArchiveManifest.Entry> actual) throws IOException {
        Map<String, ProjectArchiveManifest.Entry> byPath = new HashMap<>();
        actual.forEach(entry -> byPath.put(entry.path(), entry));
        if (byPath.size() != expected.size()) {
            throw new IOException("Selected knowledge payload inventory changed during export");
        }
        for (PortableKnowledge.Entry entry : expected) {
            ProjectArchiveManifest.Entry captured = byPath.get(entry.path());
            if (captured == null || captured.size() != entry.size()
                    || !captured.sha256().equals(entry.sha256())) {
                throw new IOException("Knowledge payload changed during export: " + entry.path());
            }
        }
    }

    public ProjectArchiveInspection inspectProject(Path archive) throws IOException {
        return inspectProject(archive, ProjectArchiveImportOptions.defaults());
    }

    /**
     * Performs a read-only structural preflight. Payload checksums are verified during import.
     */
    public ProjectArchiveInspection inspectProject(
            Path archive, ProjectArchiveImportOptions limits) throws IOException {
        ProjectArchiveImportOptions effectiveLimits = limits == null
                ? ProjectArchiveImportOptions.defaults() : limits;
        Path source = normalizeArchive(archive);
        try (ZipFile zip = new ZipFile(source.toFile())) {
            Preflight preflight = preflight(zip, effectiveLimits);
            return new ProjectArchiveInspection(
                    source, preflight.manifest(), preflight.totalBytes(),
                    inspectionWarnings(preflight.manifest()));
        }
    }

    public ProjectArchiveInspection preflightImport(Path archive, Path target) throws IOException {
        return preflightImport(archive, target, ProjectArchiveImportOptions.defaults());
    }

    /**
     * Validates both an archive and its intended destination without creating or changing files.
     */
    public ProjectArchiveInspection preflightImport(
            Path archive, Path target, ProjectArchiveImportOptions limits) throws IOException {
        validateImportTarget(target);
        return inspectProject(archive, limits);
    }

    public ProjectArchiveResult importProject(Path archive, Path target) throws IOException {
        return importProject(archive, target, ProjectArchiveImportOptions.defaults());
    }

    public ProjectArchiveResult importProject(Path archive, Path target,
                                               ProjectArchiveImportOptions limits) throws IOException {
        ProjectArchiveImportOptions effectiveLimits = limits == null
                ? ProjectArchiveImportOptions.defaults() : limits;
        Path source = normalizeArchive(archive);
        Path destination = validateImportTarget(target);
        Path parent = destination.getParent();
        Files.createDirectories(parent);
        Path staging = parent.resolve("." + destination.getFileName() + ".import-" + UUID.randomUUID());
        ProjectArchiveManifest manifest;
        long total = 0;
        try (ZipFile zip = new ZipFile(source.toFile())) {
            Preflight preflight = preflight(zip, effectiveLimits);
            manifest = preflight.manifest();
            Files.createDirectory(staging);
            byte[] buffer = new byte[BUFFER_SIZE];
            for (ProjectArchiveManifest.Entry item : manifest.entries()) {
                ZipEntry entry = preflight.payload().get(item.path());
                Path out = staging.resolve(item.path()).normalize();
                if (!out.startsWith(staging)) throw new IOException("Archive path escapes target: " + item.path());
                Files.createDirectories(out.getParent());
                MessageDigest digest = sha256();
                long count = 0;
                try (InputStream in = new BufferedInputStream(zip.getInputStream(entry));
                     OutputStream output = new BufferedOutputStream(Files.newOutputStream(out))) {
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                        if (n == 0) continue;
                        count += n;
                        if (count > item.size() || count > effectiveLimits.maxEntrySize()) {
                            throw new IOException("Archive entry exceeds declared/allowed size: " + item.path());
                        }
                        output.write(buffer, 0, n);
                        digest.update(buffer, 0, n);
                    }
                }
                String actualHash = HexFormat.of().formatHex(digest.digest());
                if (count != item.size() || !actualHash.equalsIgnoreCase(item.sha256())) {
                    throw new IOException("Checksum or size mismatch for " + item.path());
                }
                total = Math.addExact(total, count);
                restoreExecutable(out, item.executable());
            }
            KompileProjectManifest importedProject = loadSupportedProject(staging);
            if (!Objects.equals(manifest.projectId(), importedProject.getProjectId())) {
                throw new IOException("Archive projectId does not match kompile.project.json");
            }
            if (!Objects.equals(manifest.name(), importedProject.getName())) {
                throw new IOException("Archive project name does not match kompile.project.json");
            }
            publicationHook.beforePublish(destination);
            movePublished(staging, destination);
            return new ProjectArchiveResult(destination, manifest, total);
        } catch (Exception e) {
            try {
                deleteTree(staging);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            if (e instanceof IOException io) throw io;
            throw new IOException("Failed to import project archive", e);
        }
    }

    private Preflight preflight(ZipFile zip, ProjectArchiveImportOptions limits) throws IOException {
        Map<String, ZipEntry> raw = new HashMap<>();
        Set<String> rawFolded = new HashSet<>();
        ZipEntry manifestZip = null;
        int count = 0;
        Enumeration<? extends ZipEntry> enumeration = zip.entries();
        while (enumeration.hasMoreElements()) {
            ZipEntry entry = enumeration.nextElement();
            count++;
            if (count > limits.maxEntries() + 1) throw new IOException("Archive entry count exceeds limit");
            String name = entry.getName();
            validateZipName(name);
            if (raw.putIfAbsent(name, entry) != null) throw new IOException("Duplicate ZIP entry: " + name);
            if (!rawFolded.add(name.toLowerCase(Locale.ROOT))) {
                throw new IOException("Case-colliding ZIP entry: " + name);
            }
            if (entry.isDirectory()) throw new IOException("Directory entries are not allowed: " + name);
            if (name.equals(MANIFEST_ENTRY)) manifestZip = entry;
            else if (!name.startsWith(PAYLOAD_PREFIX)) throw new IOException("Unknown archive entry: " + name);
        }
        if (manifestZip == null) throw new IOException("Archive is missing manifest.json");
        if (manifestZip.getSize() > limits.maxManifestSize()) throw new IOException("Archive manifest exceeds limit");
        byte[] manifestBytes;
        try (InputStream in = zip.getInputStream(manifestZip)) {
            manifestBytes = readBounded(in, limits.maxManifestSize());
        }
        ProjectArchiveManifest manifest = mapper.readValue(manifestBytes, ProjectArchiveManifest.class);
        if (!FORMAT.equals(manifest.format())
                || manifest.formatVersion() < MIN_SUPPORTED_FORMAT_VERSION
                || manifest.formatVersion() > FORMAT_VERSION) {
            throw new IOException("Unsupported project archive format/version");
        }
        if (manifest.projectId() == null || manifest.projectId().isBlank()
                || manifest.name() == null || manifest.name().isBlank()) {
            throw new IOException("Project archive manifest is missing projectId or name");
        }
        if (manifest.entries().size() > limits.maxEntries()) throw new IOException("Manifest entry count exceeds limit");

        Map<String, ZipEntry> payload = new TreeMap<>();
        Set<String> normalized = new HashSet<>();
        Set<String> folded = new HashSet<>();
        long total = 0;
        for (ProjectArchiveManifest.Entry item : manifest.entries()) {
            if (item == null) throw new IOException("Manifest entries must not contain null");
            validateRelativePath(item.path());
            String normal = Path.of(item.path()).normalize().toString().replace('\\', '/');
            if (!normal.equals(item.path())) throw new IOException("Non-normalized manifest path: " + item.path());
            if (!normalized.add(normal)) throw new IOException("Duplicate manifest path: " + item.path());
            if (!folded.add(normal.toLowerCase(Locale.ROOT))) throw new IOException("Case-colliding manifest path: " + item.path());
            if (item.size() < 0 || item.size() > limits.maxEntrySize()) {
                throw new IOException("Archive entry size exceeds limit: " + item.path());
            }
            if (item.sha256() == null || !item.sha256().matches("[0-9a-fA-F]{64}")) {
                throw new IOException("Invalid SHA-256 for manifest entry: " + item.path());
            }
            try {
                total = Math.addExact(total, item.size());
            } catch (ArithmeticException e) {
                throw new IOException("Archive total size overflow", e);
            }
            if (total > limits.maxTotalSize()) throw new IOException("Archive total size exceeds limit");
            ZipEntry zipEntry = raw.get(PAYLOAD_PREFIX + item.path());
            if (zipEntry == null) throw new IOException("Archive is missing payload entry: " + item.path());
            if (zipEntry.getSize() >= 0 && zipEntry.getSize() != item.size()) {
                throw new IOException("ZIP and manifest sizes differ for " + item.path());
            }
            payload.put(item.path(), zipEntry);
        }
        if (raw.size() != payload.size() + 1) throw new IOException("Archive contains unexpected payload entries");
        if (manifest.defaultGraph() != null) {
            validateRelativePath(manifest.defaultGraph());
            if (!manifest.defaultGraph().endsWith(".kgraph")) {
                throw new IOException("Archive defaultGraph must name a .kgraph file");
            }
            if (!payload.containsKey(manifest.defaultGraph())) {
                throw new IOException("Archive defaultGraph is not present in manifest inventory");
            }
        }
        return new Preflight(manifest, payload, total);
    }

    private Snapshot captureSnapshot(Path root, Set<String> excludedPaths,
                                     ProjectArchiveExportOptions options,
                                     ZipOutputStream zip) throws IOException {
        return captureSnapshot(root, excludedPaths, options, zip, path -> true, PAYLOAD_PREFIX);
    }

    private Snapshot captureSnapshot(Path root, Set<String> excludedPaths,
                                     ProjectArchiveExportOptions options,
                                     ZipOutputStream zip,
                                     Predicate<String> includedFiles,
                                     String payloadPrefix) throws IOException {
        SnapshotCollector collector = new SnapshotCollector();
        try (SecureDirectoryStream<Path> secureRoot = openSecureDirectory(root)) {
            captureDirectory(secureRoot, "", excludedPaths, options, zip, collector,
                    includedFiles, payloadPrefix);
        }
        if (collector.projectDescriptor == null) {
            throw new IOException("Selected project inventory does not contain "
                    + KompileProjectStore.MANIFEST_FILE);
        }
        KompileProjectManifest project = parseSupportedProject(collector.projectDescriptor);
        return new Snapshot(project, List.copyOf(collector.entries), collector.totalSize);
    }

    private void captureDirectory(SecureDirectoryStream<Path> directory, String prefix,
                                  Set<String> excludedPaths, ProjectArchiveExportOptions options,
                                  ZipOutputStream zip, SnapshotCollector collector,
                                  Predicate<String> includedFiles,
                                  String payloadPrefix) throws IOException {
        List<Path> names = new ArrayList<>();
        for (Path child : directory) {
            Path name = child.getFileName();
            if (name == null) throw new IOException("Project contains an unnamed directory entry");
            names.add(name);
        }
        names.sort(Comparator.comparing(Path::toString));
        for (Path name : names) {
            String relative = prefix.isEmpty() ? unix(name) : prefix + "/" + unix(name);
            validateRelativePath(relative);
            BasicFileAttributes attrs = secureAttributes(directory, name);
            if (attrs.isSymbolicLink()) {
                throw new IOException("Symbolic links are not supported: " + relative);
            }
            if (attrs.isDirectory()) {
                if (relative.equals("data/pids")) {
                    if (!options.allowRunning() && containsSecureRegularFile(directory, name)) {
                        throw new IOException("Project appears to be running (data/pids contains files); use allowRunning");
                    }
                    continue;
                }
                if (excludedDirectory(relative, options.includeSensitiveFiles())) continue;
                try (SecureDirectoryStream<Path> child =
                             directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                    captureDirectory(child, relative, excludedPaths, options, zip, collector,
                            includedFiles, payloadPrefix);
                }
            } else if (attrs.isRegularFile()
                    && includedFiles.test(relative)
                    && !excludedPaths.contains(relative)
                    && !excludedFile(relative, options.includeSensitiveFiles())) {
                captureFile(directory, name, relative, attrs, options, zip, collector, payloadPrefix);
            }
        }
    }

    private void captureFile(SecureDirectoryStream<Path> directory, Path name, String relative,
                             BasicFileAttributes listedAttributes,
                             ProjectArchiveExportOptions options, ZipOutputStream zip,
                             SnapshotCollector collector, String payloadPrefix) throws IOException {
        if (collector.entries.size() >= ProjectArchiveImportOptions.defaults().maxEntries()) {
            throw new IOException("Project exceeds supported archive entry limit");
        }
        try (SeekableByteChannel channel = directory.newByteChannel(name, SECURE_READ_OPTIONS)) {
            BasicFileAttributes openedAttributes = secureAttributes(directory, name);
            requireSameSecureFile(relative, listedAttributes, openedAttributes);
            exportHook.afterFileOpened(relative);

            byte[] descriptor = null;
            if (relative.equals(KompileProjectStore.MANIFEST_FILE)) {
                descriptor = readChannelBounded(channel, MAX_PROJECT_DESCRIPTOR_BYTES, relative);
            }
            if (containsEmbeddedSecret(channel, relative)) {
                throw new IOException("Potential embedded credential in " + relative
                        + "; remove it before exporting");
            }

            boolean executable = secureExecutable(directory, name);
            channel.position(0);
            MessageDigest digest = sha256();
            long count = 0;
            ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
            if (zip != null) {
                ZipEntry entry = new ZipEntry(payloadPrefix + relative);
                entry.setTime(0);
                zip.putNextEntry(entry);
            }
            int read;
            while ((read = channel.read(buffer)) >= 0) {
                if (read == 0) {
                    buffer.clear();
                    continue;
                }
                count = Math.addExact(count, read);
                if (count > ProjectArchiveImportOptions.defaults().maxEntrySize()) {
                    throw new IOException("Project file exceeds supported archive entry limit: " + relative);
                }
                digest.update(buffer.array(), 0, read);
                if (zip != null) zip.write(buffer.array(), 0, read);
                buffer.clear();
            }
            if (zip != null) zip.closeEntry();

            BasicFileAttributes finishedAttributes = secureAttributes(directory, name);
            requireSameSecureFile(relative, openedAttributes, finishedAttributes);
            if (channel.size() != count || finishedAttributes.size() != count) {
                throw new IOException("Project file changed during export: " + relative);
            }
            if (collector.totalSize > ProjectArchiveImportOptions.defaults().maxTotalSize() - count) {
                throw new IOException("Project exceeds supported archive total size limit");
            }
            collector.totalSize += count;
            ProjectArchiveManifest.Entry item = new ProjectArchiveManifest.Entry(
                    relative, count, HexFormat.of().formatHex(digest.digest()), executable);
            String folded = relative.toLowerCase(Locale.ROOT);
            if (!collector.foldedPaths.add(folded)) {
                throw new IOException("Project contains case-colliding paths: " + relative);
            }
            collector.entries.add(item);
            if (descriptor != null) collector.projectDescriptor = descriptor;
        } catch (ArithmeticException e) {
            throw new IOException("Project size exceeds supported archive range", e);
        }
    }

    private static SecureDirectoryStream<Path> openSecureDirectory(Path root) throws IOException {
        Path parent = root.getParent();
        Path name = root.getFileName();
        if (parent == null || name == null) {
            throw new IOException("Project root must have a parent directory: " + root);
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

    private static void requireSameSecureFile(
            String relative, BasicFileAttributes before, BasicFileAttributes after) throws IOException {
        if (!after.isRegularFile() || after.isSymbolicLink()
                || (before.fileKey() != null && after.fileKey() != null
                && !before.fileKey().equals(after.fileKey()))) {
            throw new IOException("Project path changed or became a symbolic link during export: " + relative);
        }
    }

    private static boolean secureExecutable(SecureDirectoryStream<Path> directory, Path name)
            throws IOException {
        PosixFileAttributeView view = directory.getFileAttributeView(
                name, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) return false;
        try {
            Set<PosixFilePermission> permissions = view.readAttributes().permissions();
            return permissions.contains(PosixFilePermission.OWNER_EXECUTE)
                    || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
                    || permissions.contains(PosixFilePermission.OTHERS_EXECUTE);
        } catch (UnsupportedOperationException e) {
            return false;
        }
    }

    private static boolean containsSecureRegularFile(
            SecureDirectoryStream<Path> parent, Path name) throws IOException {
        try (SecureDirectoryStream<Path> directory =
                     parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
            for (Path child : directory) {
                Path childName = child.getFileName();
                BasicFileAttributes attrs = secureAttributes(directory, childName);
                if (attrs.isSymbolicLink()) {
                    throw new IOException("Symbolic links are not supported in data/pids");
                }
                if (attrs.isRegularFile()) return true;
                if (attrs.isDirectory() && containsSecureRegularFile(directory, childName)) return true;
            }
            return false;
        }
    }

    private static Set<String> excludedProjectPaths(Path root, Path... paths) {
        Set<String> result = new HashSet<>();
        for (Path path : paths) {
            Path normalized = path.toAbsolutePath().normalize();
            if (normalized.startsWith(root) && !normalized.equals(root)) {
                result.add(unix(root.relativize(normalized)));
            }
        }
        return result;
    }

    private static boolean excludedDirectory(String path, boolean includeSensitiveFiles) {
        return path.equals(".git") || path.startsWith(".git/") ||
                path.equals("target") || path.endsWith("/target") || path.contains("/target/") ||
                path.equals("node_modules") || path.endsWith("/node_modules") || path.contains("/node_modules/") ||
                path.matches("\\.kompile/(cache|sessions|state|logs)(/.*)?") ||
                path.matches("data/(logs|pids)(/.*)?") ||
                (!includeSensitiveFiles
                        && (path.equals("config/secrets") || path.startsWith("config/secrets/")));
    }

    private static boolean excludedFile(String path, boolean includeSensitiveFiles) {
        String lower = path.toLowerCase(Locale.ROOT);
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        return excludedDirectory(path, includeSensitiveFiles)
                || lower.equals(".kompile/project/open.json")
                || name.endsWith(".lock") || name.equals("lock")
                || name.startsWith("core.") || name.equals("core") ||
                name.endsWith(".hprof") || (!includeSensitiveFiles && sensitiveFile(lower, name));
    }

    private static boolean sensitiveFile(String path, String name) {
        return name.equals(".env") || (name.startsWith(".env.") && !name.equals(".env.example"))
                || name.endsWith(".secret.json") || name.equals("oauth-settings.json")
                || name.equals("oauth-encryption.key") || name.equals("credentials")
                || name.equals("credentials.json") || name.equals("auth.json")
                || name.equals(".netrc") || name.equals(".npmrc") || name.equals(".pypirc")
                || name.equals(".git-credentials") || name.equals("id_rsa")
                || name.equals("id_ed25519") || name.equals("id_ecdsa")
                || name.endsWith(".pem") || name.endsWith(".p12") || name.endsWith(".pfx")
                || name.endsWith(".jks") || name.endsWith(".keystore") || name.endsWith(".kdbx")
                || name.endsWith(".key") || path.equals(".aws/credentials")
                || path.endsWith("/.aws/credentials")
                || name.equals("application_default_credentials.json")
                || ((name.contains("service-account") || name.contains("service_account"))
                        && name.endsWith(".json"));
    }

    private static boolean containsEmbeddedSecret(SeekableByteChannel channel, String relative) throws IOException {
        String lower = relative.toLowerCase(Locale.ROOT);
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        boolean configurationFile = lower.startsWith("config/")
                || lower.startsWith(".kompile/config/")
                || lower.contains("/resources/")
                || name.startsWith("application.")
                || name.contains("config") || name.contains("settings")
                || name.endsWith(".properties") || name.endsWith(".yaml") || name.endsWith(".yml")
                || name.endsWith(".toml") || name.endsWith(".ini") || name.endsWith(".conf");
        if (!configurationFile) {
            return false;
        }
        byte[] bytes = readChannelBounded(channel, MAX_SECRET_SCAN_BYTES, relative);
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        Matcher matcher = SECRET_ASSIGNMENT.matcher(text);
        while (matcher.find()) {
            if (!placeholderSecretValue(matcher.group(2))) {
                return true;
            }
        }
        return false;
    }

    private static boolean placeholderSecretValue(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.isBlank() || lower.equals("null") || lower.equals("none") || lower.equals("false")
                || lower.startsWith("$" + "{") || lower.startsWith("#{") || lower.startsWith("{{")
                || lower.startsWith("<") || lower.contains("changeme") || lower.contains("change-me")
                || lower.contains("replace-me") || lower.startsWith("your-")
                || lower.startsWith("your_") || lower.contains("example")
                || lower.equals("dummy") || lower.equals("test");
    }

    private static void verifySnapshot(Snapshot before, Snapshot after) throws IOException {
        if (!Objects.equals(before.project().getProjectId(), after.project().getProjectId())
                || !Objects.equals(before.project().getName(), after.project().getName())
                || before.totalSize() != after.totalSize()
                || before.entries().size() != after.entries().size()) {
            throw new IOException("Project file inventory changed during export");
        }
        for (int i = 0; i < before.entries().size(); i++) {
            ProjectArchiveManifest.Entry expected = before.entries().get(i);
            ProjectArchiveManifest.Entry actual = after.entries().get(i);
            if (!expected.path().equals(actual.path())
                    || expected.size() != actual.size()
                    || expected.executable() != actual.executable()
                    || !expected.sha256().equals(actual.sha256())) {
                throw new IOException("Project changed during export: " + actual.path());
            }
        }
    }

    private static ProjectArchiveSemanticMetadata semanticMetadata(
            KompileProjectManifest project, List<ProjectArchiveManifest.Entry> entries) {
        Set<String> paths = new HashSet<>();
        entries.forEach(entry -> paths.add(entry.path()));

        Set<String> componentTypes = new TreeSet<>();
        project.getComponents().stream()
                .filter(Objects::nonNull)
                .map(component -> component.getType() == null ? null : component.getType().name())
                .filter(Objects::nonNull)
                .forEach(componentTypes::add);

        Set<String> assets = new TreeSet<>();
        assets.add("PROJECT_MANIFEST");
        boolean sources = hasAnyPrefix(paths, "data/input_documents/", "data/sources/",
                "data/documents/", "data/markdown/", "data/crawls/");
        boolean graph = hasAnyPrefix(paths, "data/graph/");
        boolean indexedCatalog = hasAnyPrefix(paths, "data/indexed-documents/");
        boolean indices = hasAnyPrefix(paths, "data/indices/", "data/index/");
        boolean noteSync = hasAnyPrefix(paths, "data/note-sync/");

        if (sources) assets.add("SOURCE_CORPUS");
        if (hasAnyPrefix(paths, "data/fact-sheets/")) assets.add("FACT_SHEET_CATALOG");
        if (noteSync) assets.add("SOURCE_SYNC_CONFIGURATION");
        if (indexedCatalog) assets.add("INDEXED_DOCUMENT_CATALOG");
        if (indices) assets.add("SEARCH_INDICES");
        if (graph) assets.add("KNOWLEDGE_GRAPH");
        if (hasAnyPrefix(paths, "data/chats/")) assets.add("CHAT_EXPORTS");
        if (hasAnyPrefix(paths, "data/ontologies/")) assets.add("ONTOLOGIES");
        if (hasAnyPrefix(paths, "data/process-definitions/", "data/processes/")) {
            assets.add("PROCESS_DEFINITIONS");
        }
        if (hasAnyPrefix(paths, "data/models/")) assets.add("MODEL_ARTIFACTS");
        if (hasAnyPrefix(paths, "data/db/")) assets.add("DATABASE_SNAPSHOT");

        Set<String> rebuildable = new TreeSet<>();
        if (indexedCatalog && !indices) rebuildable.add("SEARCH_INDICES");
        if (sources && !graph) rebuildable.add("KNOWLEDGE_GRAPH");
        if (sources && !indexedCatalog) rebuildable.add("INDEXED_DOCUMENT_CATALOG");

        Set<String> external = new TreeSet<>();
        if (noteSync) external.add("SOURCE_CREDENTIALS");
        if (!project.getCodingProjects().isEmpty()) external.add("EXTERNAL_CODE_REPOSITORIES");
        if (!project.getModels().isEmpty() && !assets.contains("MODEL_ARTIFACTS")) {
            external.add("MODEL_ARTIFACTS");
        }

        return new ProjectArchiveSemanticMetadata(
                1,
                project.getDescription(),
                project.getLifecycle() == null ? null : project.getLifecycle().name(),
                project.getTags(),
                List.copyOf(componentTypes),
                List.copyOf(assets),
                List.copyOf(rebuildable),
                List.copyOf(external));
    }

    private static boolean hasAnyPrefix(Set<String> paths, String... prefixes) {
        for (String path : paths) {
            for (String prefix : prefixes) {
                if (path.startsWith(prefix)) return true;
            }
        }
        return false;
    }

    private static List<String> inspectionWarnings(ProjectArchiveManifest manifest) {
        List<String> warnings = new ArrayList<>();
        if (manifest.formatVersion() < FORMAT_VERSION || manifest.semantic().isEmpty()) {
            warnings.add("Legacy archive: semantic knowledge-base inventory is unavailable.");
        }
        if (manifest.defaultGraph() == null) {
            warnings.add("No default .kgraph is present; graph state may need to be rebuilt after import.");
        }
        for (String requirement : manifest.semantic().externalRequirements()) {
            warnings.add("External binding required after import: " + requirement);
        }
        if (manifest.semantic().portableAssets().contains("KNOWLEDGE_GRAPH")
                && !manifest.semantic().portableAssets().contains("SOURCE_CORPUS")) {
            warnings.add("The graph is portable, but its source corpus is not included.");
        }
        return List.copyOf(warnings);
    }

    private static Path normalizeArchive(Path archive) throws IOException {
        if (archive == null) throw new IOException("Archive path is required");
        Path source = archive.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Project archive is not a regular file: " + source);
        }
        return source;
    }

    private static Path validateImportTarget(Path target) throws IOException {
        if (target == null) throw new IOException("Import target is required");
        Path destination = target.toAbsolutePath().normalize();
        if (destination.getFileName() == null || destination.getParent() == null) {
            throw new IOException("Import target must have a parent directory");
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Import target already exists: " + destination);
        }
        return destination;
    }

    private static String selectDefaultGraph(List<ProjectArchiveManifest.Entry> entries) {
        Set<String> paths = new HashSet<>();
        List<String> graphs = new ArrayList<>();
        for (ProjectArchiveManifest.Entry entry : entries) {
            paths.add(entry.path());
            if (entry.path().endsWith(".kgraph")) {
                graphs.add(entry.path());
            }
        }
        if (paths.contains(COMPLETE_PROJECT_GRAPH)) return COMPLETE_PROJECT_GRAPH;
        if (paths.contains(GLOBAL_GRAPH)) return GLOBAL_GRAPH;
        return graphs.size() == 1 ? graphs.get(0) : null;
    }

    private KompileProjectManifest loadSupportedProject(Path root) throws IOException {
        Path manifest = root.resolve(KompileProjectStore.MANIFEST_FILE);
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Project root does not contain " + KompileProjectStore.MANIFEST_FILE);
        }
        try {
            return requireSupportedProject(store.load(root));
        } catch (IllegalStateException e) {
            throw new IOException("Project manifest is not loadable: " + e.getMessage(), e);
        }
    }

    private KompileProjectManifest parseSupportedProject(byte[] descriptor) throws IOException {
        try {
            return requireSupportedProject(mapper.readValue(descriptor, KompileProjectManifest.class));
        } catch (IOException e) {
            throw new IOException("Project manifest is not loadable: " + e.getMessage(), e);
        }
    }

    private static KompileProjectManifest requireSupportedProject(KompileProjectManifest project)
            throws IOException {
        if (project.getSchemaVersion() != 1) {
            throw new IOException("Unsupported project schemaVersion: " + project.getSchemaVersion());
        }
        if (project.getProjectId() == null || project.getProjectId().isBlank()
                || project.getName() == null || project.getName().isBlank()) {
            throw new IOException("Project manifest is missing projectId or name");
        }
        return project;
    }

    private static void validateZipName(String name) throws IOException {
        if (name == null || name.isBlank() || name.indexOf('\\') >= 0 || name.startsWith("/") ||
                name.matches("^[A-Za-z]:.*") || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new IOException("Unsafe archive entry name: " + name);
        }
        for (String part : name.split("/")) {
            if (part.equals(".") || part.equals("..") || part.isEmpty()) {
                throw new IOException("Unsafe archive entry name: " + name);
            }
        }
    }

    private static void validateRelativePath(String path) throws IOException {
        validateZipName(path);
        if (path.startsWith(PAYLOAD_PREFIX) || path.equals(MANIFEST_ENTRY)) {
            throw new IOException("Invalid manifest payload path: " + path);
        }
    }

    private static byte[] readBounded(InputStream in, long limit) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long count = 0;
        int n;
        while ((n = in.read(buffer)) >= 0) {
            count += n;
            if (count > limit) throw new IOException("Archive manifest exceeds limit");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] readChannelBounded(SeekableByteChannel channel, long limit, String label)
            throws IOException {
        if (channel.size() > limit) {
            throw new IOException(label + " exceeds size limit of " + limit + " bytes");
        }
        channel.position(0);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        long count = 0;
        int read;
        while ((read = channel.read(buffer)) >= 0) {
            if (read == 0) {
                buffer.clear();
                continue;
            }
            count += read;
            if (count > limit) throw new IOException(label + " exceeds size limit of " + limit + " bytes");
            out.write(buffer.array(), 0, read);
            buffer.clear();
        }
        channel.position(0);
        return out.toByteArray();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String unix(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void restoreExecutable(Path path, boolean executable) throws IOException {
        if (!executable) return;
        try {
            Set<PosixFilePermission> permissions = new HashSet<>(Files.getPosixFilePermissions(path));
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            path.toFile().setExecutable(true, false);
        }
    }

    private static void movePublished(Path source, Path destination) throws IOException {
        int result;
        try {
            if (Platform.isLinux()) {
                result = LinuxLibC.INSTANCE.renameat2(
                        -100, source.toString(), -100, destination.toString(), 1);
            } else if (Platform.isMac()) {
                result = MacLibC.INSTANCE.renamex_np(source.toString(), destination.toString(), 0x00000004);
            } else if (Platform.isWindows()) {
                boolean moved = WindowsKernel32.INSTANCE.MoveFileExW(
                        new WString(source.toString()), new WString(destination.toString()), 0x00000008);
                if (moved) return;
                throw publicationFailure(destination, Native.getLastError());
            } else {
                throw new IOException("Operating system does not support atomic no-replace publication");
            }
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            throw new IOException("Atomic no-replace publication is unavailable on this platform", e);
        }
        if (result != 0) {
            throw publicationFailure(destination, Native.getLastError());
        }
    }

    private static IOException publicationFailure(Path destination, int nativeError) {
        if (nativeError == 17 || nativeError == 80 || nativeError == 183) {
            return new FileAlreadyExistsException(destination.toString());
        }
        return new IOException("Atomic no-replace project publication failed for " + destination
                + " (native error " + nativeError + ")");
    }

    private interface LinuxLibC extends Library {
        LinuxLibC INSTANCE = Native.load(Platform.C_LIBRARY_NAME, LinuxLibC.class);
        int renameat2(int oldDirectory, String oldPath, int newDirectory, String newPath, int flags);
    }

    private interface MacLibC extends Library {
        MacLibC INSTANCE = Native.load(Platform.C_LIBRARY_NAME, MacLibC.class);
        int renamex_np(String oldPath, String newPath, int flags);
    }

    private interface WindowsKernel32 extends StdCallLibrary {
        WindowsKernel32 INSTANCE = Native.load("kernel32", WindowsKernel32.class);
        boolean MoveFileExW(WString oldPath, WString newPath, int flags);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @FunctionalInterface
    interface ExportHook {
        void afterFileOpened(String relative) throws IOException;
    }

    @FunctionalInterface
    interface PublicationHook {
        void beforePublish(Path destination) throws IOException;
    }

    private static final class SnapshotCollector {
        private final List<ProjectArchiveManifest.Entry> entries = new ArrayList<>();
        private final Set<String> foldedPaths = new HashSet<>();
        private byte[] projectDescriptor;
        private long totalSize;
    }

    private record Snapshot(KompileProjectManifest project,
                            List<ProjectArchiveManifest.Entry> entries,
                            long totalSize) {}
    private record Preflight(
            ProjectArchiveManifest manifest,
            Map<String, ZipEntry> payload,
            long totalBytes) {}
}
