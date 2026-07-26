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
import ai.kompile.staging.conversion.ConversionArtifact;
import ai.kompile.staging.download.DownloadRequest;
import ai.kompile.staging.download.StagingCancellation;
import ai.kompile.staging.download.TextModelAssetMap;
import org.nd4j.dsp.model.SdxCompiledModel;
import org.nd4j.dsp.model.SdxModelCache;
import org.nd4j.dsp.model.SdxModelCompiler;
import org.nd4j.dsp.model.SdxQuantizationContract;
import org.nd4j.dsp.model.SdxTargetProfile;
import org.nd4j.dsp.model.SdxTensorG3NnapiCompiler;
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
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Compiles one canonical SDZ for one mobile target and publishes either that complete chat
 * model or a canonical .kproject containing the exact same SDZ.
 *
 * <p>Target products remain internal SDX cache objects. Model output is a runnable .sdz with
 * its tokenizer, tokenizer configuration, chat template, and generation configuration.
 * Project output wraps those same bytes with the project's portable graph and Markdown
 * provenance. Applications never select a vendor model format. Compilation and publication
 * are both fail-closed.</p>
 */
@Service
public class SdxProjectOutputService {
    public static final String OUTPUT_MODEL = "model";
    public static final String OUTPUT_KPROJECT = "kproject";
    public static final String QUANTIZATION_NONE = "none";
    public static final String QUANTIZATION_INT8 = "int8";
    public static final String QUANTIZATION_INT8_PER_TENSOR = "int8-per-tensor";
    public static final String QUANTIZATION_INT8_PER_CHANNEL = "int8-per-channel";

    private static final Pattern SAFE_MODEL_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final List<String> PORTABLE_KNOWLEDGE_ROOTS = List.of(
            "data/graph",
            "data/markdown",
            "data/fact-sheets",
            "data/sources",
            "data/indexed-documents");
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

    /**
     * A model request becomes a target output only when it names a target profile. This keeps
     * the legacy untargeted model-staging and promotion path intact for API clients that still
     * need it, while all mobile UI requests produce a downloadable canonical artifact.
     */
    public static boolean isTargetOutputRequested(DownloadRequest request) {
        if (request == null) {
            return false;
        }
        String outputFormat = normalizeOutputFormat(request.getOutputFormat());
        return OUTPUT_KPROJECT.equals(outputFormat)
                || (OUTPUT_MODEL.equals(outputFormat)
                        && request.getTargetProfile() != null
                        && !request.getTargetProfile().isBlank());
    }

    public Path createProject(
            Path stagingWorkspace,
            Path canonicalSdz,
            DownloadRequest request) throws IOException {
        return createProject(
                stagingWorkspace,
                ConversionArtifact.canonicalSdz(canonicalSdz),
                request,
                StagingCancellation.NONE);
    }

    public Path createProject(
            Path stagingWorkspace,
            ConversionArtifact conversionArtifact,
            DownloadRequest request,
            StagingCancellation cancellation) throws IOException {
        if (!isProjectOutputRequested(request)) {
            throw new IllegalArgumentException("SDX project output was not requested");
        }
        return createOutput(stagingWorkspace, conversionArtifact, request, cancellation);
    }

    public Path createOutput(
            Path stagingWorkspace,
            ConversionArtifact conversionArtifact,
            DownloadRequest request,
            StagingCancellation cancellation) throws IOException {
        Objects.requireNonNull(request, "request");
        StagingCancellation signal = cancellation == null
                ? StagingCancellation.NONE
                : cancellation;
        signal.checkpoint();
        if (!isTargetOutputRequested(request)) {
            throw new IllegalArgumentException(
                    "A targetProfile is required for downloadable mobile SDX output");
        }

        boolean projectOutput = isProjectOutputRequested(request);
        String modelId = requireModelId(request.getModelId());
        SdxTargetProfile target = requireMobileTarget(request.getTargetProfile());
        String quantizationIntent = normalizeQuantization(request.getQuantizationProfile());
        String targetSoc = normalizeTargetSoc(target, request.getTargetSoc());
        String quantization = resolveQuantization(target, targetSoc, quantizationIntent);

        Path workspace = Objects.requireNonNull(stagingWorkspace, "stagingWorkspace")
                .toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        Path source = Objects.requireNonNull(conversionArtifact, "conversionArtifact")
                .requireCanonicalSdz();
        requireRegularFile(source, "Canonical SameDiff model");
        if (!source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sdz")) {
            throw new IOException("Mobile target compilation requires a canonical .sdz model");
        }

        // A full project must fail before target compilation if no synced graph/Markdown
        // source is configured. Model-only output deliberately has no project dependency.
        KompileProjectManifest sourceProject = projectOutput ? loadSourceProject() : null;
        Path operationRoot = workspace.resolve(".sdx-output-" + UUID.randomUUID());
        Path projectRoot = operationRoot.resolve("project");
        Path outputDirectory = workspace.resolve("outputs");
        String extension = projectOutput ? ".kproject" : ".sdz";
        Path output = outputDirectory.resolve(modelId + "-" + target.id() + extension);
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to overwrite staged mobile artifact: " + output);
        }

