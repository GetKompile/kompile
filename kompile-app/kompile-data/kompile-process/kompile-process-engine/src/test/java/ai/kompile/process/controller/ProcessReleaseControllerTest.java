package ai.kompile.process.controller;

import ai.kompile.process.release.DeploymentReadiness;
import ai.kompile.process.release.ProcessRelease;
import ai.kompile.process.release.ProcessReleaseService;
import ai.kompile.process.release.ProcessReleaseStatus;
import ai.kompile.process.release.ReleaseDeploymentException;
import ai.kompile.process.release.ReleaseValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProcessReleaseControllerTest {

    @Test
    void exposesDeploymentConfirmationWithoutActivatingRelease() {
        ProcessReleaseService service = mock(ProcessReleaseService.class);
        ProcessRelease deployed = ProcessRelease.builder()
                .id("r1")
                .status(ProcessReleaseStatus.DEPLOYED)
                .build();
        when(service.confirmDeployed("r1")).thenReturn(deployed);

        ProcessReleaseController controller = new ProcessReleaseController(service);

        assertSame(deployed, controller.confirmDeployment("r1"));
        assertEquals(ProcessReleaseStatus.DEPLOYED, deployed.getStatus());
    }

    @Test
    void exposesDeploymentReadinessForAutomation() {
        ProcessReleaseService service = mock(ProcessReleaseService.class);
        DeploymentReadiness readiness = new DeploymentReadiness(
                "r1", true, Instant.now(), Map.of("script:1", "sha256:abc"), List.of());
        when(service.readiness("r1")).thenReturn(readiness);

        ProcessReleaseController controller = new ProcessReleaseController(service);

        assertSame(readiness, controller.deploymentReadiness("r1"));
    }

    @Test
    void returnsStructuredDeploymentErrors() {
        ProcessReleaseController controller =
                new ProcessReleaseController(mock(ProcessReleaseService.class));

        ResponseEntity<Map<String, Object>> response = controller.deploymentNotReady(
                new ReleaseDeploymentException(List.of("artifact missing")));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        assertEquals("release_deployment_not_ready", response.getBody().get("error"));
        assertEquals(List.of("artifact missing"), response.getBody().get("errors"));
    }

    @Test
    void returnsStructuredValidationErrors() {
        ProcessReleaseController controller =
                new ProcessReleaseController(mock(ProcessReleaseService.class));

        ResponseEntity<Map<String, Object>> response = controller.invalidRelease(
                new ReleaseValidationException(List.of("hash mismatch", "runtime missing")));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        assertEquals("release_validation_failed", response.getBody().get("error"));
        assertEquals(List.of("hash mismatch", "runtime missing"), response.getBody().get("errors"));
    }

    @Test
    void mapsMissingReleaseToNotFound() {
        ProcessReleaseController controller =
                new ProcessReleaseController(mock(ProcessReleaseService.class));

        ResponseEntity<Map<String, Object>> response =
                controller.notFound(new NoSuchElementException("missing"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("missing", response.getBody().get("error"));
    }
}
