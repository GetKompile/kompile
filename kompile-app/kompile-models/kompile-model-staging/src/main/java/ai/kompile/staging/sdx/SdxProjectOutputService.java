/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.staging.sdx;

import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.project.KompileProjectInitRequest;
import ai.kompile.project.KompileProjectManifest;
import ai.kompile.project.KompileProjectModel;
import ai.kompile.project.KompileProjectStore;
import ai.kompile.project.archive.ProjectArchiveService;
import ai.kompile.staging.config.SdxStagingProperties;
import ai.kompile.staging.config.StagingPropertyKeys;
import ai.kompile.staging.download.DownloadRequest;
import org.nd4j.dsp.model.SdxCompiledModel;
import org.nd4j.dsp.model.SdxModelCache;
import org.nd4j.dsp.model.SdxModelCompiler;
import org.nd4j.dsp.model.SdxQuantizationContract;
import org.nd4j.dsp.model.SdxTargetProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Compiles one canonical SDZ for one mobile target and publishes a canonical .kproject.
 *
 * <p>Target products remain internal SDX cache objects. The project contains one enriched
 * .sdz plus the project's portable graph and Markdown provenance; applications never select
 * a vendor model format. Compilation and archive publication are both fail-closed.</p>
 */
@Service
public class SdxProjectOutputService {
    public static final String OUTPUT_MODEL = "model";
    public static final String OUTPUT_KPROJECT = "kproject";
    public static final String QUANTIZATION_NONE = "none";
    public static final String QUANTIZATION_INT8 = "int8-per-channel";

    private static final Pattern SAFE_MODEL_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final List<String> PORTABLE_KNOWLEDGE_ROOTS = List.of(
            "data/graph",
            "data/markdown",
            "data/fact-sheets",
            "data/sources",
            "data/indexed-documents");
    private static final Set<String> TOKENIZER_NAMES =
            Set.of("tokenizer.json", "tokenizer.model");
    private static final Set<String> GENERATION_CONFIG_NAMES =
            Set.of("generation_config.json", "text-generation.json");

    private final Path modelsDir;
    private final Path sourceProjectRoot;
    private final SdxStagingProperties properties;
    private final SdxModelCompiler.TargetCompiler compilerOverride;
    private final KompileProjectStore projectStore;
    private final ProjectArchiveService archiveService;

    @Autowired
    public SdxProjectOutputService(
            SdxStagingProperties properties,
            @Value(StagingPropertyKeys.MODELS_DIR_VALUE) String modelsDir,
            @Value("${kompile.staging.project-dir:}") String projectDir) {
        this(
                Path.of(modelsDir),
                projectDir == null || projectDir.isBlank() ? null : Path.of(projectDir),
                properties,
                null);
    }

    SdxProjectOutputService(
            Path modelsDir,
            Path sourceProjectRoot,
            SdxStagingProperties properties,
            SdxModelCompiler.TargetCompiler compilerOverride) {
        this.modelsDir = Objects.requireNonNull(modelsDir, "modelsDir")
                .toAbsolutePath().normalize();
        this.sourceProjectRoot = sourceProjectRoot == null
                ? null
                : sourceProjectRoot.toAbsolutePath().normalize();
        this.properties = Objects.requireNonNull(properties, "properties");
        this.compilerOverride = compilerOverride;
        this.projectStore = new KompileProjectStore();
        this.archiveService = new ProjectArchiveService(projectStore);
    }

    public static boolean isProjectOutputRequested(DownloadRequest request) {
        return request != null
                && OUTPUT_KPROJECT.equals(normalizeOutputFormat(request.getOutputFormat()));
    }

