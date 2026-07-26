package ai.kompile.chat.local;

import ai.kompile.project.knowledge.PortableKnowledge;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;

/**
 * Installs the mobile subset of a canonical {@code .kproject} archive.
 *
 * <p>The archive remains the only user-facing artifact. The selected target-compiled
 * {@code .sdz}, default {@code .kgraph}, project descriptor, and Markdown source tree are
 * copied into one private installation directory only after the existing project inventory,
 * identity, size, and SHA-256 checks pass. Callers validate the SDX and graph payloads before
 * atomically selecting the returned paths in application preferences.</p>
 */
public final class ProjectArchiveInstaller {
    /** Maximum compressed archive bytes copied from mobile document providers (20 GiB). */
    public static final long MAX_ARCHIVE_BYTES = 20L * 1024 * 1024 * 1024;
    public static final String TARGET_PROFILE_METADATA = "sdxTargetProfile";
    private static final String PROJECT_DESCRIPTOR = "kompile.project.json";
    private static final String MARKDOWN_ROOT = "data/markdown/";

    private ProjectArchiveInstaller() {
    }

    public static InstalledProject install(
            Path archive,
            String targetProfile,
            Path installationsRoot) throws IOException {
        String normalizedTarget = normalizeTargetProfile(targetProfile);
        if (installationsRoot == null) {
            throw new IllegalArgumentException("Project installation root is required");
        }
        Path root = installationsRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Project installation root must be a real directory: " + root);
        }
        return ProjectBundleResolver.withValidatedArchive(
                archive, validated -> installValidated(validated, normalizedTarget, root));
    }

    private static InstalledProject installValidated(
            ProjectBundleResolver.ValidatedArchive archive,
            String targetProfile,
            Path installationsRoot) throws IOException {
        String graphRelative = ProjectBundleResolver.selectArchiveDefault(archive.manifest());
        if (!graphRelative.endsWith(".kgraph")) {
            throw new IOException("Project default graph is not a .kgraph: " + graphRelative);
        }
        String modelRelative = selectModel(
                archive.identity().descriptor(), archive.manifest(), targetProfile);

        List<String> knowledgeRelatives = archive.manifest().entries().keySet().stream()
                .filter(PortableKnowledge::isPortablePath)
                .sorted()
                .toList();
        if (!knowledgeRelatives.contains(PROJECT_DESCRIPTOR)
                || !knowledgeRelatives.contains(graphRelative)) {
            throw new IOException("Project archive is missing required portable knowledge payloads");
        }
        List<String> sourceRelatives = knowledgeRelatives.stream()
                .filter(path -> path.startsWith(MARKDOWN_ROOT))
                .toList();
        List<PortableKnowledge.Entry> knowledgeInventory = knowledgeRelatives.stream()
                .map(path -> {
                    ProjectBundleResolver.InventoryEntry entry = archive.manifest().entries().get(path);
                    return new PortableKnowledge.Entry(entry.path(), entry.size(), entry.sha256());
                })
                .toList();
        String knowledgeRevision = PortableKnowledge.revision(
                archive.identity().projectId(),
                archive.identity().name(),
                graphRelative,
                knowledgeInventory);

        String prefix = safeSegment(archive.identity().projectId())
                + "-" + archive.revision().substring(0, 12)
                + "-" + safeSegment(targetProfile);
        Path pending = installationsRoot.resolve("." + prefix + "-" + UUID.randomUUID() + ".pending");
        Path activated = installationsRoot.resolve(prefix + "-" + UUID.randomUUID());
        boolean moved = false;
        try {
            Files.createDirectory(pending);
            Map<String, Path> knowledgePaths = new LinkedHashMap<>();
            for (String relative : knowledgeRelatives) {
                knowledgePaths.put(relative, extract(archive, relative, pending));
            }
            Path graphPath = knowledgePaths.get(graphRelative);
            Path modelPath = extract(archive, modelRelative, pending);
            List<Path> sources = new ArrayList<>(sourceRelatives.size());
            for (String source : sourceRelatives) {
                sources.add(knowledgePaths.get(source));
            }

            moveAtomically(pending, activated);
            moved = true;
            return new InstalledProject(
                    activated,
                    rebase(pending, activated, modelPath),
                    rebase(pending, activated, graphPath),
                    activated.resolve(MARKDOWN_ROOT.substring(0, MARKDOWN_ROOT.length() - 1)),
                    rebaseAll(pending, activated, sources),
                    archive.identity().projectId(),
                    archive.identity().name(),
                    archive.revision(),
                    knowledgeRevision,
                    targetProfile);
        } catch (IOException | RuntimeException failure) {
            deleteTree(moved ? activated : pending);
            throw failure;
        }
    }

    private static Path extract(
            ProjectBundleResolver.ValidatedArchive archive,
            String relative,
            Path installationRoot) throws IOException {
        ProjectBundleResolver.InventoryEntry inventory = archive.manifest().entries().get(relative);
        if (inventory == null) {
            throw new IOException("Project archive does not inventory required payload: " + relative);
        }
        ZipEntry entry = archive.zipEntries().get("project/" + relative);
        if (entry == null || entry.isDirectory()) {
            throw new IOException("Project archive is missing required payload: project/" + relative);
        }
        Path target = resolveContained(installationRoot, relative);
        Files.createDirectories(target.getParent());
        Files.createFile(target);
        try {
            ProjectBundleResolver.streamVerified(archive.zip(), entry, inventory, target);
            return target;
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(target);
            throw failure;
        }
    }

    private static String selectModel(
            Map<String, Object> descriptor,
            ProjectBundleResolver.Manifest manifest,
            String targetProfile) throws IOException {
        List<ModelCandidate> candidates = descriptorCandidates(descriptor, manifest);
        List<ModelCandidate> exact = candidates.stream()
                .filter(candidate -> targetProfile.equals(candidate.targetProfile()))
                .toList();
        if (exact.size() == 1) {
            return exact.get(0).path();
        }
        if (exact.size() > 1) {
            throw new IOException("Project declares multiple SDX models for target " + targetProfile);
        }

        List<String> declaredTargets = candidates.stream()
                .map(ModelCandidate::targetProfile)
                .filter(value -> value != null)
                .distinct()
                .sorted()
                .toList();
        if (!declaredTargets.isEmpty()) {
            throw new IOException("Project has no SDX model for target " + targetProfile
                    + "; available targets: " + declaredTargets);
        }

        List<String> unprofiled = candidates.stream().map(ModelCandidate::path).distinct().toList();
        if (unprofiled.isEmpty()) {
            unprofiled = manifest.entries().keySet().stream()
                    .filter(path -> path.toLowerCase(Locale.ROOT).endsWith(".sdz"))
                    .sorted()
                    .toList();
        }
        if (unprofiled.size() == 1) {
            return unprofiled.get(0);
        }
        if (unprofiled.isEmpty()) {
            throw new IOException("Project contains no canonical SameDiff .sdz model");
        }
        throw new IOException("Project contains multiple unprofiled .sdz models; set model metadata."
                + TARGET_PROFILE_METADATA + " to select target " + targetProfile);
    }

    private static List<ModelCandidate> descriptorCandidates(
            Map<String, Object> descriptor,
            ProjectBundleResolver.Manifest manifest) throws IOException {
        Object rawModels = descriptor.get("models");
        if (rawModels == null) {
            return List.of();
        }
        if (!(rawModels instanceof List<?> models)) {
            throw new IOException("kompile.project.json models must be an array");
        }
        List<ModelCandidate> result = new ArrayList<>();
        for (Object raw : models) {
            if (!(raw instanceof Map<?, ?> model)) {
                throw new IOException("kompile.project.json model entry must be an object");
            }
            Object rawPath = model.get("path");
            if (!(rawPath instanceof String path) || path.isBlank()
                    || !path.toLowerCase(Locale.ROOT).endsWith(".sdz")) {
                continue;
            }
            if (!manifest.entries().containsKey(path)) {
                throw new IOException("Project model is absent from archive inventory: " + path);
            }
            String profile = null;
            Object rawMetadata = model.get("metadata");
            if (rawMetadata != null) {
                if (!(rawMetadata instanceof Map<?, ?> metadata)) {
                    throw new IOException("kompile.project.json model metadata must be an object");
                }
                Object rawProfile = metadata.get(TARGET_PROFILE_METADATA);
                if (rawProfile != null) {
                    if (!(rawProfile instanceof String value) || value.isBlank()) {
                        throw new IOException("Model metadata." + TARGET_PROFILE_METADATA
                                + " must be a non-empty string");
                    }
                    profile = normalizeTargetProfile(value);
                }
            }
            result.add(new ModelCandidate(path, profile));
        }
        return result;
    }

    private static String normalizeTargetProfile(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("SDX target profile is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException("Invalid SDX target profile: " + value);
        }
        return normalized;
    }

    private static Path resolveContained(Path root, String relative) throws IOException {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Project payload escapes installation root: " + relative);
        }
        return target;
    }

    private static String safeSegment(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank() || ".".equals(safe) || "..".equals(safe)) {
            safe = "project";
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

    private static Path rebase(Path oldRoot, Path newRoot, Path path) {
        return newRoot.resolve(oldRoot.relativize(path));
    }

    private static List<Path> rebaseAll(Path oldRoot, Path newRoot, List<Path> paths) {
        List<Path> rebased = new ArrayList<>(paths.size());
        for (Path path : paths) {
            rebased.add(rebase(oldRoot, newRoot, path));
        }
        return Collections.unmodifiableList(rebased);
    }

    static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            List<Path> ordered = paths.sorted(Comparator.reverseOrder()).toList();
            IOException failure = null;
            for (Path path : ordered) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException deletion) {
                    if (failure == null) {
                        failure = deletion;
                    } else {
                        failure.addSuppressed(deletion);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private record ModelCandidate(String path, String targetProfile) {
    }

    public static final class InstalledProject {
        private final Path installationRoot;
        private final Path modelPath;
        private final Path graphPath;
        private final Path sourcesRoot;
        private final List<Path> sourcePaths;
        private final String projectId;
        private final String projectName;
        private final String revision;
        private final String knowledgeRevision;
        private final String targetProfile;

        private InstalledProject(
                Path installationRoot,
                Path modelPath,
                Path graphPath,
                Path sourcesRoot,
                List<Path> sourcePaths,
                String projectId,
                String projectName,
                String revision,
                String knowledgeRevision,
                String targetProfile) {
            this.installationRoot = installationRoot;
            this.modelPath = modelPath;
            this.graphPath = graphPath;
            this.sourcesRoot = sourcesRoot;
            this.sourcePaths = sourcePaths;
            this.projectId = projectId;
            this.projectName = projectName;
            this.revision = revision;
            this.knowledgeRevision = knowledgeRevision;
            this.targetProfile = targetProfile;
        }

        public Path installationRoot() {
            return installationRoot;
        }

        public Path modelPath() {
            return modelPath;
        }

        public Path graphPath() {
            return graphPath;
        }

        public Path sourcesRoot() {
            return sourcesRoot;
        }

        public List<Path> sourcePaths() {
            return sourcePaths;
        }

        public String projectId() {
            return projectId;
        }

        public String projectName() {
            return projectName;
        }

        public String revision() {
            return revision;
        }

        /** Content revision for descriptor, graph, Markdown, fact sheets, and indexes only. */
        public String knowledgeRevision() {
            return knowledgeRevision;
        }

        /** Root containing the portable knowledge tree. Initially the immutable installation root. */
        public Path knowledgeRoot() {
            return installationRoot;
        }

        public String targetProfile() {
            return targetProfile;
        }

        public void delete() throws IOException {
            deleteTree(installationRoot);
        }
    }
}
