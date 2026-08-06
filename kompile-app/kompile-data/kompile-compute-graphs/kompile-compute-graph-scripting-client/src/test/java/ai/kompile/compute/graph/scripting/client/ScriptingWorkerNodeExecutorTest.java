package ai.kompile.compute.graph.scripting.client;

import ai.kompile.cli.common.util.JavaRuntimeLocator;
import ai.kompile.compute.graph.engine.ExecutionContext;
import ai.kompile.compute.graph.model.ComputeGraph;
import ai.kompile.compute.graph.model.ComputeNode;
import ai.kompile.compute.graph.model.ExecutionResult;
import ai.kompile.compute.graph.model.ExecutionStatus;
import ai.kompile.compute.graph.model.NodeExecutionType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScriptingWorkerNodeExecutorTest {

    @Test
    void delegatesBothScriptLanguagesAcrossTheProcessProtocol() throws Exception {
        String testClasses = Path.of(FakeWorkerMain.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
        ScriptingWorkerNodeExecutor executor = new ScriptingWorkerNodeExecutor(
                List.of(JavaRuntimeLocator.javaExecutable(), "-cp", testClasses,
                        FakeWorkerMain.class.getName()),
                Duration.ofSeconds(15));

        assertEquals(java.util.Set.of(NodeExecutionType.JAVASCRIPT, NodeExecutionType.PYTHON),
                executor.supportedTypes());

        ComputeNode node = ComputeNode.builder()
                .id("script-node")
                .name("script-node")
                .executionType(NodeExecutionType.JAVASCRIPT)
                .script("1 + 1")
                .build();
        ComputeGraph graph = ComputeGraph.builder().id("graph").name("graph").build();
        ExecutionContext context = new ExecutionContext("execution", graph, null);

        ExecutionResult result = executor.execute(node, Map.of("value", 21), context);

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertEquals(42, result.getOutputs().get("answer"));
        assertNull(executor.validate(node));
    }

    @Test
    void packagedWorkerExecutesRealJavaScriptAndPythonWhenRequested() throws Exception {
        String configuredJar = System.getProperty(ScriptingWorkerNodeExecutor.WORKER_JAR_PROPERTY);
        org.junit.jupiter.api.Assumptions.assumeTrue(configuredJar != null && !configuredJar.isBlank());
        Path workerJar = Path.of(configuredJar);
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(workerJar));

        ScriptingWorkerNodeExecutor executor = new ScriptingWorkerNodeExecutor(
                JavaRuntimeLocator.javaExecutable(), workerJar, Duration.ofSeconds(90));
        ComputeGraph graph = ComputeGraph.builder().id("graph").name("graph").build();
        ExecutionContext context = new ExecutionContext("packaged-worker", graph, null);

        ComputeNode javaScript = ComputeNode.builder()
                .id("javascript")
                .name("javascript")
                .executionType(NodeExecutionType.JAVASCRIPT)
                .script("_output = {answer: value * 2};")
                .build();
        ExecutionResult javaScriptResult = executor.execute(javaScript, Map.of("value", 21), context);
        assertEquals(ExecutionStatus.COMPLETED, javaScriptResult.getStatus(), javaScriptResult.getError());
        assertEquals(42, javaScriptResult.getOutputs().get("answer"));

        if (pythonAvailable()) {
            ComputeNode python = ComputeNode.builder()
                    .id("python")
                    .name("python")
                    .executionType(NodeExecutionType.PYTHON)
                    .script("_result = value + 5")
                    .build();
            ExecutionResult pythonResult = executor.execute(python, Map.of("value", 37), context);
            assertEquals(ExecutionStatus.COMPLETED, pythonResult.getStatus(), pythonResult.getError());
            assertEquals(42, pythonResult.getOutputs().get("_result"));
        }
    }

    private static boolean pythonAvailable() {
        try {
            Process process = new ProcessBuilder("python3", "--version")
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            return process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** A dependency-free child main so the unit test exercises the real process protocol. */
    public static final class FakeWorkerMain {
        private FakeWorkerMain() {
        }

        public static void main(String[] args) throws Exception {
            System.in.readAllBytes();
            String response = "{\"nodeId\":\"script-node\",\"executionId\":\"execution\","
                    + "\"status\":\"COMPLETED\",\"outputs\":{\"answer\":42},"
                    + "\"error\":null,\"stackTrace\":null,\"consoleOutput\":null,"
                    + "\"durationMillis\":1,\"startedAtEpochMillis\":1,"
                    + "\"completedAtEpochMillis\":2}";
            String encoded = Base64.getEncoder().encodeToString(
                    response.getBytes(StandardCharsets.UTF_8));
            System.out.println(ScriptingWorkerNodeExecutor.RESULT_PREFIX + encoded);
        }
    }
}
