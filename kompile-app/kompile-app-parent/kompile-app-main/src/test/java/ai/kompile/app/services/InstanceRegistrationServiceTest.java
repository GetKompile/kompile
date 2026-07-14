package ai.kompile.app.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InstanceRegistrationServiceTest {

    @Test
    void configuredProjectRootIsCanonicalAndProducesProjectInstanceName(@TempDir Path projectRoot) {
        Path resolved = InstanceRegistrationService.resolveProjectRoot(projectRoot.toString());
        assertEquals(projectRoot.toAbsolutePath().normalize(), resolved);
        assertEquals("dogfood-project", InstanceRegistrationService.sanitize("dogfood project"));
    }
}