        Files.createDirectories(operationRoot);
        try {
            signal.checkpoint();
            SdxTextModelStager.PreparedTextAssets textAssets =
                    SdxTextModelStager.prepare(workspace, source, operationRoot);
            signal.checkpoint();
            Path quantizationConfig = QUANTIZATION_INT8.equals(quantizationIntent)
                    ? writeInt8Contract(operationRoot, source, target, targetSoc)
                    : null;

            SdxModelCache cache = new SdxModelCache(cacheRoot());
            SdxModelCompiler.CompileOptions.Builder options =
                    SdxModelCompiler.CompileOptions.builder()
                            .modelId(modelId)
                            .targetSoc(targetSoc)
                            // The compile key must be identical whether the canonical SDZ is
                            // downloaded directly or wrapped in a project archive.
                            .cacheKeyProperty("stagingOutput", "mobile-sdz")
                            .cacheKeyProperty(
                                    "sourceRepository",
                                    firstNonBlank(request.getRepository(), "unspecified"))
                            .cacheKeyProperty(
                                    "sourceRevision",
                                    firstNonBlank(request.getRevision(), "unversioned"))
                            .cacheKeyProperty(
                                    "sourceModelSelection",
                                    firstNonBlank(
                                            request.effectiveSourceAssetProvenance().get(
                                                    TextModelAssetMap.MODEL),
                                            firstNonBlank(
                                                    request.getTextAssets() == null
                                                            ? null
                                                            : request.getTextAssets().getModel(),
                                                    "model")))
                            .tokenizer(textAssets.tokenizer())
                            .tokenizerConfig(textAssets.tokenizerConfig())
                            .textGenerationConfig(textAssets.textGenerationConfig());
            if (quantizationConfig != null) {
                options.quantizationConfig(quantizationConfig);
            }

            SdxCompiledModel compiled;
            try {
                compiled = new SdxModelCompiler(cache).compile(
                        source,
                        target,
                        cancellableCompiler(
                                targetCompiler(target, quantizationIntent, targetSoc), signal),
                        options.build());
            } catch (IOException compileFailure) {
                // SDX converts target-compiler runtime failures to IOException after deleting
                // its private staging directory. Recover the shared cancellation semantic here
                // so the staging lifecycle reports CANCELLED instead of a false build failure.
                signal.checkpoint();
                throw compileFailure;
            }
            signal.checkpoint();
            compiled.requireTextModelAssets();

            Path packagedSdz = operationRoot.resolve("compiled-model.sdz");
            cache.packageCompiledSdz(source, List.of(target), packagedSdz);
            signal.checkpoint();
            if (!projectOutput) {
                return publishPreparedArtifact(packagedSdz, output);
            }

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
            signal.checkpoint();

            Path packagedModel = projectRoot.resolve(model.getPath());
            Files.createDirectories(packagedModel.getParent());
            Files.copy(packagedSdz, packagedModel, StandardCopyOption.COPY_ATTRIBUTES);
            signal.checkpoint();

            Path preparedProject = operationRoot.resolve("compiled-project.kproject");
            archiveService.exportProject(projectRoot, preparedProject);
            signal.checkpoint();
            Path published = publishPreparedArtifact(preparedProject, output);
            signal.checkpoint();
            return published;
        } finally {
            deleteTree(operationRoot);
        }
    }

    private static Path publishPreparedArtifact(Path prepared, Path output) throws IOException {
        Files.createDirectories(output.getParent());
        Path pending = output.resolveSibling(
                "." + UUID.randomUUID() + ".pending-" + output.getFileName());
        try {
            Files.copy(prepared, pending, StandardCopyOption.COPY_ATTRIBUTES);
            AtomicProjectPublisher.publish(pending, output);
            return output;
        } catch (java.nio.file.FileAlreadyExistsException raced) {
            throw new IOException(
                    "Refusing to overwrite staged mobile artifact: " + output,
                    raced);
        } finally {
            Files.deleteIfExists(pending);
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

    SdxModelCompiler.TargetCompiler targetCompiler(
            SdxTargetProfile target,
            String quantizationIntent,
            String targetSoc) throws IOException {
        if (compilerOverride != null) {
            return compilerOverride;
        }
        if (target == SdxTargetProfile.IOS_ARM64_METAL
                && QUANTIZATION_NONE.equals(quantizationIntent)) {
            return SdxModelCompiler.metalDeviceCompilationPolicy(targetSoc);
        }
        if (target == SdxTargetProfile.ANDROID_ARM64_NNAPI_ACCELERATOR) {
            if (QUANTIZATION_NONE.equals(quantizationIntent)) {
                return SdxModelCompiler.nnapiDeviceCompilationPolicy(targetSoc);
            }
            if ("Tensor_G3".equals(targetSoc)
                    && QUANTIZATION_INT8.equals(quantizationIntent)) {
                return new SdxTensorG3NnapiCompiler();
            }
        }
        List<String> command = properties.getCompilerCommand();
        if (command.isEmpty() || command.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IOException(
                    "No SDX target compiler is configured for " + target.id()
                            + " with quantization " + quantizationIntent + ". Set "
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

    private static SdxModelCompiler.TargetCompiler cancellableCompiler(
            SdxModelCompiler.TargetCompiler delegate,
            StagingCancellation cancellation) {
        return new SdxModelCompiler.TargetCompiler() {
            @Override
            public String id() {
                return delegate.id();
            }

            @Override
            public String version() {
                return delegate.version();
            }

            @Override
            public String cacheKeyMaterial(
                    Path sourceModel,
                    SdxTargetProfile target,
                    SdxModelCompiler.CompileOptions options) throws IOException {
                cancellation.checkpoint();
                return delegate.cacheKeyMaterial(sourceModel, target, options);
            }

            @Override
            public Path compile(SdxModelCompiler.CompilationContext context) throws Exception {
                cancellation.checkpoint();
                Path output = delegate.compile(context);
                cancellation.checkpoint();
                return output;
            }
        };
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
        if (request.getSourceReference() != null && !request.getSourceReference().isBlank()) {
            metadata.put("sourceReference", request.getSourceReference());
        }
        if (request.getRequestedRevision() != null && !request.getRequestedRevision().isBlank()) {
            metadata.put("sourceRequestedRevision", request.getRequestedRevision());
        }
        request.effectiveSourceAssetProvenance().forEach(
                (key, value) -> metadata.put("sourceAsset." + key, value));
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
            Path source,
            SdxTargetProfile target,
            String targetSoc) throws IOException {
        Path output = operationRoot.resolve(
                resolveQuantization(target, targetSoc, QUANTIZATION_INT8) + ".json");
        if (target == SdxTargetProfile.ANDROID_ARM64_NNAPI_ACCELERATOR
                && SdxTensorG3NnapiCompiler.TARGET_SOC.equals(targetSoc)) {
            copyEmbeddedQuantizationContract(source, output);
            return output;
        }
        SdxQuantizationContract.writeWeightInt8Profile(output, target, targetSoc);
        return output;
    }

    private static void copyEmbeddedQuantizationContract(Path source, Path output)
            throws IOException {
        try (ZipFile zip = new ZipFile(source.toFile())) {
            ZipEntry entry = zip.getEntry("metadata/quantization.json");
            if (entry == null || entry.isDirectory()) {
                throw new IOException(
                        "Tensor G3 NNAPI staging requires calibrated metadata/quantization.json "
                                + "in the canonical SDZ");
            }
            try (var input = zip.getInputStream(entry)) {
                Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static SdxTargetProfile requireMobileTarget(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "targetProfile is required for downloadable mobile SDX output");
        }
        SdxTargetProfile target = SdxTargetProfile.fromId(value);
        // Resolve through the exact provider registry now so unsupported or ambiguous
        // targets fail before any cache or project state is created.
        target.platformProvider();
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
        if (QUANTIZATION_INT8.equals(normalized)
                || QUANTIZATION_INT8_PER_CHANNEL.equals(normalized)
                || QUANTIZATION_INT8_PER_TENSOR.equals(normalized)) {
            return QUANTIZATION_INT8;
        }
        throw new IllegalArgumentException(
                "Unsupported SDX quantizationProfile: " + value
                        + ". Supported values are none and int8.");
    }

    public static String resolveQuantization(
            SdxTargetProfile target, String targetSoc, String quantizationIntent) {
        Objects.requireNonNull(target, "target");
        String normalized = normalizeQuantization(quantizationIntent);
        if (QUANTIZATION_NONE.equals(normalized)) {
            return QUANTIZATION_NONE;
        }
        if (target == SdxTargetProfile.ANDROID_ARM64_NNAPI_ACCELERATOR
                && SdxTensorG3NnapiCompiler.TARGET_SOC.equals(targetSoc)) {
            return QUANTIZATION_INT8_PER_TENSOR;
        }
        return QUANTIZATION_INT8_PER_CHANNEL;
    }

    public static String normalizeTargetProfile(String value) {
        return requireMobileTarget(value).id();
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
        return Objects.requireNonNull(target, "target")
                .platformProvider()
                .defaultTargetSoc();
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
