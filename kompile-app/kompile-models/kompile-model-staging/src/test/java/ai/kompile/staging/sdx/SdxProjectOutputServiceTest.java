/*
 * Copyright 2025 Kompile Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.kompile.staging.sdx;

import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.project.KompileProjectInitRequest;
import ai.kompile.project.KompileProjectManifest;
import ai.kompile.project.KompileProjectStore;
import ai.kompile.project.archive.ProjectArchiveService;
import ai.kompile.staging.config.SdxStagingProperties;
import ai.kompile.staging.conversion.ConversionArtifact;
import ai.kompile.staging.conversion.ConversionResult;
import ai.kompile.staging.conversion.ConversionService;
import ai.kompile.staging.download.DownloadRequest;
import ai.kompile.staging.download.StagingCancellation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.serde.SDZSerializer;
import org.nd4j.dsp.model.SdxCompiledModel;
import org.nd4j.dsp.model.SdxModelCache;
import org.nd4j.dsp.model.SdxModelCompiler;
import org.nd4j.dsp.model.SdxNnapiDevicePolicy;
import org.nd4j.dsp.model.SdxSourceIdentity;
import org.nd4j.dsp.model.SdxTargetProfile;
import org.nd4j.dsp.model.SdxTensorG3NnapiCompiler;
import org.nd4j.ggml.GGMLModelExport;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.factory.Nd4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdxProjectOutputServiceTest {

    @TempDir
    Path temp;

    @Test
    void compilesAndExportsTargetSdzGraphAndMarkdownAsCanonicalProject() throws Exception {
        Path projectRoot = createSourceProject();
        Path workspace = temp.resolve("staging");
        Files.createDirectories(workspace);
        Path sourceSdz = workspace.resolve("model.sdz");
        writeSdz(sourceSdz);
        writeCompleteTextAssets(workspace);

        SdxStagingProperties properties = new SdxStagingProperties();
        properties.setCacheDir(temp.resolve("sdx-cache"));
        SdxProjectOutputService service = new SdxProjectOutputService(
                temp.resolve("models"),
                projectRoot,
                properties,
                fakeNnapiCompiler());

        DownloadRequest request = DownloadRequest.builder()
                .source("huggingface")
                .repository("Qwen/Qwen2.5-0.5B-Instruct")
                .revision("main")
                .modelId("qwen-mobile")
                .modelType(ModelType.LLM_GGML)
                .format("sdz")
                .outputFormat("kproject")
                .targetProfile("android-arm64-nnapi-accelerator")
                .quantizationProfile("int8")
                .targetSoc("Tensor_G3")
                .build();

        Path archive = service.createProject(workspace, sourceSdz, request);

        assertTrue(Files.isRegularFile(archive));
        assertTrue(archive.getFileName().toString().endsWith(".kproject"));
        try (var children = Files.list(workspace)) {
            assertFalse(children.anyMatch(path ->
                    path.getFileName().toString().startsWith(".sdx-output-")));
        }

        Path imported = temp.resolve("imported-project");
        new ProjectArchiveService().importProject(archive, imported);

        Path targetSdz = imported.resolve(
                "data/models/android-arm64-nnapi-accelerator/model.sdz");
        assertTrue(Files.isRegularFile(targetSdz));
        assertTrue(Files.isRegularFile(imported.resolve("data/graph/project.kgraph")));
        assertEquals(
                "# Offline facts\n",
                Files.readString(imported.resolve("data/markdown/facts.md")));

        KompileProjectManifest manifest = new KompileProjectStore().load(imported);
        assertEquals("source-project", manifest.getProjectId());
        assertEquals(1, manifest.getModels().size());
        assertEquals(
                "android-arm64-nnapi-accelerator",
                manifest.getModels().get(0).getMetadata().get("sdxTargetProfile"));
        assertEquals(
                "int8-per-tensor",
                manifest.getModels().get(0).getMetadata().get("quantization"));
        assertNotNull(manifest.getModels().get(0).getMetadata().get("sdxCompileKey"));

        SdxCompiledModel resolved = new SdxModelCache(temp.resolve("runtime-cache")).resolve(
                targetSdz,
                SdxTargetProfile.ANDROID_ARM64_NNAPI_ACCELERATOR);
        assertTrue(Files.isDirectory(resolved.runtimeModelPath()));
        assertTrue(resolved.quantizationConfigPath().isPresent());
        var textAssets = resolved.requireTextModelAssets();
        assertTrue(Files.isRegularFile(textAssets.tokenizer()));
        assertTrue(Files.isRegularFile(textAssets.tokenizerConfig()));
        assertTrue(Files.isRegularFile(textAssets.textGenerationConfig()));
        String normalizedTokenizerConfig = Files.readString(textAssets.tokenizerConfig());
        assertTrue(normalizedTokenizerConfig.contains("\"chat_template\" : \""));
        assertFalse(normalizedTokenizerConfig.contains("\"name\" : \"default\""));
    }

    @Test
    void publishesCompleteTargetSdzWithoutConfiguredProject() throws Exception {
        Path workspace = temp.resolve("model-only-staging");
        Files.createDirectories(workspace);
        Path sourceSdz = workspace.resolve("model.sdz");
        writeSdz(sourceSdz);
        writeCompleteTextAssets(workspace);

        SdxStagingProperties properties = new SdxStagingProperties();
        properties.setCacheDir(temp.resolve("model-only-cache"));
        SdxProjectOutputService service = new SdxProjectOutputService(
                temp.resolve("models"),
                null,
                properties,
                fakeNnapiCompiler());
        DownloadRequest request = DownloadRequest.builder()
                .source("huggingface")
                .repository("Qwen/Qwen2.5-0.5B-Instruct")
                .revision("main")
                .modelId("qwen-model-only")
                .modelType(ModelType.LLM_GGML)
                .format("sdz")
                .outputFormat("model")
                .targetProfile("android-arm64-nnapi-accelerator")
                .quantizationProfile("int8")
                .targetSoc("Tensor_G3")
                .build();

        Path model = service.createOutput(
                workspace,
                ConversionArtifact.canonicalSdz(sourceSdz),
                request,
                StagingCancellation.NONE);

        assertEquals(
                "qwen-model-only-android-arm64-nnapi-accelerator.sdz",
                model.getFileName().toString());
        assertTrue(SdxProjectOutputService.isTargetOutputRequested(request));
        assertFalse(SdxProjectOutputService.isProjectOutputRequested(request));
        SdxCompiledModel resolved = new SdxModelCache(temp.resolve("model-only-runtime"))
                .resolve(model, SdxTargetProfile.ANDROID_ARM64_NNAPI_ACCELERATOR);
        var textAssets = resolved.requireTextModelAssets();
        assertTrue(Files.isRegularFile(textAssets.tokenizer()));
        assertTrue(Files.readString(textAssets.tokenizerConfig()).contains("chat_template"));
        assertTrue(Files.isRegularFile(textAssets.textGenerationConfig()));
    }

    @Test
    void modelAndProjectOutputsReuseOneContentAddressedCompile() throws Exception {
        Path projectRoot = createSourceProject();
        Path workspace = temp.resolve("shared-output-staging");
        Files.createDirectories(workspace);
        Path sourceSdz = workspace.resolve("model.sdz");
        writeSdz(sourceSdz);
        writeCompleteTextAssets(workspace);

        AtomicInteger compileCalls = new AtomicInteger();
        SdxModelCompiler.TargetCompiler delegate = fakeNnapiCompiler();
        SdxModelCompiler.TargetCompiler countingCompiler = new SdxModelCompiler.TargetCompiler() {
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
                return delegate.cacheKeyMaterial(sourceModel, target, options);
            }

            @Override
            public Path compile(SdxModelCompiler.CompilationContext context) throws Exception {
                compileCalls.incrementAndGet();
                return delegate.compile(context);
            }
        };
        SdxStagingProperties properties = new SdxStagingProperties();
        properties.setCacheDir(temp.resolve("shared-output-cache"));
        SdxProjectOutputService service = new SdxProjectOutputService(
                temp.resolve("models"), projectRoot, properties, countingCompiler);

        DownloadRequest.DownloadRequestBuilder base = DownloadRequest.builder()
                .source("huggingface")
                .repository("Qwen/Qwen2.5-0.5B-Instruct")
                .revision("main")
                .modelId("shared-mobile")
                .modelType(ModelType.LLM_GGML)
                .format("sdz")
                .targetProfile("android-arm64-nnapi-accelerator")
                .quantizationProfile("int8")
                .targetSoc("Tensor_G3");
        Path model = service.createOutput(
                workspace,
                ConversionArtifact.canonicalSdz(sourceSdz),
                base.outputFormat("model").build(),
                StagingCancellation.NONE);
        Path project = service.createProject(
                workspace,
                ConversionArtifact.canonicalSdz(sourceSdz),
                base.outputFormat("kproject").build(),
                StagingCancellation.NONE);

        assertEquals(1, compileCalls.get());
        SdxCompiledModel direct = new SdxModelCache(temp.resolve("direct-runtime"))
                .resolve(model, SdxTargetProfile.ANDROID_ARM64_NNAPI_ACCELERATOR);
        Path imported = temp.resolve("shared-output-import");
        new ProjectArchiveService().importProject(project, imported);
        SdxCompiledModel embedded = new SdxModelCache(temp.resolve("embedded-runtime"))
                .resolve(
                        imported.resolve(
                                "data/models/android-arm64-nnapi-accelerator/model.sdz"),
                        SdxTargetProfile.ANDROID_ARM64_NNAPI_ACCELERATOR);
        assertEquals(direct.compileKey(), embedded.compileKey());
        var directAssets = direct.requireTextModelAssets();
        var embeddedAssets = embedded.requireTextModelAssets();
        assertArrayEquals(
                Files.readAllBytes(directAssets.tokenizer()),
                Files.readAllBytes(embeddedAssets.tokenizer()));
    }

    @Test
    void convertsRealGgufAndPublishesRunnableProjectWithTokenizerConfiguration()
            throws Exception {
        Path projectRoot = createSourceProject();
        Path workspace = temp.resolve("real-gguf-staging");
        Files.createDirectories(workspace);
        Path gguf = workspace.resolve("tiny-llama.gguf");
        GGMLModelExport.exportModel(createSmallLlamaModel(), gguf.toFile());
        writeCompleteTextAssets(workspace);

        Path canonicalSdz = workspace.resolve("model.sdz");
        ConversionResult conversion =
                new ConversionService().convert(gguf, canonicalSdz, "gguf");
        assertTrue(
                conversion.isSuccess(),
                () -> "Real GGUF conversion failed: " + conversion.getErrorMessage());
        assertEquals(canonicalSdz.toAbsolutePath(), conversion.getArtifact().canonicalPath());
        SameDiff converted = SDZSerializer.load(canonicalSdz.toFile(), true);
        assertNotNull(converted);
        assertTrue(converted.variables().size() > 0);

        SdxStagingProperties properties = new SdxStagingProperties();
        properties.setCacheDir(temp.resolve("real-gguf-cache"));
        SdxProjectOutputService service = new SdxProjectOutputService(
                temp.resolve("models"), projectRoot, properties, null);
        DownloadRequest request = DownloadRequest.builder()
                .source("local")
                .repository("opaque-test-handle")
                .modelId("tiny-gguf-mobile")
                .modelType(ModelType.LLM_GGML)
                .format("gguf")
                .outputFormat("kproject")
                .targetProfile("android-arm64-nnapi-accelerator")
                .quantizationProfile("none")
                .targetSoc("Tensor_G3")
                .build();

        Path archive = service.createProject(
                workspace,
                conversion.getArtifact(),
                request,
                StagingCancellation.NONE);
        assertTrue(Files.isRegularFile(archive));

        Path imported = temp.resolve("real-gguf-project");
        new ProjectArchiveService().importProject(archive, imported);
        Path targetSdz = imported.resolve(
                "data/models/android-arm64-nnapi-accelerator/model.sdz");
        assertTrue(Files.isRegularFile(targetSdz));
        SdxCompiledModel resolved = new SdxModelCache(
                temp.resolve("real-gguf-runtime-cache")).resolve(
                        targetSdz,
                        SdxTargetProfile.ANDROID_ARM64_NNAPI_ACCELERATOR);
        var textAssets = resolved.requireTextModelAssets();
        assertTrue(Files.isRegularFile(textAssets.tokenizer()));
        assertTrue(Files.isRegularFile(textAssets.tokenizerConfig()));
        assertTrue(Files.readString(textAssets.tokenizerConfig())
                .contains("\"chat_template\""));
        assertTrue(Files.isRegularFile(textAssets.textGenerationConfig()));
        assertTrue(Files.isRegularFile(
                imported.resolve("data/graph/project.kgraph")));
    }

    @Test
    void rejectsSdzWhenTokenizerConfigurationIsMissing() throws Exception {
        Path projectRoot = createSourceProject();
        Path workspace = temp.resolve("missing-tokenizer-config");
        Files.createDirectories(workspace);
        Path sourceSdz = workspace.resolve("model.sdz");
        writeSdz(sourceSdz);
        writeTokenizer(workspace);
        writeTextGenerationContract(workspace);

        SdxProjectOutputService service = new SdxProjectOutputService(
                temp.resolve("models"), projectRoot, new SdxStagingProperties(), fakeNnapiCompiler());
        DownloadRequest request = DownloadRequest.builder()
                .source("local")
                .repository(sourceSdz.toString())
                .modelId("incomplete-manual-import")
                .modelType(ModelType.LLM_GGML)
                .format("sdz")
                .outputFormat("kproject")
                .targetProfile("android-arm64-vulkan")
                .quantizationProfile("none")
                .build();

        IOException failure = assertThrows(
                IOException.class,
                () -> service.createProject(workspace, sourceSdz, request));

        assertTrue(failure.getMessage().contains("tokenizer_config.json"));
        assertTrue(failure.getMessage().contains("chat_template"));
    }

    @Test
    void doesNotTreatHuggingFaceGenerationConfigAsSdxGraphContract() throws Exception {
        Path projectRoot = createSourceProject();
        Path workspace = temp.resolve("hf-metadata-is-not-sdx-contract");
        Files.createDirectories(workspace);
        Path sourceSdz = workspace.resolve("model.sdz");
        writeSdz(sourceSdz);
        writeTokenizer(workspace);
        writeTokenizerConfig(workspace);
        Files.writeString(
                workspace.resolve("config.json"),
                "{\"max_position_embeddings\":128,\"eos_token_id\":2}\n");
        Files.writeString(
                workspace.resolve("generation_config.json"),
                "{\"max_new_tokens\":16,\"eos_token_id\":2}\n");

        SdxProjectOutputService service = new SdxProjectOutputService(
                temp.resolve("models"), projectRoot, new SdxStagingProperties(), fakeNnapiCompiler());
        DownloadRequest request = DownloadRequest.builder()
                .source("huggingface")
                .repository("example/incomplete-export")
                .modelId("metadata-only")
                .modelType(ModelType.LLM_GGML)
                .format("sdz")
                .outputFormat("kproject")
                .targetProfile("android-arm64-vulkan")
                .quantizationProfile("none")
                .build();

        IOException failure = assertThrows(
                IOException.class,
                () -> service.createProject(workspace, sourceSdz, request));

        assertTrue(failure.getMessage().contains("could not inspect the canonical SDZ"));
        assertTrue(failure.getMessage().contains("text-generation.json"));
    }

    @Test
    void cancellationDuringTargetCompilationPublishesNeitherCacheObjectNorProject()
            throws Exception {
        Path projectRoot = createSourceProject();
        Path workspace = temp.resolve("cancelled-compilation");
        Files.createDirectories(workspace);
        Path sourceSdz = workspace.resolve("model.sdz");
        writeSdz(sourceSdz);
        writeCompleteTextAssets(workspace);

        CountDownLatch compileEntered = new CountDownLatch(1);
        CountDownLatch releaseCompiler = new CountDownLatch(1);
        SdxModelCompiler.TargetCompiler delegate = fakeNnapiCompiler();
        SdxModelCompiler.TargetCompiler blockingCompiler =
                new SdxModelCompiler.TargetCompiler() {
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
                            SdxModelCompiler.CompileOptions options)
                            throws IOException {
                        return delegate.cacheKeyMaterial(sourceModel, target, options);
                    }

                    @Override
                    public Path compile(SdxModelCompiler.CompilationContext context)
                            throws Exception {
                        compileEntered.countDown();
                        if (!releaseCompiler.await(10, TimeUnit.SECONDS)) {
                            throw new IOException("Timed out waiting to release test compiler");
                        }
                        return delegate.compile(context);
                    }
                };

        Path cacheDir = temp.resolve("cancelled-cache");
        SdxStagingProperties properties = new SdxStagingProperties();
        properties.setCacheDir(cacheDir);
        SdxProjectOutputService service = new SdxProjectOutputService(
                temp.resolve("models"),
                projectRoot,
                properties,
                blockingCompiler);
        DownloadRequest request = DownloadRequest.builder()
                .source("local")
                .repository("opaque-cancel-handle")
                .modelId("cancelled-mobile")
                .modelType(ModelType.LLM_GGML)
                .format("sdz")
                .outputFormat("kproject")
                .targetProfile("android-arm64-nnapi-accelerator")
                .quantizationProfile("none")
                .targetSoc("Tensor_G3")
                .build();
        AtomicBoolean cancelled = new AtomicBoolean();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Path> result = worker.submit(() -> service.createProject(
                    workspace,
                    ConversionArtifact.canonicalSdz(sourceSdz),
                    request,
                    cancelled::get));
            assertTrue(compileEntered.await(10, TimeUnit.SECONDS));
            cancelled.set(true);
            releaseCompiler.countDown();

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> result.get(15, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof CancellationException);
            assertFalse(Files.exists(workspace.resolve(
                    "outputs/cancelled-mobile-android-arm64-nnapi-accelerator.kproject")));
            assertFalse(Files.exists(cacheDir.resolve("v1/objects")));
            Path cacheTmp = cacheDir.resolve("v1/tmp");
            if (Files.isDirectory(cacheTmp)) {
                try (var files = Files.list(cacheTmp)) {
                    assertFalse(files.findAny().isPresent());
                }
            }
            try (var files = Files.list(workspace)) {
                assertFalse(files.anyMatch(path ->
                        path.getFileName().toString().startsWith(".sdx-project-")));
            }
        } finally {
            cancelled.set(true);
            releaseCompiler.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void failsClosedWhenNoTargetCompilerIsConfigured() throws Exception {
        Path projectRoot = createSourceProject();
        Path workspace = temp.resolve("unconfigured-staging");
        Files.createDirectories(workspace);
        Path sourceSdz = workspace.resolve("model.sdz");
        writeSdz(sourceSdz);
        writeCompleteTextAssets(workspace);

        SdxProjectOutputService service = new SdxProjectOutputService(
                temp.resolve("models"),
                projectRoot,
                new SdxStagingProperties(),
                null);
        DownloadRequest request = DownloadRequest.builder()
                .source("http")
                .repository("https://example.invalid/model.sdz")
                .modelId("unconfigured")
                .modelType(ModelType.LLM_GGML)
                .format("sdz")
                .outputFormat("kproject")
                .targetProfile("android-arm64-vulkan")
                .quantizationProfile("none")
                .build();

        IOException failure = assertThrows(
                IOException.class,
                () -> service.createProject(workspace, sourceSdz, request));

        assertTrue(failure.getMessage().contains("No SDX target compiler is configured"));
        assertFalse(Files.exists(workspace.resolve(
                "outputs/unconfigured-android-arm64-vulkan.kproject")));
    }

    @Test
    void stagesUnquantizedPixel8aProjectWithBuiltInDeviceCompilationPolicy()
            throws Exception {
        Path projectRoot = createSourceProject();
        Path workspace = temp.resolve("pixel-staging");
        Files.createDirectories(workspace);
        Path sourceSdz = workspace.resolve("pixel-model.sdz");
        writeSdz(sourceSdz);
        writeCompleteTextAssets(workspace);

        SdxStagingProperties properties = new SdxStagingProperties();
        properties.setCacheDir(temp.resolve("pixel-sdx-cache"));
        SdxProjectOutputService service = new SdxProjectOutputService(
                temp.resolve("models"), projectRoot, properties, null);
        DownloadRequest request = DownloadRequest.builder()
                .source("huggingface")
                .repository("Qwen/Qwen2.5-0.5B-Instruct")
                .revision("main")
                .modelId("pixel-fp16")
                .modelType(ModelType.LLM_GGML)
                .format("sdz")
                .outputFormat("kproject")
                .targetProfile("android-arm64-nnapi-accelerator")
                .quantizationProfile("none")
                .targetSoc("Tensor_G3")
                .build();

        Path archive = service.createProject(workspace, sourceSdz, request);
        Path imported = temp.resolve("pixel-imported-project");
        new ProjectArchiveService().importProject(archive, imported);
        Path targetSdz = imported.resolve(
                "data/models/android-arm64-nnapi-accelerator/model.sdz");
        SdxCompiledModel resolved = new SdxModelCache(
                temp.resolve("pixel-runtime-cache")).resolve(
                        targetSdz,
                        SdxTargetProfile.ANDROID_ARM64_NNAPI_ACCELERATOR);

        SdxNnapiDevicePolicy policy = SdxNnapiDevicePolicy.load(
                resolved.runtimeModelPath()
                        .resolve("artifacts/nnapi/accelerator-only.json"));
        assertEquals(SdxNnapiDevicePolicy.POLICY_ABI, policy.policyAbi());
        assertEquals("android-arm64-nnapi-accelerator", policy.target());
        assertEquals("Tensor_G3", policy.targetSoc());
        assertEquals(resolved.sourceIdentity().sha256(), policy.sourceSha256());
        assertEquals(null, policy.derivedModelSha256());
        assertEquals("DEVICE_ACCELERATOR", policy.deviceType());
        assertTrue(policy.requireWholeGraph());
        assertFalse(policy.allowCpu());
        assertFalse(policy.allowGpu());
        assertFalse(policy.allowFallback());
        assertEquals("android-nnapi-device", policy.compilationLocation());
        assertTrue(policy.persistentCache());
        assertFalse(resolved.quantizationConfigPath().isPresent());
        resolved.requireTextModelAssets();

        KompileProjectManifest manifest = new KompileProjectStore().load(imported);
        assertEquals(
                "sdx-nnapi-device-policy",
                manifest.getModels().get(0).getMetadata().get("sdxCompilerId"));
        assertEquals(
                "none",
                manifest.getModels().get(0).getMetadata().get("quantization"));

        byte[] published = Files.readAllBytes(archive);
        IOException duplicate = assertThrows(
                IOException.class,
                () -> service.createProject(workspace, sourceSdz, request));
        assertTrue(duplicate.getMessage().contains("Refusing to overwrite"));
        assertArrayEquals(published, Files.readAllBytes(archive));
        try (var outputs = Files.list(archive.getParent())) {
            assertFalse(outputs.anyMatch(path ->
                    path.getFileName().toString().contains(".pending-")));
        }
    }

    @Test
    void selectsInProcessTensorG3CompilerWithoutExternalConfiguration() throws Exception {
        SdxProjectOutputService service = new SdxProjectOutputService(
                temp.resolve("models"),
                createSourceProject(),
                new SdxStagingProperties(),
                null);

        SdxModelCompiler.TargetCompiler compiler = service.targetCompiler(
                SdxTargetProfile.ANDROID_ARM64_NNAPI_ACCELERATOR,
                SdxProjectOutputService.QUANTIZATION_INT8,
                "Tensor_G3");

        assertTrue(compiler instanceof SdxTensorG3NnapiCompiler);
        assertEquals(SdxTensorG3NnapiCompiler.COMPILER_ID, compiler.id());
    }

    @Test
    void resolvesCanonicalInt8SchemeByTarget() {
        assertEquals(
                SdxProjectOutputService.QUANTIZATION_INT8,
                SdxProjectOutputService.normalizeQuantization("int8-per-channel"));
        assertEquals(
                SdxProjectOutputService.QUANTIZATION_INT8_PER_TENSOR,
                SdxProjectOutputService.resolveQuantization(
                        SdxTargetProfile.ANDROID_ARM64_NNAPI_ACCELERATOR,
                        "Tensor_G3",
                        "int8"));
        assertEquals(
                SdxProjectOutputService.QUANTIZATION_INT8_PER_CHANNEL,
                SdxProjectOutputService.resolveQuantization(
                        SdxTargetProfile.ANDROID_ARM64_GOOGLE_TENSOR_G5,
                        "Tensor_G5",
                        "int8"));
    }

    @Test
    void selectsProductionMlxDeviceCompilationPolicyWithoutExternalCompiler() throws Exception {
        SdxProjectOutputService service = new SdxProjectOutputService(
                temp.resolve("models"),
                createSourceProject(),
                new SdxStagingProperties(),
                null);

        SdxModelCompiler.TargetCompiler compiler = service.targetCompiler(
                SdxTargetProfile.IOS_ARM64_METAL,
                SdxProjectOutputService.QUANTIZATION_NONE,
                "Apple_ARM64_MLX_Metal");

        assertEquals("sdx-mlx-device-compilation", compiler.id());
    }

    @Test
    void otherQuantizedTargetsStillRequireExternalCompilerConfiguration() throws Exception {
        SdxProjectOutputService service = new SdxProjectOutputService(
                temp.resolve("models"),
                createSourceProject(),
                new SdxStagingProperties(),
                null);

        IOException failure = assertThrows(
                IOException.class,
                () -> service.targetCompiler(
                        SdxTargetProfile.ANDROID_ARM64_VULKAN,
                        SdxProjectOutputService.QUANTIZATION_INT8,
                        "Android_Vulkan_1_1"));

        assertTrue(failure.getMessage().contains("No SDX target compiler is configured"));
    }

    @Test
    void concurrentPublicationHasOneWinnerAndNeverChangesWinnerBytes() throws Exception {
        Path outputs = temp.resolve("concurrent-outputs");
        Files.createDirectories(outputs);
        Path destination = outputs.resolve("mobile.kproject");
        byte[] firstBytes = "first-complete-project".getBytes(StandardCharsets.UTF_8);
        byte[] secondBytes = "second-complete-project".getBytes(StandardCharsets.UTF_8);
        Path first = Files.write(outputs.resolve(".first.pending-mobile.kproject"), firstBytes);
        Path second = Files.write(outputs.resolve(".second.pending-mobile.kproject"), secondBytes);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> firstResult = workers.submit(
                    () -> publishAfterSignal(first, destination, ready, start));
            Future<Boolean> secondResult = workers.submit(
                    () -> publishAfterSignal(second, destination, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int winners = (firstResult.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (secondResult.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, winners);
            byte[] winner = Files.readAllBytes(destination);
            assertTrue(Arrays.equals(firstBytes, winner) || Arrays.equals(secondBytes, winner));

            Path late = Files.write(
                    outputs.resolve(".late.pending-mobile.kproject"),
                    "late-project".getBytes(StandardCharsets.UTF_8));
            assertThrows(
                    FileAlreadyExistsException.class,
                    () -> AtomicProjectPublisher.publish(late, destination));
            assertArrayEquals(winner, Files.readAllBytes(destination));
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void separateJvmPublishersExerciseInterProcessLockAndPreserveWinner()
            throws Exception {
        Path outputs = temp.resolve("interprocess-outputs");
        Files.createDirectories(outputs);
        Path destination = outputs.resolve("mobile.kproject");
        byte[] firstBytes = "first-process-project".getBytes(StandardCharsets.UTF_8);
        byte[] secondBytes = "second-process-project".getBytes(StandardCharsets.UTF_8);
        Path first = Files.write(outputs.resolve(".first.process.kproject"), firstBytes);
        Path second = Files.write(outputs.resolve(".second.process.kproject"), secondBytes);
        Path firstReady = outputs.resolve("first.ready");
        Path secondReady = outputs.resolve("second.ready");
        Path start = outputs.resolve("publish.start");

        Process firstProcess = startPublisherProcess(
                first, destination, firstReady, start);
        Process secondProcess = startPublisherProcess(
                second, destination, secondReady, start);
        try {
            awaitPublisherReadiness(
                    firstProcess, secondProcess, firstReady, secondReady);
            Files.writeString(start, "start\n", StandardOpenOption.CREATE_NEW);
            assertTrue(firstProcess.waitFor(15, TimeUnit.SECONDS));
            assertTrue(secondProcess.waitFor(15, TimeUnit.SECONDS));

            int firstExit = firstProcess.exitValue();
            int secondExit = secondProcess.exitValue();
            String diagnostics = "first=" + firstExit + ":"
                    + processOutput(firstProcess) + "; second=" + secondExit + ":"
                    + processOutput(secondProcess);
            assertTrue(
                    (firstExit == 0
                            && secondExit
                                    == AtomicProjectPublisherProcessMain.ALREADY_EXISTS_EXIT)
                            || (secondExit == 0
                                    && firstExit
                                            == AtomicProjectPublisherProcessMain.ALREADY_EXISTS_EXIT),
                    diagnostics);

            byte[] winner = Files.readAllBytes(destination);
            assertTrue(Arrays.equals(firstBytes, winner) || Arrays.equals(secondBytes, winner));
            assertTrue(Files.exists(first) ^ Files.exists(second));

            Path late = Files.write(
                    outputs.resolve(".late.process.kproject"),
                    "late-process-project".getBytes(StandardCharsets.UTF_8));
            assertThrows(
                    FileAlreadyExistsException.class,
                    () -> AtomicProjectPublisher.publish(late, destination));
            assertArrayEquals(winner, Files.readAllBytes(destination));
        } finally {
            firstProcess.destroyForcibly();
            secondProcess.destroyForcibly();
        }
    }

    @Test
    void publisherBlocksUntilSeparateJvmReleasesExactDestinationLock()
            throws Exception {
        Path procLocks = Path.of("/proc/locks");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                "Linux".equals(System.getProperty("os.name"))
                        && Files.isReadable(procLocks),
                "Kernel lock-wait verification requires Linux /proc/locks");
        Path outputs = temp.resolve("held-interprocess-lock");
        Files.createDirectories(outputs);
        Path destination = outputs.resolve("mobile.kproject");
        byte[] expected = "lock-blocked-project".getBytes(StandardCharsets.UTF_8);
        Path source = Files.write(outputs.resolve(".blocked.process.kproject"), expected);
        Path holderReady = outputs.resolve("holder.ready");
        Path release = outputs.resolve("holder.release");
        Path publisherReady = outputs.resolve("publisher.ready");
        Path start = outputs.resolve("publisher.start");

        Process holder = startLockHolderProcess(destination, holderReady, release);
        Process publisher = null;
        try {
            awaitProcessReady(holder, holderReady, "lock holder");
            publisher = startPublisherProcess(
                    source, destination, publisherReady, start);
            awaitProcessReady(publisher, publisherReady, "publisher");
            Files.writeString(start, "start\n", StandardOpenOption.CREATE_NEW);

            Path lockPath = outputs.resolve(AtomicProjectPublisher.LOCK_DIRECTORY)
                    .resolve(destination.getFileName() + ".lock");
            awaitBlockedKernelLock(procLocks, lockPath, publisher);
            assertTrue(publisher.isAlive());
            assertFalse(Files.exists(destination));

            Files.writeString(release, "release\n", StandardOpenOption.CREATE_NEW);
            assertTrue(holder.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, holder.exitValue(), processOutput(holder));
            assertTrue(publisher.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, publisher.exitValue(), processOutput(publisher));
            assertArrayEquals(expected, Files.readAllBytes(destination));
        } finally {
            try {
                Files.writeString(
                        release,
                        "release\n",
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException ignored) {
                // Best-effort release before forcing a failed child process down.
            }
            holder.destroyForcibly();
            if (publisher != null) {
                publisher.destroyForcibly();
            }
        }
    }

    @Test
    void acceptsMetalAndCoreMlAneAsDistinctExactPlatformTargets() {
        assertEquals(
                "ios-arm64-metal",
                SdxProjectOutputService.normalizeTargetProfile("ios-metal"));
        assertEquals(
                "Apple_ARM64_MLX_Metal",
                SdxProjectOutputService.normalizeTargetSoc(
                        SdxTargetProfile.IOS_ARM64_METAL, ""));

        assertEquals(
                "ios-arm64-coreml-ane",
                SdxProjectOutputService.normalizeTargetProfile("coreml-ane"));
        assertEquals(
                "Apple_ARM64_ANE",
                SdxProjectOutputService.normalizeTargetSoc(
                        SdxTargetProfile.IOS_ARM64_COREML_ANE, null));

        assertFalse(SdxTargetProfile.IOS_ARM64_METAL.platformProvider().allowsCpuFallback());
        assertTrue(SdxTargetProfile.IOS_ARM64_COREML_ANE
                .platformProvider()
                .allowsCpuFallback());
        assertFalse(SdxTargetProfile.IOS_ARM64_METAL
                .platformProvider()
                .providerId()
                .equals(SdxTargetProfile.IOS_ARM64_COREML_ANE
                        .platformProvider()
                        .providerId()));
    }

    private static Process startPublisherProcess(
            Path source,
            Path destination,
            Path ready,
            Path start) throws IOException {
        return startJavaProcess(
                AtomicProjectPublisherProcessMain.class,
                source.toString(),
                destination.toString(),
                ready.toString(),
                start.toString());
    }

    private static Process startLockHolderProcess(
            Path destination,
            Path ready,
            Path release) throws IOException {
        return startJavaProcess(
                AtomicProjectPublisherLockHolderMain.class,
                destination.toString(),
                ready.toString(),
                release.toString());
    }

    private static Process startJavaProcess(Class<?> mainClass, String... arguments)
            throws IOException {
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) {
            classpath = System.getProperty("java.class.path");
        }
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>(4 + arguments.length);
        command.add(java.toString());
        command.add("-cp");
        command.add(classpath);
        command.add(mainClass.getName());
        command.addAll(List.of(arguments));
        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
    }

    private static void awaitProcessReady(
            Process process, Path marker, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.isRegularFile(marker)
                && System.nanoTime() < deadline
                && process.isAlive()) {
            Thread.sleep(5L);
        }
        assertTrue(
                Files.isRegularFile(marker),
                description + " process did not reach its barrier");
    }

    private static void awaitBlockedKernelLock(
            Path procLocks, Path lockPath, Process publisher) throws Exception {
        long inode = ((Number) Files.getAttribute(
                lockPath, "unix:ino", LinkOption.NOFOLLOW_LINKS)).longValue();
        String pidToken = " " + publisher.pid() + " ";
        String inodeToken = ":" + inode + " ";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean blocked = false;
        while (!blocked && publisher.isAlive() && System.nanoTime() < deadline) {
            for (String line : Files.readAllLines(procLocks, StandardCharsets.UTF_8)) {
                String normalized = line.trim().replaceAll("\\s+", " ");
                if (normalized.contains("->")
                        && normalized.contains(pidToken)
                        && normalized.contains(inodeToken)) {
                    blocked = true;
                    break;
                }
            }
            if (!blocked) {
                Thread.sleep(5L);
            }
        }
        assertTrue(
                blocked,
                "Kernel lock table did not report the publisher waiting on the exact lock");
    }

    private static void awaitPublisherReadiness(
            Process first,
            Process second,
            Path firstReady,
            Path secondReady) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while ((!Files.isRegularFile(firstReady) || !Files.isRegularFile(secondReady))
                && System.nanoTime() < deadline
                && first.isAlive()
                && second.isAlive()) {
            Thread.sleep(5L);
        }
        assertTrue(
                Files.isRegularFile(firstReady) && Files.isRegularFile(secondReady),
                "Separate publisher JVMs did not both reach the publication barrier");
    }

    private static String processOutput(Process process) throws IOException {
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static boolean publishAfterSignal(
            Path source,
            Path destination,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IOException("Timed out waiting to begin concurrent publication");
        }
        try {
            AtomicProjectPublisher.publish(source, destination);
            return true;
        } catch (FileAlreadyExistsException expected) {
            return false;
        }
    }

    private Path createSourceProject() throws Exception {
        Path root = temp.resolve("source-project");
        KompileProjectInitRequest init = new KompileProjectInitRequest();
        init.setName("Offline Research");
        init.setDescription("Fact-sheet snapshot for mobile chat");
        init.setIncludeStandardComponents(false);
        KompileProjectStore store = new KompileProjectStore();
        KompileProjectManifest manifest = store.init(root, init);
        manifest.setProjectId("source-project");
        store.save(root, manifest);

        Files.createDirectories(root.resolve("data/markdown"));
        Files.writeString(root.resolve("data/markdown/facts.md"), "# Offline facts\n");
        Files.createDirectories(root.resolve("data/fact-sheets"));
        Files.writeString(
                root.resolve("data/fact-sheets/project.json"),
                "{\"name\":\"Offline Research\"}\n");
        Files.createDirectories(root.resolve("data/graph"));
        new UnifiedGraph()
                .addEntity("fact:1", "Fact", "Offline fact")
                .save(root.resolve("data/graph/project.kgraph"));
        return root;
    }

    private static SdxModelCompiler.TargetCompiler fakeNnapiCompiler() {
        return new SdxModelCompiler.TargetCompiler() {
            @Override
            public String id() {
                return "test-nnapi-aot";
            }

            @Override
            public String version() {
                return "1";
            }

            @Override
            public String cacheKeyMaterial(
                    Path sourceModel,
                    SdxTargetProfile target,
                    SdxModelCompiler.CompileOptions options) {
                return "test-tensor-g3";
            }

            @Override
            public Path compile(SdxModelCompiler.CompilationContext context)
                    throws Exception {
                writeSdz(
                        context.suggestedModelOutput(),
                        "quantized-same-diff-fixture");
                SdxSourceIdentity derived = SdxSourceIdentity.identify(
                        context.suggestedModelOutput());
                SdxNnapiDevicePolicy.create(
                        context.target(),
                        context.options().targetSoc(),
                        context.sourceIdentity(),
                        derived).write(context.suggestedOutput());
                return context.suggestedOutput();
            }
        };
    }

    private static SameDiff createSmallLlamaModel() {
        SameDiff model = SameDiff.create();
        int vocabSize = 32;
        int hiddenSize = 16;
        int intermediateSize = 32;
        model.var(
                "model.embed_tokens.weight",
                Nd4j.rand(DataType.FLOAT, vocabSize, hiddenSize));
        model.var(
                "model.layers.0.self_attn.q_proj.weight",
                Nd4j.rand(DataType.FLOAT, hiddenSize, hiddenSize));
        model.var(
                "model.layers.0.self_attn.k_proj.weight",
                Nd4j.rand(DataType.FLOAT, hiddenSize, hiddenSize));
        model.var(
                "model.layers.0.self_attn.v_proj.weight",
                Nd4j.rand(DataType.FLOAT, hiddenSize, hiddenSize));
        model.var(
                "model.layers.0.self_attn.o_proj.weight",
                Nd4j.rand(DataType.FLOAT, hiddenSize, hiddenSize));
        model.var(
                "model.layers.0.mlp.gate_proj.weight",
                Nd4j.rand(DataType.FLOAT, intermediateSize, hiddenSize));
        model.var(
                "model.layers.0.mlp.up_proj.weight",
                Nd4j.rand(DataType.FLOAT, intermediateSize, hiddenSize));
        model.var(
                "model.layers.0.mlp.down_proj.weight",
                Nd4j.rand(DataType.FLOAT, hiddenSize, intermediateSize));
        model.var(
                "model.layers.0.input_layernorm.weight",
                Nd4j.rand(DataType.FLOAT, hiddenSize));
        model.var(
                "model.layers.0.post_attention_layernorm.weight",
                Nd4j.rand(DataType.FLOAT, hiddenSize));
        model.var("model.norm.weight", Nd4j.rand(DataType.FLOAT, hiddenSize));
        model.var(
                "lm_head.weight",
                Nd4j.rand(DataType.FLOAT, vocabSize, hiddenSize));
        return model;
    }

    private static void writeCompleteTextAssets(Path workspace) throws IOException {
        writeTokenizer(workspace);
        writeTokenizerConfig(workspace);
        writeTextGenerationContract(workspace);
    }

    private static void writeTokenizer(Path workspace) throws IOException {
        Files.writeString(
                workspace.resolve("tokenizer.json"),
                """
                {
                  "version": "1.0",
                  "truncation": null,
                  "padding": null,
                  "added_tokens": [
                    {"id": 0, "content": "<unk>", "single_word": false, "lstrip": false, "rstrip": false, "normalized": false, "special": true},
                    {"id": 1, "content": "<|im_start|>", "single_word": false, "lstrip": false, "rstrip": false, "normalized": false, "special": true},
                    {"id": 2, "content": "<|im_end|>", "single_word": false, "lstrip": false, "rstrip": false, "normalized": false, "special": true}
                  ],
                  "normalizer": null,
                  "pre_tokenizer": {"type": "Whitespace"},
                  "post_processor": null,
                  "decoder": null,
                  "model": {
                    "type": "WordLevel",
                    "vocab": {"<unk>": 0, "<|im_start|>": 1, "<|im_end|>": 2, "hello": 3},
                    "unk_token": "<unk>"
                  }
                }
                """);
    }

    private static void writeTokenizerConfig(Path workspace) throws IOException {
        Files.writeString(
                workspace.resolve("tokenizer_config.json"),
                """
                {
                  "model_max_length": 128,
                  "bos_token": "<|im_start|>",
                  "eos_token": "<|im_end|>",
                  "pad_token": "<|im_end|>",
                  "chat_template": [
                    {"name": "tool_use", "template": "unused"},
                    {
                      "name": "default",
                      "template": "{% for message in messages %}<|im_start|>{{ message['role'] }}\\n{{ message['content'] }}<|im_end|>\\n{% endfor %}{% if add_generation_prompt %}<|im_start|>assistant\\n{% endif %}"
                    }
                  ]
                }
                """);
    }

    private static void writeTextGenerationContract(Path workspace) throws IOException {
        Files.writeString(
                workspace.resolve("text-generation.json"),
                """
                {
                  "formatVersion": 1,
                  "profile": "causal-lm-in-graph-kv-v1",
                  "io": {
                    "inputIds": "input_ids",
                    "causalMask": "attention_mask",
                    "positionOffset": "position_offset",
                    "cachePosition": "cache_position",
                    "actualSequenceLength": "actual_sequence_length",
                    "logits": "logits",
                    "kvKeyInputs": ["past_key_values.0.key"],
                    "kvValueInputs": ["past_key_values.0.value"],
                    "prefillKeyOutputs": ["present.0.key"],
                    "prefillValueOutputs": ["present.0.value"]
                  },
                  "execution": {
                    "kvLayout": "BSHD",
                    "kvDtype": "FLOAT16",
                    "maskDtype": "FLOAT16",
                    "planOwnsKvScatter": true
                  },
                  "tokens": {
                    "bosId": 1,
                    "padId": 2,
                    "eosIds": [2]
                  },
                  "limits": {
                    "contextLength": 128,
                    "maxPrefillLength": 64,
                    "maxBatchSize": 1
                  },
                  "samplingDefaults": {
                    "maxNewTokens": 16,
                    "minNewTokens": 0,
                    "temperature": 0.0,
                    "topK": 0,
                    "topP": 1.0,
                    "repetitionPenalty": 1.0,
                    "seed": 0
                  }
                }
                """);
    }

    private static String tensorG3QuantizationContract() {
        return "{"
                + "\"formatVersion\":1,\"scheme\":\"int8-per-tensor\","
                + "\"provider\":\"sdx-graph\",\"targetSocs\":[\"Tensor_G3\"],"
                + "\"deviceOnly\":true,\"allowFloatFallback\":false,"
                + "\"requireVendorAot\":true,"
                + "\"weights\":{\"dtype\":\"INT8\",\"scaleDtype\":\"FLOAT32\","
                + "\"granularity\":\"per-tensor\",\"scale\":0.015625,"
                + "\"symmetric\":true,\"zeroPoint\":0},"
                + "\"activations\":{\"dtype\":\"INT8\",\"scaleDtype\":\"FLOAT32\","
                + "\"granularity\":\"per-tensor\",\"scale\":0.03125,\"zeroPoint\":0,"
                + "\"calibration\":{\"method\":\"minmax\",\"sampleCount\":32,"
                + "\"datasetSha256\":\"" + "a".repeat(64) + "\"}},"
                + "\"outputs\":{\"dtype\":\"INT8\",\"scaleDtype\":\"FLOAT32\","
                + "\"granularity\":\"per-tensor\",\"scale\":0.0625,\"zeroPoint\":0},"
                + "\"excludedOps\":[]}";
    }

    private static void writeSdz(Path output) throws IOException {
        writeSdz(output, "same-diff-fixture");
    }

    private static void writeSdz(Path output, String graphFixture) throws IOException {
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
            ZipEntry entry = new ZipEntry("graph/model.fb");
            entry.setTime(0L);
            zip.putNextEntry(entry);
            zip.write(graphFixture.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            ZipEntry quantization = new ZipEntry("metadata/quantization.json");
            quantization.setTime(0L);
            zip.putNextEntry(quantization);
            zip.write(tensorG3QuantizationContract().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
