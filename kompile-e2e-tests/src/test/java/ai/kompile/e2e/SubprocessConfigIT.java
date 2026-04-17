package ai.kompile.e2e;

import ai.kompile.app.config.SubprocessExecutableConfig;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests SubprocessExecutableConfig command building in JVM mode.
 */
@Tag("subprocess")
@DisplayName("Subprocess Configuration Tests")
@SpringBootTest(
        classes = E2eTestApplication.class,
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "kompile.subprocess.executable.mode=jvm",
                "kompile.embedding.type=none",
                "kompile.vectorstore.type=none"
        }
)
@ActiveProfiles("test")
class SubprocessConfigIT {

    @Autowired(required = false)
    private SubprocessExecutableConfig subprocessConfig;

    @Test
    @DisplayName("SubprocessExecutableConfig bean is available")
    void testConfigBeanAvailable() {
        assertNotNull(subprocessConfig, "SubprocessExecutableConfig should be autowired");
    }

    @Test
    @DisplayName("JVM mode is correctly detected")
    void testJvmMode() {
        if (subprocessConfig == null) return;

        assertTrue(subprocessConfig.isJvmMode(), "Should be in JVM mode");
        assertFalse(subprocessConfig.isNativeMode(), "Should not be in native mode");
        assertEquals(SubprocessExecutableConfig.LaunchMode.JVM_CLASSPATH,
                subprocessConfig.getLaunchMode());
    }

    @Test
    @DisplayName("Ingest command builds correctly in JVM mode")
    void testBuildIngestCommand() {
        if (subprocessConfig == null) return;

        Path argsFile = Path.of("/tmp/test-args.json");
        String heapSize = "4g";
        String javaPath = "/usr/bin/java";
        String classpath = "/app/lib/*";

        List<String> command = subprocessConfig.buildIngestCommand(
                argsFile, heapSize, javaPath, classpath);

        assertNotNull(command, "Command should not be null");
        assertFalse(command.isEmpty(), "Command should not be empty");

        // In JVM mode, command should start with java path
        assertEquals(javaPath, command.get(0),
                "First element should be java path in JVM mode");

        // Should contain heap size flag
        assertTrue(command.stream().anyMatch(s -> s.contains(heapSize)),
                "Command should contain heap size");
    }

    @Test
    @DisplayName("Vector population command builds correctly")
    void testBuildVectorPopulationCommand() {
        if (subprocessConfig == null) return;

        Path argsFile = Path.of("/tmp/test-vector-args.json");
        List<String> command = subprocessConfig.buildVectorPopulationCommand(
                argsFile, "2g", "/usr/bin/java", "/app/lib/*");

        assertNotNull(command);
        assertFalse(command.isEmpty());
    }

    @Test
    @DisplayName("Embedding command builds correctly")
    void testBuildEmbeddingCommand() {
        if (subprocessConfig == null) return;

        List<String> command = subprocessConfig.buildEmbeddingCommand(
                "2g", "/usr/bin/java", "/app/lib/*");

        assertNotNull(command);
        assertFalse(command.isEmpty());
    }

    @Test
    @DisplayName("Model init command builds correctly")
    void testBuildModelInitCommand() {
        if (subprocessConfig == null) return;

        Path argsFile = Path.of("/tmp/test-model-args.json");
        List<String> command = subprocessConfig.buildModelInitCommand(
                argsFile, "4g", "/usr/bin/java", "/app/lib/*");

        assertNotNull(command);
        assertFalse(command.isEmpty());
    }
}
