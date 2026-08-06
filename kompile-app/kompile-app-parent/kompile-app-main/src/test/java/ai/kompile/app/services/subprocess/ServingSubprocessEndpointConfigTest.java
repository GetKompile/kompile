package ai.kompile.app.services.subprocess;

import ai.kompile.cli.common.routing.ServiceEndpointsConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServingSubprocessEndpointConfigTest {

    @Test
    void demandDrivenLauncherUsesManagedServingPort(@TempDir Path tmpDir) throws Exception {
        ServiceEndpointsConfigManager manager = new ServiceEndpointsConfigManager(
                tmpDir.resolve(ServiceEndpointsConfigManager.FILENAME));
        manager.update(Map.of(
                ServiceEndpointsConfigManager.STAGING_URL_KEY, "http://localhost:19090",
                ServiceEndpointsConfigManager.SERVING_URL_KEY, "http://127.0.0.1:19091"));
        ServingSubprocessLauncher launcher = new ServingSubprocessLauncher();
        launcher.setEndpointConfigManager(manager);

        launcher.init();

        assertEquals(19091, launcher.getServingPort());
    }
}
