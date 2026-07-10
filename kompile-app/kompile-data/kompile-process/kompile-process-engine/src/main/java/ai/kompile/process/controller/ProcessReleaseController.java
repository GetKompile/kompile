package ai.kompile.process.controller;

import ai.kompile.process.release.ArtifactDeployment;
import ai.kompile.process.release.ArtifactUploadRequest;
import ai.kompile.process.release.DeploymentReadiness;
import ai.kompile.process.release.ProcessRelease;
import ai.kompile.process.release.ProcessReleaseService;
import ai.kompile.process.release.ReleaseDeploymentException;
import ai.kompile.process.release.ReleaseDeploymentRequest;
import ai.kompile.process.release.ReleaseDeploymentResult;
import ai.kompile.process.release.ReleaseValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Release management API. Publishing freezes configuration; deployment confirmation records that
 * the target runtime is ready; activation changes which release receives new process runs.
 */
@RestController
@RequestMapping("/api/process/releases")
public class ProcessReleaseController {

    private final ProcessReleaseService releaseService;

    public ProcessReleaseController(ProcessReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    @PostMapping
    public ResponseEntity<ProcessRelease> create(@RequestBody ProcessRelease release) {
        return ResponseEntity.status(HttpStatus.CREATED).body(releaseService.createDraft(release));
    }

    @GetMapping
    public List<ProcessRelease> list() {
        return releaseService.list();
    }

    @GetMapping("/{id}")
    public ProcessRelease get(@PathVariable String id) {
        return releaseService.get(id);
    }

    @PostMapping("/{id}/validate")
    public ProcessRelease validate(@PathVariable String id) {
        return releaseService.validate(id);
    }

    @PostMapping("/{id}/publish")
    public ProcessRelease publish(@PathVariable String id) {
        return releaseService.publish(id);
    }

    @PostMapping("/{id}/deploy")
    public ReleaseDeploymentResult deploy(@PathVariable String id,
                                          @RequestBody ReleaseDeploymentRequest request) {
        return releaseService.deploy(id, request);
    }

    @PostMapping("/{id}/artifacts/{artifactId}/{version}")
    public ArtifactDeployment stageArtifact(@PathVariable String id,
                                            @PathVariable String artifactId,
                                            @PathVariable String version,
                                            @RequestBody ArtifactUploadRequest request) {
        return releaseService.stageArtifact(id, artifactId, version, request);
    }

    @GetMapping("/{id}/deployment-readiness")
    public DeploymentReadiness deploymentReadiness(@PathVariable String id) {
        return releaseService.readiness(id);
    }

    @PostMapping("/{id}/deployment-confirmation")
    public ProcessRelease confirmDeployment(@PathVariable String id) {
        return releaseService.confirmDeployed(id);
    }

    @PostMapping("/{id}/activate")
    public ProcessRelease activate(@PathVariable String id) {
        return releaseService.activate(id);
    }

    @PostMapping("/{id}/retire")
    public ProcessRelease retire(@PathVariable String id) {
        return releaseService.retire(id);
    }

    @PostMapping("/{id}/rollback/{targetId}")
    public ProcessRelease rollback(@PathVariable String id, @PathVariable String targetId) {
        return releaseService.rollback(id, targetId);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, Object>> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(ReleaseValidationException.class)
    ResponseEntity<Map<String, Object>> invalidRelease(ReleaseValidationException exception) {
        return ResponseEntity.unprocessableEntity()
                .body(Map.of("error", "release_validation_failed", "errors", exception.getErrors()));
    }

    @ExceptionHandler(ReleaseDeploymentException.class)
    ResponseEntity<Map<String, Object>> deploymentNotReady(ReleaseDeploymentException exception) {
        return ResponseEntity.unprocessableEntity()
                .body(Map.of("error", "release_deployment_not_ready", "errors", exception.getErrors()));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<Map<String, Object>> conflict(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", exception.getMessage()));
    }
}