    public Path createProject(
            Path stagingWorkspace,
            Path canonicalSdz,
            DownloadRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        if (!isProjectOutputRequested(request)) {
            throw new IllegalArgumentException("SDX project output was not requested");
        }

        String modelId = requireModelId(request.getModelId());
        SdxTargetProfile target = requireAndroidTarget(request.getTargetProfile());
        String quantization = normalizeQuantization(request.getQuantizationProfile());
        String targetSoc = normalizeTargetSoc(target, request.getTargetSoc());

        Path workspace = Objects.requireNonNull(stagingWorkspace, "stagingWorkspace")
                .toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        Path source = Objects.requireNonNull(canonicalSdz, "canonicalSdz")
                .toAbsolutePath().normalize();
        requireRegularFile(source, "Canonical SameDiff model");
        if (!source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sdz")) {
            throw new IOException("Mobile project compilation requires a canonical .sdz model");
        }

        KompileProjectManifest sourceProject = loadSourceProject();
        Path operationRoot = workspace.resolve(".sdx-project-" + UUID.randomUUID());
        Path projectRoot = operationRoot.resolve("project");
        Path outputDirectory = workspace.resolve("outputs");
        Path output = outputDirectory.resolve(modelId + "-" + target.id() + ".kproject");
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to overwrite staged mobile project: " + output);
        }

