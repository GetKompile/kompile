package ai.kompile.cli.main;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliProcessLauncherTest {

    @Test
    void nativeParentAcceptsOnlyNativeChildren() {
        assertTrue(CliProcessLauncher.childArtifactCompatible(true, true));
        assertFalse(CliProcessLauncher.childArtifactCompatible(true, false));
    }

    @Test
    void jvmParentRetainsNativeAndExecutableJarDevelopmentTiers() {
        assertTrue(CliProcessLauncher.childArtifactCompatible(false, true));
        assertTrue(CliProcessLauncher.childArtifactCompatible(false, false));
    }

    @Test
    void nativeParentReportsMixedArtifactAsDistributionError() {
        Path jar = Path.of("/opt/kompile/lib/kompile-model-staging.jar");

        IOException error = assertThrows(IOException.class,
                () -> CliProcessLauncher.requireCompatibleChild(
                        "kompile-model-staging", false, jar, true));

        assertTrue(error.getMessage().contains("Native Kompile execution"));
        assertTrue(error.getMessage().contains("bin/kompile-model-staging"));
        assertTrue(error.getMessage().contains("JAR fallbacks are available only"));
        assertDoesNotThrow(() -> CliProcessLauncher.requireCompatibleChild(
                "kompile-model-staging", true,
                Path.of("/opt/kompile/bin/kompile-model-staging"), true));
    }
}
