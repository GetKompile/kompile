package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.config.ChatConfig;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KompileLocalServingBootstrapTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesInstalledNativeServingExecutableFirst() throws Exception {
        Path install = tempDir.resolve("dist");
        Path component = install.resolve("components")
                .resolve("kompile-model-serving").resolve("1");
        Files.createDirectories(component);
        Path executable = executable(install.resolve("bin")
                .resolve(servingBinaryName()));

        KompileLocalServingBootstrap.LauncherArtifact resolved =
                KompileLocalServingBootstrap.resolveLauncher(
                        install, component, new Properties(), Map.of());

        assertEquals(executable, resolved.path());
        assertTrue(resolved.nativeExecutable());
    }

    @Test
    void resolvesInstalledFatApplicationJarAsJvmFallback() throws Exception {
        Path install = tempDir.resolve("dist");
        Path component = install.resolve("components")
                .resolve("kompile-model-serving").resolve("1");
        Files.createDirectories(component);
        Path jar = component.resolve("kompile-model-serving-1-exec.jar");
        Files.writeString(jar, "fat-jar");

        KompileLocalServingBootstrap.LauncherArtifact resolved =
                KompileLocalServingBootstrap.resolveLauncher(
                        install, component, new Properties(), Map.of());

        assertEquals(jar.toAbsolutePath(), resolved.path());
        assertFalse(resolved.nativeExecutable());
    }

    @Test
    void nativeParentRejectsInstalledExecutableJarFallback() throws Exception {
        Path install = tempDir.resolve("native-dist");
        Path component = install.resolve("components")
                .resolve("kompile-model-serving").resolve("1");
        Files.createDirectories(component);
        Path jar = component.resolve("kompile-model-serving-1-exec.jar");
        Files.writeString(jar, "fat-jar");

        Exception error = assertThrows(Exception.class,
                () -> KompileLocalServingBootstrap.resolveLauncher(
                        install, component, new Properties(), Map.of(), true));

        assertTrue(error.getMessage().contains("bin/kompile-model-serving"));
        assertTrue(error.getMessage().contains("Refusing to fall back"));
    }

    @Test
    void explicitServingExecutableOverrideWins() throws Exception {
        Path install = tempDir.resolve("dist");
        Path component = install.resolve("components")
                .resolve("kompile-model-serving").resolve("1");
        Files.createDirectories(component);
        executable(install.resolve("bin").resolve(servingBinaryName()));
        Path override = executable(tempDir.resolve("custom-serving"));
        Properties properties = new Properties();
        properties.setProperty(
                KompileLocalServingBootstrap.SERVING_EXECUTABLE_PROPERTY,
                override.toString());

        KompileLocalServingBootstrap.LauncherArtifact resolved =
                KompileLocalServingBootstrap.resolveLauncher(
                        install, component, properties, Map.of());

        assertEquals(override, resolved.path());
        assertTrue(resolved.nativeExecutable());
    }

    @Test
    void nativeAndExecutableJarCommandsUseStandaloneServingEntrypoint() throws Exception {
        Path args = tempDir.resolve("args.json");
        Files.writeString(args, "{}");
        Path nativeApp = executable(tempDir.resolve(servingBinaryName()));
        List<String> nativeCommand = KompileLocalServingBootstrap.buildCommand(
                new KompileLocalServingBootstrap.LauncherArtifact(nativeApp, true),
                args, new Properties());

        assertEquals(List.of(nativeApp.toString(), args.toAbsolutePath().toString()), nativeCommand);
        assertFalse(nativeCommand.contains("-jar"));
        assertFalse(nativeCommand.contains("-cp"));

        Path java = executable(tempDir.resolve(javaBinaryName()));
        Path jar = tempDir.resolve("kompile-model-serving-exec.jar");
        Files.writeString(jar, "jar");
        Properties properties = new Properties();
        properties.setProperty(
                KompileLocalServingBootstrap.JAVA_EXECUTABLE_PROPERTY,
                java.toString());

        List<String> jvmCommand = KompileLocalServingBootstrap.buildCommand(
                new KompileLocalServingBootstrap.LauncherArtifact(jar, false),
                args, properties);

        assertEquals(java.toString(), jvmCommand.get(0));
        assertTrue(jvmCommand.contains("-jar"));
        assertTrue(jvmCommand.contains(jar.toAbsolutePath().toString()));
        assertEquals(args.toAbsolutePath().toString(), jvmCommand.get(jvmCommand.size() - 1));
        assertFalse(jvmCommand.contains("-cp"));
        assertFalse(jvmCommand.contains("--subprocess=serving"));
    }

    @Test
    void writesTheServingSubprocessArgsContract() throws Exception {
        Path model = tempDir.resolve("model.gguf");
        Path tokenizer = tempDir.resolve("tokenizer.json");
        Files.writeString(model, "model");
        Files.writeString(tokenizer, "{}");
        KompileLocalServingBootstrap.ResolvedModel resolvedModel =
                new KompileLocalServingBootstrap.ResolvedModel(
                        "local-model", model, tokenizer);

        Path args = KompileLocalServingBootstrap.writeServingArgs(resolvedModel, 43123);
        try {
            JsonNode json = JsonUtils.standardMapper().readTree(args.toFile());
            assertEquals(43123, json.path("port").asInt());
            assertEquals("127.0.0.1", json.path("host").asText());
            assertEquals("local-model", json.path("modelId").asText());
            assertEquals(model.toAbsolutePath().toString(),
                    json.path("modelPath").asText());
            assertEquals(tokenizer.toAbsolutePath().toString(),
                    json.path("tokenizerPath").asText());
            assertEquals(1024, json.path("maxNewTokens").asInt());
            assertTrue(json.path("temperature").isNull(),
                    "absent temperature must preserve model-family sampling");
            assertTrue(json.path("topK").isNull(),
                    "absent topK must preserve model-family sampling");
            assertTrue(json.has("dspEnabled"));
            assertTrue(json.path("prefixCacheEnabled").isNull());
            assertTrue(json.path("prefixCacheMaxBytes").isNull());
            assertTrue(json.path("prefixCacheBlockSize").isNull());
            assertTrue(json.path("deviceMemoryLimitsBytes").isNull());
        } finally {
            Files.deleteIfExists(args);
        }
    }

    @Test
    void writesExplicitLocalServingRuntimeTuningWithoutInventingDefaults() throws Exception {
        Path model = tempDir.resolve("model.gguf");
        Path tokenizer = tempDir.resolve("tokenizer.json");
        Files.writeString(model, "model");
        Files.writeString(tokenizer, "{}");
        KompileLocalServingBootstrap.ResolvedModel resolvedModel =
                new KompileLocalServingBootstrap.ResolvedModel(
                        "qwen3.5-2b-instruct", model, tokenizer);

        Path args = KompileLocalServingBootstrap.writeServingArgs(
                resolvedModel,
                43123,
                Map.ofEntries(
                        Map.entry("host", "0.0.0.0"),
                        Map.entry("maxNewTokens", 768),
                        Map.entry("temperature", 1.0),
                        Map.entry("topK", 20),
                        Map.entry("dspEnabled", true),
                        Map.entry("optimizerEnabled", false),
                        Map.entry("optimizerFp16", true),
                        Map.entry("memoryThresholdPercent", 81),
                        Map.entry("prefixCacheEnabled", true),
                        Map.entry("prefixCacheMaxBytes", 268_435_456L),
                        Map.entry("prefixCacheBlockSize", 32),
                        Map.entry("deviceMemoryLimitsBytes", List.of(15_032_385_536L, 4_294_967_296L))));
        try {
            JsonNode json = JsonUtils.standardMapper().readTree(args.toFile());
            assertEquals("0.0.0.0", json.path("host").asText());
            assertEquals(768, json.path("maxNewTokens").asInt());
            assertEquals(1.0, json.path("temperature").asDouble());
            assertEquals(20, json.path("topK").asInt());
            assertTrue(json.path("dspEnabled").asBoolean());
            assertFalse(json.path("optimizerEnabled").asBoolean());
            assertTrue(json.path("optimizerFp16").asBoolean());
            assertEquals(81, json.path("memoryThresholdPercent").asInt());
            assertTrue(json.path("prefixCacheEnabled").asBoolean());
            assertEquals(268_435_456L, json.path("prefixCacheMaxBytes").asLong());
            assertEquals(32, json.path("prefixCacheBlockSize").asInt());
            assertEquals(2, json.path("deviceMemoryLimitsBytes").size());
            assertEquals(15_032_385_536L, json.path("deviceMemoryLimitsBytes").get(0).longValue());
            assertEquals(4_294_967_296L, json.path("deviceMemoryLimitsBytes").get(1).longValue());
        } finally {
            Files.deleteIfExists(args);
        }
    }

    @Test
    void rejectsMalformedDeviceCapsBeforeWritingArgs() {
        var model = new KompileLocalServingBootstrap.ResolvedModel(
                "local-model", tempDir.resolve("model.sdz"), null);
        for (Object invalid : List.of(List.of(), List.of(0L), List.of(-1L), List.of(1.5),
                List.of("1024"), List.of(new java.math.BigInteger("9223372036854775808")),
                java.util.Arrays.asList(1024L, null), "1024", 1024L)) {
            assertThrows(IllegalArgumentException.class, () -> KompileLocalServingBootstrap.writeServingArgs(
                    model, 43123, Map.of("deviceMemoryLimitsBytes", invalid)), invalid.toString());
        }
    }

    @Test
    void installedModelAliasPrefersQuantizedGguf() throws Exception {
        Path install = tempDir.resolve("dist");
        Path home = tempDir.resolve("home");
        Path models = home.resolve("models").resolve("chat");
        Files.createDirectories(models);
        Files.writeString(models.resolve(
                "qwen2.5-0.5b-instruct-fp16.gguf"), "fp16");
        Path quantized = models.resolve(
                "qwen2.5-0.5b-instruct-q4_k_m.gguf");
        Files.writeString(quantized, "q4");
        Path tokenizer = home.resolve("models").resolve("tokenizers")
                .resolve("qwen2.5-0.5b").resolve("tokenizer.json");
        Files.createDirectories(tokenizer.getParent());
        Files.writeString(tokenizer, "{}");

        KompileLocalServingBootstrap.ResolvedModel resolved =
                KompileLocalServingBootstrap.resolveModel(
                        "Qwen2.5-0.5B-Instruct", install, home, Map.of());

        assertEquals(quantized.toAbsolutePath(), resolved.modelPath());
        assertEquals(tokenizer.toAbsolutePath(), resolved.tokenizerPath());
    }

    @Test
    void explicitLocalModelUsesSidecarTokenizer() throws Exception {
        Path modelDirectory = tempDir.resolve("model");
        Files.createDirectories(modelDirectory);
        Path model = modelDirectory.resolve("weights.gguf");
        Path tokenizer = modelDirectory.resolve("tokenizer.json");
        Files.writeString(model, "weights");
        Files.writeString(tokenizer, "{}");

        KompileLocalServingBootstrap.ResolvedModel resolved =
                KompileLocalServingBootstrap.resolveLocalModel(
                        modelDirectory, "my-model");

        assertEquals("my-model", resolved.modelId());
        assertEquals(model.toAbsolutePath(), resolved.modelPath());
        assertEquals(tokenizer.toAbsolutePath(), resolved.tokenizerPath());
    }

    @Test
    void explicitStageDirectoryKeepsGeneratedArtifactsAwayFromTheSource() throws Exception {
        Path source = tempDir.resolve("immutable-release/model.gguf");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "weights");
        Path cache = tempDir.resolve("workspace/.kompile/model-cache").toAbsolutePath();

        Path isolated = KompileLocalServingBootstrap.stagedSdzPath(
                source, "int4", Map.of(
                        KompileLocalServingBootstrap.MODEL_STAGE_DIR_ENV, cache.toString()));
        Path defaultPath = KompileLocalServingBootstrap.stagedSdzPath(
                source, "int4", Map.of());

        assertEquals(cache, isolated.getParent());
        assertTrue(Files.isDirectory(cache));
        assertTrue(isolated.getFileName().toString().contains("-int4-"));
        assertEquals(source.getParent(), defaultPath.getParent());
    }

    @Test
    void cachedStageMustMatchTheCurrentConverterFormat() throws Exception {
        Path staged = Files.writeString(tempDir.resolve("model.sdz"), "staged");
        Path report = staged.resolveSibling(staged.getFileName() + ".stage.json");

        Files.writeString(report, "{\"stageFormatVersion\": \"2\"}");
        assertFalse(KompileLocalServingBootstrap.isStagedArtifactValid(staged),
                "a converter-v2 artifact must be restaged by the v3 runtime");

        Files.writeString(report, "{\"stageFormatVersion\": \"3\"}");
        assertTrue(KompileLocalServingBootstrap.isStagedArtifactValid(staged));
    }

    @Test
    void resolvedProjectModelDirectoryUsesTheSameGgufAndTokenizerRules() throws Exception {
        Path modelDirectory = tempDir.resolve("project-model");
        Files.createDirectories(modelDirectory);
        Path model = modelDirectory.resolve("weights.gguf");
        Path tokenizer = modelDirectory.resolve("tokenizer.json");
        Files.writeString(model, "weights");
        Files.writeString(tokenizer, "{}");

        KompileLocalServingBootstrap.ResolvedModel resolved =
                KompileLocalServingBootstrap.resolveProjectModel(
                        "project-model", modelDirectory, null);

        assertEquals("project-model", resolved.modelId());
        assertEquals(model.toAbsolutePath(), resolved.modelPath());
        assertEquals(tokenizer.toAbsolutePath(), resolved.tokenizerPath());
    }

    @Test
    void startupResultInjectsOnlyThePrivateServingEndpoint() throws Exception {
        Path model = tempDir.resolve("model.gguf");
        Files.writeString(model, "model");
        Path app = executable(tempDir.resolve(servingBinaryName()));
        URI endpoint = URI.create("http://127.0.0.1:43123");

        ChatConfig config = new ChatConfig(
                "kompile-local", null,
                KompileLocalServingBootstrap.DEFAULT_MODEL, null);
        KompileLocalServingBootstrap.StartupResult result =
                new KompileLocalServingBootstrap.StartupResult(
                        "model", model, null, endpoint,
                        new KompileLocalServingBootstrap.LauncherArtifact(app, true),
                        null, null, null);

        result.applyTo(config);

        assertEquals("model", config.getModel());
        assertEquals(endpoint.toString(), config.getBaseUrl());
        assertTrue(config.isValid());
    }

    @Test
    void missingConfiguredServingExecutableFailsClearly() {
        Properties properties = new Properties();
        properties.setProperty(
                KompileLocalServingBootstrap.SERVING_EXECUTABLE_PROPERTY,
                tempDir.resolve("missing-serving").toString());

        Exception error = assertThrows(Exception.class,
                () -> KompileLocalServingBootstrap.resolveLauncher(
                        tempDir.resolve("dist"), tempDir.resolve("component"),
                        properties, Map.of()));

        assertTrue(error.getMessage().contains("not an executable file"));
    }

    private Path executable(Path path) throws Exception {
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.writeString(path, "#!/bin/sh\nexit 0\n");
        assertTrue(path.toFile().setExecutable(true));
        return path.toAbsolutePath();
    }

    private static String servingBinaryName() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "kompile-model-serving.exe" : "kompile-model-serving";
    }

    private static String javaBinaryName() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
    }
}