        Path tokenizer = findUniqueAsset(workspace, TOKENIZER_NAMES);
        Path generationConfig = findUniqueAsset(workspace, GENERATION_CONFIG_NAMES);
        Files.createDirectories(operationRoot);
        try {
            Path quantizationConfig = QUANTIZATION_INT8.equals(quantization)
                    ? writeInt8Contract(operationRoot, target, targetSoc)
                    : null;

            SdxModelCache cache = new SdxModelCache(cacheRoot());
            SdxModelCompiler.CompileOptions.Builder options =
                    SdxModelCompiler.CompileOptions.builder()
                            .modelId(modelId)
                            .targetSoc(targetSoc)
                            .cacheKeyProperty("stagingOutput", OUTPUT_KPROJECT);
            if (tokenizer != null) {
                options.tokenizer(tokenizer);
            }
            if (generationConfig != null) {
                options.textGenerationConfig(generationConfig);
            }
            if (quantizationConfig != null) {
                options.quantizationConfig(quantizationConfig);
            }

            SdxCompiledModel compiled = new SdxModelCompiler(cache).compile(
                    source,
                    target,
                    targetCompiler(target, quantization, targetSoc),
                    options.build());

            KompileProjectModel model = projectModel(
                    request, target, targetSoc, quantization, compiled);
            KompileProjectInitRequest init = new KompileProjectInitRequest();
            init.setName(sourceProject.getName());
            init.setDescription(sourceProject.getDescription());
            init.setIncludeStandardComponents(false);
            init.setInitializeGit(false);
            init.setTags(mergeTags(sourceProject.getTags(), target.id()));
            init.setModels(List.of(model));

            KompileProjectManifest mobileProject = projectStore.init(projectRoot, init);
            mobileProject.setProjectId(sourceProject.getProjectId());
            mobileProject.setName(sourceProject.getName());
            mobileProject.setDescription(sourceProject.getDescription());
            mobileProject.setTags(mergeTags(sourceProject.getTags(), target.id()));
            mobileProject.setModels(List.of(model));
            mobileProject.setMetadata(projectMetadata(
                    sourceProject, target, targetSoc, quantization, compiled));
            mobileProject.setUpdatedAt(Instant.now());
            projectStore.save(projectRoot, mobileProject);

            copyPortableKnowledge(sourceProjectRoot, projectRoot);
            requirePortableKnowledge(projectRoot);

            Path packagedModel = projectRoot.resolve(model.getPath());
            Files.createDirectories(packagedModel.getParent());
            cache.packageCompiledSdz(source, List.of(target), packagedModel);

            Files.createDirectories(outputDirectory);
            Path pending = output.resolveSibling(
                    "." + UUID.randomUUID() + ".pending-" + output.getFileName());
            try {
                archiveService.exportProject(projectRoot, pending);
                AtomicProjectPublisher.publish(pending, output);
                return output;
            } catch (java.nio.file.FileAlreadyExistsException raced) {
                throw new IOException(
                        "Refusing to overwrite staged mobile project: " + output,
                        raced);
            } finally {
                Files.deleteIfExists(pending);
            }
        } finally {
            deleteTree(operationRoot);
        }
    }

    private KompileProjectManifest loadSourceProject() throws IOException {
        if (sourceProjectRoot == null) {
            throw new IOException(
                    "Mobile .kproject output requires kompile.staging.project-dir so the "
                            + "fact-sheet graph and Markdown sources can be bundled");
        }
        if (!Files.isDirectory(sourceProjectRoot, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(sourceProjectRoot)) {
            throw new IOException("Configured Kompile project directory is unavailable: "
                    + sourceProjectRoot);
        }
        try {
            return projectStore.load(sourceProjectRoot);
        } catch (RuntimeException invalid) {
            throw new IOException(
                    "Configured kompile.staging.project-dir is not a valid Kompile project: "
                            + sourceProjectRoot,
                    invalid);
        }
    }

    private Path cacheRoot() {
        Path configured = properties.getCacheDir();
        return configured == null
                ? modelsDir.resolve(".staging").resolve("sdx-cache")
                : configured.toAbsolutePath().normalize();
    }

    private SdxModelCompiler.TargetCompiler targetCompiler(
            SdxTargetProfile target,
            String quantization,
            String targetSoc) throws IOException {
        if (compilerOverride != null) {
            return compilerOverride;
        }
        if (target == SdxTargetProfile.ANDROID_ARM64_NNAPI_ACCELERATOR
                && QUANTIZATION_NONE.equals(quantization)) {
            return SdxModelCompiler.nnapiDeviceCompilationPolicy(targetSoc);
        }
        List<String> command = properties.getCompilerCommand();
        if (command.isEmpty() || command.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IOException(
                    "No SDX target compiler is configured for " + target.id()
                            + " with quantization " + quantization + ". Set "
                            + "kompile.staging.sdx.compiler-command as an argument vector; "
                            + "mobile staging never substitutes CPU or a prepared placeholder.");
        }
        return SdxModelCompiler.externalCommand(
                command,
                requireSetting(properties.getCompilerId(), "kompile.staging.sdx.compiler-id"),
                requireSetting(
                        properties.getCompilerVersion(),
                        "kompile.staging.sdx.compiler-version"),
                requireSetting(
                        properties.getCompilerFingerprint(),
                        "kompile.staging.sdx.compiler-fingerprint"));
    }

    private static String requireSetting(String value, String name) throws IOException {
        if (value == null || value.isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IOException("Missing or invalid " + name);
        }
        return value.trim();
    }

    private KompileProjectModel projectModel(
            DownloadRequest request,
            SdxTargetProfile target,
            String targetSoc,
            String quantization,
            SdxCompiledModel compiled) {
        KompileProjectModel model = new KompileProjectModel();
        model.setId(request.getModelId() + "-" + target.id());
        model.setModelId(request.getModelId());
        model.setRole("offline-chat");
        model.setVersion(firstNonBlank(request.getRevision(), "staged"));
        model.setSource(request.getSource());
        model.setSourceRepository(request.getRepository());
        model.setSourceRevision(request.getRevision());
        model.setPath("data/models/" + target.id() + "/model.sdz");
        model.setRequired(true);

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("sdxTargetProfile", target.id());
        metadata.put("sdxTargetSoc", targetSoc);
        metadata.put("sdxCompileKey", compiled.compileKey());
        metadata.put("sdxCompilerId", compiled.compilerId());
        metadata.put("sdxCompilerVersion", compiled.compilerVersion());
        metadata.put("sourceSha256", compiled.sourceIdentity().sha256());
        metadata.put("quantization", quantization);
        metadata.put("deviceOnly", "true");
        metadata.put("allowHostFallback", "false");
        model.setMetadata(metadata);
        model.setTags(List.of("mobile", "offline", target.id()));
        return model;
    }

    private static Map<String, String> projectMetadata(
            KompileProjectManifest sourceProject,
            SdxTargetProfile target,
            String targetSoc,
            String quantization,
            SdxCompiledModel compiled) {
        Map<String, String> metadata = new LinkedHashMap<>(sourceProject.getMetadata());
        metadata.put("artifact", OUTPUT_KPROJECT);
        metadata.put("offlineGraphChat", "true");
        metadata.put("sourceProjectId", sourceProject.getProjectId());
        metadata.put("sdxTargetProfile", target.id());
        metadata.put("sdxTargetSoc", targetSoc);
        metadata.put("sdxCompileKey", compiled.compileKey());
        metadata.put("quantization", quantization);
        metadata.put("defaultGraph", "data/graph/project.kgraph");
        metadata.put("markdownRoot", "data/markdown");
        return metadata;
    }

    private void copyPortableKnowledge(Path sourceRoot, Path targetRoot) throws IOException {
        CopyBudget budget = new CopyBudget(
                properties.getMaxKnowledgeFiles(),
                properties.getMaxKnowledgeBytes());
        for (String relative : PORTABLE_KNOWLEDGE_ROOTS) {
            copyTree(sourceRoot.resolve(relative), targetRoot.resolve(relative), budget);
        }
    }

    private static void copyTree(Path source, Path destination, CopyBudget budget)
            throws IOException {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(source)) {
            throw new IOException("Portable project source cannot be a symbolic link: " + source);
        }
        try (Stream<Path> paths = Files.walk(source)) {
            Iterator<Path> iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (Files.isSymbolicLink(path)) {
                    throw new IOException(
                            "Portable project source contains a symbolic link: " + path);
                }
                Path relative = source.relativize(path);
                Path target = destination.resolve(relative).normalize();
                if (!target.startsWith(destination.toAbsolutePath().normalize())) {
                    throw new IOException("Portable project path escapes destination: " + relative);
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    long before = Files.size(path);
                    budget.accept(before, path);
                    Files.createDirectories(target.getParent());
                    Files.copy(
                            path,
                            target,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                    if (Files.size(path) != before || Files.size(target) != before) {
                        throw new IOException(
                                "Portable project source changed while being copied: " + path);
                    }
                } else {
                    throw new IOException("Unsupported portable project entry: " + path);
                }
            }
        }
    }

    private static void requirePortableKnowledge(Path projectRoot) throws IOException {
        Path graph = projectRoot.resolve("data/graph/project.kgraph");
        requireRegularFile(graph, "Portable project graph");
        try {
            UnifiedGraph.load(graph);
        } catch (IOException invalid) {
            throw new IOException(
                    "Portable project graph is not a valid .kgraph. Refresh the project graph "
                            + "snapshot before building the offline mobile project.",
                    invalid);
        }

        Path markdown = projectRoot.resolve("data/markdown");
        boolean hasMarkdown = false;
        if (Files.isDirectory(markdown, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> paths = Files.walk(markdown)) {
                hasMarkdown = paths.anyMatch(path ->
                        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                                && !Files.isSymbolicLink(path)
                                && path.getFileName().toString()
                                        .toLowerCase(Locale.ROOT).endsWith(".md"));
            }
        }
        if (!hasMarkdown) {
            throw new IOException(
                    "Project has no Markdown sources under data/markdown. Sync the fact sheet "
                            + "sources before building the offline mobile project.");
        }
    }

    private Path writeInt8Contract(
            Path operationRoot,
            SdxTargetProfile target,
            String targetSoc) throws IOException {
        Path output = operationRoot.resolve("int8-per-channel.json");
        SdxQuantizationContract.writeWeightInt8Profile(output, target, targetSoc);
        return output;
    }

    private static Path findUniqueAsset(Path root, Set<String> names) throws IOException {
        List<Path> matches = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> names.contains(
                            path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .sorted()
                    .forEach(matches::add);
        }
        if (matches.size() > 1) {
            throw new IOException("Multiple candidate assets found for " + names + ": " + matches);
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    private static SdxTargetProfile requireAndroidTarget(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "targetProfile is required when outputFormat is kproject");
        }
        SdxTargetProfile target = SdxTargetProfile.fromId(value);
        if (target == SdxTargetProfile.IOS_ARM64_METAL) {
            throw new IllegalArgumentException(
                    "Android model staging does not accept the iOS Metal target");
        }
        return target;
    }

    public static String normalizeOutputFormat(String value) {
        if (value == null || value.isBlank()) {
            return OUTPUT_MODEL;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (OUTPUT_MODEL.equals(normalized) || OUTPUT_KPROJECT.equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("Unsupported staging outputFormat: " + value);
    }

    public static String normalizeQuantization(String value) {
        if (value == null || value.isBlank()) {
            return QUANTIZATION_NONE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (QUANTIZATION_NONE.equals(normalized)) {
            return normalized;
        }
        if ("int8".equals(normalized) || QUANTIZATION_INT8.equals(normalized)) {
            return QUANTIZATION_INT8;
        }
        throw new IllegalArgumentException(
                "Unsupported SDX quantizationProfile: " + value
                        + ". Supported values are none and int8-per-channel.");
    }

    public static String normalizeTargetProfile(String value) {
        return requireAndroidTarget(value).id();
    }

    public static String normalizeTargetSoc(SdxTargetProfile target, String value) {
        String resolved = value == null || value.isBlank()
                ? defaultTargetSoc(target)
                : value.trim();
        if (resolved.indexOf('\n') >= 0 || resolved.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("targetSoc must be a single line");
        }
        return resolved;
    }

    private static String defaultTargetSoc(SdxTargetProfile target) {
        return switch (target) {
            case ANDROID_ARM64_VULKAN -> "Android_Vulkan_1_1";
            case ANDROID_ARM64_HEXAGON_HTP -> "SM8650";
            case ANDROID_ARM64_NNAPI_ACCELERATOR -> "Tensor_G3";
            case ANDROID_ARM64_GOOGLE_TENSOR_G5 -> "Tensor_G5";
            default -> throw new IllegalArgumentException(
                    "No Android target SoC for " + target.id());
        };
    }

    private static String requireModelId(String value) {
        if (value == null || !SAFE_MODEL_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "modelId must match " + SAFE_MODEL_ID.pattern());
        }
        return value;
    }

    private static void requireRegularFile(Path path, String label) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)
                || Files.size(path) <= 0L) {
            throw new IOException(label + " is missing, empty, or unsafe: " + path);
        }
    }

    private static List<String> mergeTags(List<String> source, String target) {
        List<String> tags = new ArrayList<>();
        if (source != null) {
            tags.addAll(source);
        }
        for (String tag : List.of("offline", "mobile", "graph-chat", target)) {
            if (!tags.contains(tag)) {
                tags.add(tag);
            }
        }
        return tags;
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            IOException failure = null;
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException deleteFailure) {
                    if (failure == null) {
                        failure = deleteFailure;
                    } else {
                        failure.addSuppressed(deleteFailure);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class CopyBudget {
        private final int maxFiles;
        private final long maxBytes;
        private int files;
        private long bytes;

        private CopyBudget(int maxFiles, long maxBytes) {
            if (maxFiles <= 0 || maxBytes <= 0L) {
                throw new IllegalArgumentException(
                        "SDX portable knowledge limits must be positive");
            }
            this.maxFiles = maxFiles;
            this.maxBytes = maxBytes;
        }

        private void accept(long size, Path path) throws IOException {
            files++;
            try {
                bytes = Math.addExact(bytes, size);
            } catch (ArithmeticException overflow) {
                throw new IOException("Portable project byte count overflow", overflow);
            }
            if (files > maxFiles || bytes > maxBytes) {
                throw new IOException(
                        "Portable project knowledge exceeds configured limits at " + path
                                + " (files=" + files + "/" + maxFiles
                                + ", bytes=" + bytes + "/" + maxBytes + ")");
            }
        }
    }
}
