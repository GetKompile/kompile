package ai.kompile.app.web.controllers;

import ai.kompile.app.services.agent.KompileLocalModelService;
import ai.kompile.cli.common.routing.ServiceEndpointsConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KompileLocalModelControllerTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void installsHotReloadingManagedStagingResolver(@TempDir Path tmpDir) throws Exception {
        KompileLocalModelService service = mock(KompileLocalModelService.class);
        ServiceEndpointsConfigManager manager = manager(tmpDir);
        new KompileLocalModelController(service, manager);
        ArgumentCaptor<Supplier> captor = ArgumentCaptor.forClass(Supplier.class);
        verify(service).setStagingUrlResolver(captor.capture());

        assertEquals(ServiceEndpointsConfigManager.DEFAULT_STAGING_URL, captor.getValue().get());
        manager.update(Map.of(ServiceEndpointsConfigManager.STAGING_URL_KEY,
                "http://localhost:19090"));
        assertEquals("http://localhost:19090", captor.getValue().get());
    }

    @Test
    void connectPersistsAndUsesNormalizedCentralEndpoint(@TempDir Path tmpDir) {
        KompileLocalModelService service = mock(KompileLocalModelService.class);
        ServiceEndpointsConfigManager manager = manager(tmpDir);
        KompileLocalModelController controller = new KompileLocalModelController(service, manager);
        when(service.connectTo("http://localhost:19090"))
                .thenReturn(Map.of("success", true));

        ResponseEntity<Map<String, Object>> response = controller.connect(
                Map.of("stagingUrl", "http://localhost:19090/"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("http://localhost:19090", manager.current().stagingUrl());
        verify(service).connectTo("http://localhost:19090");
    }

    @Test
    void connectRejectsMalformedUrlWithoutChangingService(@TempDir Path tmpDir) {
        KompileLocalModelService service = mock(KompileLocalModelService.class);
        KompileLocalModelController controller = new KompileLocalModelController(service, manager(tmpDir));

        ResponseEntity<Map<String, Object>> response = controller.connect(
                Map.of("stagingUrl", "not-a-url"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(service, never()).connectTo("not-a-url");
    }

    private static ServiceEndpointsConfigManager manager(Path tmpDir) {
        return new ServiceEndpointsConfigManager(
                tmpDir.resolve(ServiceEndpointsConfigManager.FILENAME));
    }
}
