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
import ai.kompile.staging.download.DownloadRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nd4j.dsp.model.SdxCompiledModel;
import org.nd4j.dsp.model.SdxModelCache;
import org.nd4j.dsp.model.SdxModelCompiler;
import org.nd4j.dsp.model.SdxNnapiDevicePolicy;
import org.nd4j.dsp.model.SdxSourceIdentity;
import org.nd4j.dsp.model.SdxTargetProfile;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
                .quantizationProfile("int8-per-channel")
                .targetSoc("Tensor_G3")
                .build();

        Path archive = service.createProject(workspace, sourceSdz, request);

        assertTrue(Files.isRegularFile(archive));
        assertTrue(archive.getFileName().toString().endsWith(".kproject"));
        try (var children = Files.list(workspace)) {
            assertFalse(children.anyMatch(path ->
                    path.getFileName().toString().startsWith(".sdx-project-")));
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
                "int8-per-channel",
                manifest.getModels().get(0).getMetadata().get("quantization"));
        assertNotNull(manifest.getModels().get(0).getMetadata().get("sdxCompileKey"));

        SdxCompiledModel resolved = new SdxModelCache(temp.resolve("runtime-cache")).resolve(
                targetSdz,
                SdxTargetProfile.ANDROID_ARM64_NNAPI_ACCELERATOR);
        assertTrue(Files.isDirectory(resolved.runtimeModelPath()));
        assertTrue(resolved.quantizationConfigPath().isPresent());
    }

    @Test
    void failsClosedWhenNoTargetCompilerIsConfigured() throws Exception {
        Path projectRoot = createSourceProject();
        Path workspace = temp.resolve("unconfigured-staging");
        Files.createDirectories(workspace);
        Path sourceSdz = workspace.resolve("model.sdz");
        writeSdz(sourceSdz);

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
    void quantizedNnapiRequiresConfiguredCompilerAndIdentity() throws Exception {
        Path projectRoot = createSourceProject();
        Path workspace = temp.resolve("quantized-fail-closed");
        Files.createDirectories(workspace);
        Path sourceSdz = workspace.resolve("model.sdz");
        writeSdz(sourceSdz);
        DownloadRequest request = DownloadRequest.builder()
                .source("huggingface")
                .repository("Qwen/Qwen2.5-0.5B-Instruct")
                .modelId("pixel-int8")
                .modelType(ModelType.LLM_GGML)
                .format("sdz")
                .outputFormat("kproject")
                .targetProfile("android-arm64-nnapi-accelerator")
                .quantizationProfile("int8-per-channel")
                .targetSoc("Tensor_G3")
                .build();

        SdxProjectOutputService unconfigured = new SdxProjectOutputService(
                temp.resolve("models"),
                projectRoot,
                new SdxStagingProperties(),
                null);
        IOException noCompiler = assertThrows(
                IOException.class,
                () -> unconfigured.createProject(workspace, sourceSdz, request));
        assertTrue(noCompiler.getMessage().contains(
                "No SDX target compiler is configured"));

        SdxStagingProperties missingIdentity = new SdxStagingProperties();
        missingIdentity.setCompilerCommand(List.of("missing-sdx-compiler"));
        SdxProjectOutputService incomplete = new SdxProjectOutputService(
                temp.resolve("models"), projectRoot, missingIdentity, null);
        IOException noIdentity = assertThrows(
                IOException.class,
                () -> incomplete.createProject(workspace, sourceSdz, request));
        assertTrue(noIdentity.getMessage().contains("kompile.staging.sdx.compiler-id"));
        assertFalse(Files.exists(workspace.resolve(
                "outputs/pixel-int8-android-arm64-nnapi-accelerator.kproject")));
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
        }
    }
}
