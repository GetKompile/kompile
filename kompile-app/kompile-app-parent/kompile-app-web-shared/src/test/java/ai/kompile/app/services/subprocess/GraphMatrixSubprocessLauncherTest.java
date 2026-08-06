package ai.kompile.app.services.subprocess;

import org.junit.jupiter.api.Test;

import ai.kompile.app.subprocess.ManagedSubprocessLauncher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphMatrixSubprocessLauncherTest {

    @Test
    void graphMatrixExtraJvmArgsContainNoHardcodedMultiBackendDisables() {
        GraphMatrixSubprocessLauncher launcher = new GraphMatrixSubprocessLauncher();

        List<String> args = launcher.getExtraJvmArgs();

        assertFalse(
                args.stream().anyMatch(arg -> arg.contains("nd4j.multibackend.enabled=false")),
                "graph-matrix launcher must not hardcode nd4j.multibackend.enabled=false");
        assertFalse(
                args.stream().anyMatch(arg -> arg.contains("org.nd4j.backend.multi.auto=false")),
                "graph-matrix launcher must not hardcode org.nd4j.backend.multi.auto=false");

        assertTrue(launcher.getBackendPreference() == ManagedSubprocessLauncher.BackendPreference.CPU,
                "graph-matrix launcher remains CPU preference for matrix storage workload");
    }
}
