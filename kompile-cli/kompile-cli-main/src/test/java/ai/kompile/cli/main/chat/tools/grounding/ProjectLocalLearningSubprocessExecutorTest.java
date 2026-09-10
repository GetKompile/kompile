/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.common.util.JavaRuntimeLocator;
import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.lifecycle.IncrementalGraphPslInference;
import ai.kompile.graph.reasoning.lifecycle.UnifiedGraphKgeLifecycle;
import ai.kompile.graph.reasoning.lifecycle.UnifiedGraphReasoningLifecycle;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectLocalLearningSubprocessExecutorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void resolvesExplicitBackendJarOverride() {
        Path jar = tempDir.resolve("nd4j-native-1.0.0-SNAPSHOT.jar");
        try {
            Files.writeString(jar, "placeholder");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        List<Path> resolved = withSystemProperty("kompile.local.learning.backend.jars",
                jar.toString(), ProjectLocalLearningSubprocessExecutor::resolveBackendJars);
        assertEquals(List.of(jar), resolved);
    }

    private static List<Path> withSystemProperty(
            String key, String value, java.util.function.Supplier<List<Path>> action) {
        String previous = System.getProperty(key);
        System.setProperty(key, value);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    @Test
    void exchangesWholeGraphWithAnIsolatedChildProcess() throws Exception {
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        ProjectLocalLearningSubprocessExecutor.CommandFactory commandFactory = args ->
                new ProjectLocalLearningSubprocessExecutor.LaunchSpec(
                        List.of(JavaRuntimeLocator.javaExecutable(), "-cp", classpath,
                                FakeLearningChild.class.getName(), args.toString()),
                        Map.of(), "fake-learning-child");
        ProjectLocalLearningSubprocessExecutor executor =
                new ProjectLocalLearningSubprocessExecutor(
                        MAPPER, commandFactory, true, false, 30_000L, 10_000L);
        UnifiedGraph graph = graph();

        ProjectLocalLearningSubprocessExecutor.Result result = executor.learn(
                graph, plan(), tempDir, "crawl-test");

        assertEquals("SUBPROCESS", result.execution());
        assertNotNull(result.childPid());
        assertNotEquals(ProcessHandle.current().pid(), result.childPid());
        assertEquals("SUBPROCESS", result.graph().meta().get("learning.execution"));
        assertTrue(Files.isRegularFile(result.logPath()));
        assertEquals(2, result.graph().entityCount());
        assertEquals(1, result.graph().relationCount());
    }

    @Test
    void explicitDevelopmentFallbackUsesTheSamePortableLearners() throws Exception {
        ProjectLocalLearningSubprocessExecutor executor =
                new ProjectLocalLearningSubprocessExecutor(MAPPER,
                        ignored -> { throw new AssertionError("child must not launch"); },
                        false, true, 30_000L, 10_000L);

        ProjectLocalLearningSubprocessExecutor.Result result = executor.learn(
                graph(), plan(), tempDir, "crawl-inline");

        assertEquals("IN_PROCESS_FALLBACK", result.execution());
        assertNotNull(result.graph().vectorLayer(UnifiedGraphKgeLifecycle.ENTITY_LAYER));
        assertNotNull(result.graph().artifactText(UnifiedGraphKgeLifecycle.MODEL_ARTIFACT));
    }

    @Test
    void commandConstructionSupportsJarAndNativeWithoutForcingAnUnavailableBackend()
            throws Exception {
        Path args = tempDir.resolve("args.json");
        Path jar = fakeBootJar("kompile-server.jar", "dependency.jar");
        ProjectLocalLearningSubprocessExecutor.LaunchSpec jarLaunch =
                ProjectLocalLearningSubprocessExecutor.buildLaunchSpec(jar, false, args, 512);
        assertTrue(jarLaunch.command().contains("-cp"));
        assertTrue(jarLaunch.command().contains("ai.kompile.app.MainApplication"));
        assertFalse(jarLaunch.command().contains("-jar"));
        assertTrue(jarLaunch.command().contains("--subprocess=learning"));
        assertFalse(jarLaunch.command().stream().anyMatch(flag ->
                flag.startsWith("-Dorg.nd4j.cpu.priority=")
                        || flag.startsWith("-Dorg.nd4j.gpu.priority=")));

        Path cudaOnlyJar = fakeBootJar("cuda-only.jar", "nd4j-native-api-1.0.jar");
        ProjectLocalLearningSubprocessExecutor.LaunchSpec cudaOnlyLaunch =
                ProjectLocalLearningSubprocessExecutor.buildLaunchSpec(
                        cudaOnlyJar, false, args, 512);
        assertFalse(cudaOnlyLaunch.command().stream().anyMatch(flag ->
                flag.startsWith("-Dorg.nd4j.cpu.priority=")));

        Path cpuJar = fakeBootJar("cpu.jar", "nd4j-native-1.0.jar");
        ProjectLocalLearningSubprocessExecutor.LaunchSpec cpuLaunch =
                ProjectLocalLearningSubprocessExecutor.buildLaunchSpec(cpuJar, false, args, 512);
        assertTrue(cpuLaunch.command().contains("-Dorg.nd4j.cpu.priority=1000"));

        Path nativeBinary = tempDir.resolve("kompile-server");
        ProjectLocalLearningSubprocessExecutor.LaunchSpec nativeLaunch =
                ProjectLocalLearningSubprocessExecutor.buildLaunchSpec(
                        nativeBinary, true, args, 512);
        assertEquals(nativeBinary.toString(), nativeLaunch.command().get(0));
        assertFalse(nativeLaunch.command().contains("-jar"));
        assertTrue(nativeLaunch.command().contains("--subprocess=learning"));
    }

    private Path fakeBootJar(String jarName, String dependencyName) throws Exception {
        Path jar = tempDir.resolve(jarName);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("BOOT-INF/classes/marker.txt"));
            output.write("marker".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new JarEntry("BOOT-INF/lib/" + dependencyName));
            output.write(new byte[]{0});
            output.closeEntry();
        }
        return jar;
    }

    @Test
    void rejectsAChildThatChangesTopologyBehindStableIds() {
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        ProjectLocalLearningSubprocessExecutor executor =
                new ProjectLocalLearningSubprocessExecutor(MAPPER, args ->
                        new ProjectLocalLearningSubprocessExecutor.LaunchSpec(
                                List.of(JavaRuntimeLocator.javaExecutable(), "-cp", classpath,
                                        MutatingLearningChild.class.getName(), args.toString()),
                                Map.of(), "mutating-learning-child"),
                        true, false, 30_000L, 10_000L);

        assertThrows(java.io.IOException.class,
                () -> executor.learn(graph(), plan(), tempDir, "crawl-mutating"));
    }

    @Test
    void localLaunchSpecBuildsABareJvmCommand() throws Exception {
        Path args = tempDir.resolve("local-args.json");
        ProjectLocalLearningSubprocessExecutor.LaunchSpec launch =
                ProjectLocalLearningSubprocessExecutor.localLaunchSpec(args);

        List<String> command = launch.command();
        assertEquals(JavaRuntimeLocator.javaExecutable(), command.get(0));
        assertTrue(command.contains("-cp"));
        int cpIdx = command.indexOf("-cp");
        assertEquals(ProjectLocalLearningSubprocessExecutor.LOCAL_CHILD_MAIN,
                command.get(cpIdx + 2));
        assertEquals(args.toAbsolutePath().normalize().toString(), command.get(cpIdx + 3));
        assertFalse(command.contains("--subprocess=learning"));
    }

    @Test
    void defaultConstructorSelectsTheBareClasspathChildInJvmMode() throws Exception {
        // In-JVM CLI (the MCP-initiated local case) must get the bare child automatically —
        // no launch-mode property, no injected factory. The surefire pom disables the
        // subprocess for hermetic e2e tests, so this test opts back in explicitly.
        System.setProperty("kompile.local.learning.subprocess.enabled", "true");
        System.clearProperty("kompile.local.learning.inlineFallback");
        try {
            ProjectLocalLearningSubprocessExecutor executor =
                    new ProjectLocalLearningSubprocessExecutor(MAPPER);
            ProjectLocalLearningSubprocessExecutor.Plan fullPlan =
                    new ProjectLocalLearningSubprocessExecutor.Plan(
                            new UnifiedGraphKgeLifecycle.Config(true, "TRANSE", 4, 1, 0.05, 1, 1L),
                            new UnifiedGraphReasoningLifecycle.Config(true, 1, 1, 1, 0.35, 25),
                            "LOCAL_DEFAULT");

            ProjectLocalLearningSubprocessExecutor.Result result =
                    executor.learn(graph(), fullPlan, tempDir, "local-default-learning");

            assertEquals("SUBPROCESS", result.execution());
            assertNotEquals(ProcessHandle.current().pid(), result.childPid());
            assertNotNull(result.graph().vectorLayer(UnifiedGraphKgeLifecycle.ENTITY_LAYER));
            assertNotNull(result.graph().artifactText(
                    UnifiedGraphReasoningLifecycle.PSL_WEIGHTS_ARTIFACT));
            assertNotNull(result.graph().artifactText(
                    UnifiedGraphReasoningLifecycle.MEBN_THEORY_JSON_ARTIFACT));
        } finally {
            System.setProperty("kompile.local.learning.subprocess.enabled", "false");
            System.setProperty("kompile.local.learning.inlineFallback", "true");
        }
    }

    @Test
    void localChildLaunchRunsTheRealChildMainEndToEnd() throws Exception {
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        ProjectLocalLearningSubprocessExecutor executor =
                new ProjectLocalLearningSubprocessExecutor(MAPPER, args ->
                        new ProjectLocalLearningSubprocessExecutor.LaunchSpec(
                                List.of(JavaRuntimeLocator.javaExecutable(), "-cp", classpath,
                                        ProjectLocalLearningSubprocessExecutor.LOCAL_CHILD_MAIN,
                                        args.toString()),
                                Map.of(), "local-real-child"),
                        true, false, 120_000L, 60_000L);
        ProjectLocalLearningSubprocessExecutor.Plan fullPlan =
                new ProjectLocalLearningSubprocessExecutor.Plan(
                        new UnifiedGraphKgeLifecycle.Config(true, "TRANSE", 4, 1, 0.05, 1, 1L),
                        new UnifiedGraphReasoningLifecycle.Config(true, 1, 1, 1, 0.35, 25),
                        "LOCAL_REAL_CHILD");

        ProjectLocalLearningSubprocessExecutor.Result result =
                executor.learn(graph(), fullPlan, tempDir, "local-real-learning");

        assertEquals("SUBPROCESS", result.execution());
        assertNotEquals(ProcessHandle.current().pid(), result.childPid());
        assertNotNull(result.graph().vectorLayer(UnifiedGraphKgeLifecycle.ENTITY_LAYER));
        assertNotNull(result.graph().artifactText(
                UnifiedGraphReasoningLifecycle.PSL_WEIGHTS_ARTIFACT));
        assertNotNull(result.graph().artifactText(
                UnifiedGraphReasoningLifecycle.MEBN_THEORY_JSON_ARTIFACT));
        assertNotNull(result.graph().artifact(IncrementalGraphPslInference.CACHE_ARTIFACT));
        assertEquals("COMPLETED",
                result.graph().meta().get(IncrementalGraphPslInference.META_STATUS));
        assertEquals("SUBPROCESS", result.graph().meta().get("learning.execution"));
    }

    @Test
    void structuredIdsPreserveCommaAndLegacyIdsDeduplicate() {
        UnifiedGraph structured = graph()
                .meta(ProjectLocalLearningSubprocessExecutor.PROJECTED_ENTITY_IDS_META,
                        List.of("entity,with,comma", "entity,with,comma"));
        assertEquals(Set.of("entity,with,comma"),
                ProjectLocalLearningSubprocessExecutor.learningProjectedEntityIds(structured));

        UnifiedGraph legacy = graph().meta(
                ProjectLocalLearningSubprocessExecutor.PROJECTED_ENTITY_IDS_META, "alice,alice");
        assertEquals(Set.of("alice"),
                ProjectLocalLearningSubprocessExecutor.learningProjectedEntityIds(legacy));
    }

    @Test
    void malformedProjectedIdsFailClosedAndCannotBypassOpinionGuard() {
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        ProjectLocalLearningSubprocessExecutor executor =
                new ProjectLocalLearningSubprocessExecutor(MAPPER, args ->
                        new ProjectLocalLearningSubprocessExecutor.LaunchSpec(
                                List.of(JavaRuntimeLocator.javaExecutable(), "-cp", classpath,
                                        MalformedProjectedIdsLearningChild.class.getName(), args.toString()),
                                Map.of(), "malformed-projected-ids-learning-child"),
                        true, false, 30_000L, 10_000L);

        UnifiedGraph before = graph().putEntityOpinion("alice", Opinion.fromSoftTruth(0.9));
        java.io.IOException failure = assertThrows(java.io.IOException.class,
                () -> executor.learn(before, plan(), tempDir, "crawl-malformed-projected-ids"));
        assertEquals("Learning subprocess changed non-learning entity opinion alice",
                failure.getMessage());
    }

    @Test
    void rejectsUnrelatedMetadataWhenReasoningIsDisabled() {
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        ProjectLocalLearningSubprocessExecutor executor =
                new ProjectLocalLearningSubprocessExecutor(MAPPER, args ->
                        new ProjectLocalLearningSubprocessExecutor.LaunchSpec(
                                List.of(JavaRuntimeLocator.javaExecutable(), "-cp", classpath,
                                        MetadataMutatingLearningChild.class.getName(), args.toString()),
                                Map.of(), "metadata-mutating-learning-child"),
                        true, false, 30_000L, 10_000L);

        java.io.IOException failure = assertThrows(java.io.IOException.class,
                () -> executor.learn(graph(), plan(), tempDir, "crawl-metadata-mutating"));
        assertEquals("Learning subprocess changed unrelated graph metadata", failure.getMessage());
    }

    @Test
    @EnabledIfSystemProperty(named = "kompile.test.learning.jar", matches = ".+")
    void realExecutableServerRunsPortableKgePslAndMebnInItsChildProcess() throws Exception {
        Path serverJar = Path.of(System.getProperty("kompile.test.learning.jar"))
                .toAbsolutePath().normalize();
        ProjectLocalLearningSubprocessExecutor executor =
                new ProjectLocalLearningSubprocessExecutor(MAPPER, args ->
                        ProjectLocalLearningSubprocessExecutor.buildLaunchSpec(
                                serverJar, false, args, 2048),
                        true, false, 120_000L, 60_000L);
        ProjectLocalLearningSubprocessExecutor.Plan fullPlan =
                new ProjectLocalLearningSubprocessExecutor.Plan(
                        new UnifiedGraphKgeLifecycle.Config(
                                true, "TRANSE", 4, 1, 0.05, 1, 1L),
                        new UnifiedGraphReasoningLifecycle.Config(
                                true, 1, 1, 1, 0.35, 25),
                        "REAL_CHILD");

        ProjectLocalLearningSubprocessExecutor.Result result = executor.learn(
                graph(), fullPlan, tempDir, "real-learning-child");

        assertEquals("SUBPROCESS", result.execution());
        assertNotEquals(ProcessHandle.current().pid(), result.childPid());
        assertNotNull(result.graph().vectorLayer(UnifiedGraphKgeLifecycle.ENTITY_LAYER));
        assertNotNull(result.graph().artifactText(
                UnifiedGraphReasoningLifecycle.PSL_WEIGHTS_ARTIFACT));
        assertNotNull(result.graph().artifactText(
                UnifiedGraphReasoningLifecycle.MEBN_THEORY_JSON_ARTIFACT));
    }

    private ProjectLocalLearningSubprocessExecutor.Plan plan() {
        return new ProjectLocalLearningSubprocessExecutor.Plan(
                new UnifiedGraphKgeLifecycle.Config(true, "TRANSE", 4, 1, 0.05, 1, 1L),
                new UnifiedGraphReasoningLifecycle.Config(false, 1, 1, 1, 0.35, 25),
                "PRE_RESOLUTION");
    }

    private UnifiedGraph graph() {
        return new UnifiedGraph()
                .graphId("executor-test")
                .factSheetId(42L)
                .addEntity("alice", "PERSON", "Alice")
                .addEntity("acme", "COMPANY", "Acme")
                .addRelation("r1", "alice", "acme", "WORKS_AT", 0.9)
                .putEntityVector("semantic", "alice", new double[]{0.2, 0.8});
    }

    public static final class FakeLearningChild {
        private FakeLearningChild() {
        }

        public static void main(String[] args) throws Exception {
            JsonNode request = MAPPER.readTree(Path.of(args[0]).toFile());
            Path input = Path.of(request.path("inputGraphPath").asText());
            Path output = Path.of(request.path("outputGraphPath").asText());
            UnifiedGraph graph = UnifiedGraph.load(input);
            if (request.path("embeddingEnabled").asBoolean()) {
                UnifiedGraphKgeLifecycle.learn(graph, new UnifiedGraphKgeLifecycle.Config(
                        true,
                        request.path("embeddingAlgorithm").asText("TRANSE"),
                        request.path("embeddingDim").asInt(4),
                        request.path("embeddingEpochs").asInt(1),
                        request.path("embeddingLearningRate").asDouble(0.05),
                        request.path("embeddingWarmStartEpochs").asInt(1),
                        request.path("embeddingSeed").asLong(1L)));
            }
            graph.save(output);
            long pid = ProcessHandle.current().pid();
            System.out.println(ProjectLocalLearningSubprocessExecutor.MESSAGE_PREFIX
                    + "{\"type\":\"HEARTBEAT\",\"timestampMs\":" + System.currentTimeMillis() + "}");
            System.out.println(ProjectLocalLearningSubprocessExecutor.MESSAGE_PREFIX
                    + "{\"type\":\"COMPLETED\",\"finalLoss\":0.0,\"outputPath\":\""
                    + output.toString().replace("\\", "\\\\")
                    + "\",\"entities\":2,\"relations\":1,\"pid\":" + pid + "}");
        }
    }

    public static final class MutatingLearningChild {
        private MutatingLearningChild() {
        }

        public static void main(String[] args) throws Exception {
            JsonNode request = MAPPER.readTree(Path.of(args[0]).toFile());
            Path input = Path.of(request.path("inputGraphPath").asText());
            Path output = Path.of(request.path("outputGraphPath").asText());
            UnifiedGraph graph = UnifiedGraph.load(input);
            graph.removeRelationById("r1")
                    .addRelation("r1", "alice", "acme", "MUTATED", 0.9)
                    .save(output);
            System.out.println(ProjectLocalLearningSubprocessExecutor.MESSAGE_PREFIX
                    + "{\"type\":\"COMPLETED\",\"finalLoss\":0.0,\"outputPath\":\""
                    + output.toString().replace("\\", "\\\\")
                    + "\",\"entities\":2,\"relations\":1}");
        }
    }

    public static final class MetadataMutatingLearningChild {
        private MetadataMutatingLearningChild() {
        }

        public static void main(String[] args) throws Exception {
            JsonNode request = MAPPER.readTree(Path.of(args[0]).toFile());
            Path input = Path.of(request.path("inputGraphPath").asText());
            Path output = Path.of(request.path("outputGraphPath").asText());
            UnifiedGraph graph = UnifiedGraph.load(input);
            graph.meta("unrelated.metadata", "mutated").save(output);
            System.out.println(ProjectLocalLearningSubprocessExecutor.MESSAGE_PREFIX
                    + "{\"type\":\"COMPLETED\",\"finalLoss\":0.0,\"outputPath\":\""
                    + output.toString().replace("\\", "\\\\")
                    + "\",\"entities\":2,\"relations\":1}");
        }
    }

    public static final class MalformedProjectedIdsLearningChild {
        private MalformedProjectedIdsLearningChild() {
        }

        public static void main(String[] args) throws Exception {
            JsonNode request = MAPPER.readTree(Path.of(args[0]).toFile());
            Path input = Path.of(request.path("inputGraphPath").asText());
            Path output = Path.of(request.path("outputGraphPath").asText());
            UnifiedGraph graph = UnifiedGraph.load(input);
            graph.putEntityOpinion("alice", Opinion.fromSoftTruth(0.1))
                    .meta(ProjectLocalLearningSubprocessExecutor.PROJECTED_ENTITY_IDS_META,
                            List.of(123L))
                    .save(output);
            System.out.println(ProjectLocalLearningSubprocessExecutor.MESSAGE_PREFIX
                    + "{\"type\":\"COMPLETED\",\"finalLoss\":0.0,\"outputPath\":\""
                    + output.toString().replace("\\", "\\\\")
                    + "\",\"entities\":2,\"relations\":1}");
        }
    }
}
