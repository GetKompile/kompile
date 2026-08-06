package ai.kompile.app.services.subprocess;

import org.junit.jupiter.api.Test;

import ai.kompile.app.subprocess.ManagedSubprocessLauncher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningSubprocessLauncherTest {

    @Test
    void learningExtraJvmArgsDoNotHardcodeMultiBackendDisables() {
        LearningSubprocessLauncher launcher = new LearningSubprocessLauncher();

        List<String> args = launcher.getExtraJvmArgs();

        assertFalse(
                args.stream().anyMatch(arg -> arg.contains("nd4j.multibackend.enabled=false")),
                "learning launcher must not hardcode nd4j.multibackend.enabled=false");
        assertFalse(
                args.stream().anyMatch(arg -> arg.contains("org.nd4j.backend.multi.auto=false")),
                "learning launcher must not hardcode org.nd4j.backend.multi.auto=false");
    }

    @Test
    void learningBackendPreferenceRemainsCpuByDefault() {
        LearningSubprocessLauncher launcher = new LearningSubprocessLauncher();
        assertEquals(ManagedSubprocessLauncher.BackendPreference.CPU, launcher.getBackendPreference());
        assertTrue(launcher.getExtraJvmArgs().isEmpty(),
                "learning launcher should not inject extra args by default");
    }
}
