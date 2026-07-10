package ai.kompile.process.release;

import ai.kompile.process.service.ProcessEngineService;
import ai.kompile.process.workflow.ProcessDefinition;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * Coordinates the durable promotion lifecycle for process releases.
 *
 * Deployment is acknowledged separately from publishing so an external worker or deployment
 * controller can provision artifacts before activation changes production routing.
 */
@Service
public class ProcessReleaseService {

    private final ProcessReleaseRepository repository;
    private final ProcessEngineService processEngineService;
    private final ProcessReleaseValidator validator;
    private final ReleaseDeploymentVerifier deploymentVerifier;
    private final ArtifactMaterializer artifactMaterializer;

    public ProcessReleaseService(ProcessReleaseRepository repository,
                                 ProcessEngineService processEngineService,
                                 ProcessReleaseValidator validator,
                                 ReleaseDeploymentVerifier deploymentVerifier,
                                 ArtifactMaterializer artifactMaterializer) {
        this.repository = repository;
        this.processEngineService = processEngineService;
        this.validator = validator;
        this.deploymentVerifier = deploymentVerifier;
        this.artifactMaterializer = artifactMaterializer;
    }

    public ProcessRelease createDraft(ProcessRelease request) {
        if (request == null) {
            throw new IllegalArgumentException("Process release is required");
        }
        if (request.getStatus() != null && request.getStatus() != ProcessReleaseStatus.DRAFT) {
            throw new IllegalStateException("New releases must start in DRAFT status");
        }
        if (request.getId() == null || request.getId().isBlank()) {
            request.setId(UUID.randomUUID().toString());
        } else if (repository.findById(request.getId()).isPresent()) {
            throw new IllegalStateException("Process release already exists: " + request.getId());
        }
        request.setStatus(ProcessReleaseStatus.DRAFT);
        request.setCreatedAt(request.getCreatedAt() != null ? request.getCreatedAt() : Instant.now());
        return repository.save(request);
    }

    public ProcessRelease get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Process release not found: " + id));
    }

    public List<ProcessRelease> list() {
        return repository.findAll();
    }

    public synchronized ProcessRelease validate(String id) {
        ProcessRelease release = requireStatus(id, ProcessReleaseStatus.DRAFT,
                ProcessReleaseStatus.FAILED);
        ProcessDefinition definition = processEngineService.getProcess(
                release.getProcessDefinitionId(), release.getProcessDefinitionVersion());
        ProcessReleaseValidator.ValidationResult result = validator.validate(definition, release);
        if (!result.isValid()) {
            release.setStatus(ProcessReleaseStatus.FAILED);
            repository.save(release);
            throw new ReleaseValidationException(result.getErrors());
        }
        release.setStatus(ProcessReleaseStatus.VALIDATED);
        release.setValidatedAt(Instant.now());
        return repository.save(release);
    }

    public synchronized ProcessRelease publish(String id) {
        return transition(id, ProcessReleaseStatus.PUBLISHED, ProcessReleaseStatus.VALIDATED);
    }

    public DeploymentReadiness readiness(String id) {
        return deploymentVerifier.verify(get(id));
    }

    public ArtifactDeployment stageArtifact(String id, String artifactId, String version,
                                            ArtifactUploadRequest request) {
        ProcessRelease release = requireStatus(id, ProcessReleaseStatus.PUBLISHED);
        if (request == null || request.contentBase64() == null || request.contentBase64().isBlank()) {
            throw new IllegalArgumentException("Base64 artifact content is required");
        }
        ArtifactManifest manifest = release.getArtifacts() == null ? null : release.getArtifacts().stream()
                .filter(candidate -> artifactId.equals(candidate.getArtifactId()))
                .filter(candidate -> version.equals(candidate.getVersion()))
                .findFirst()
                .orElse(null);
        if (manifest == null) {
            throw new NoSuchElementException("Artifact is absent from release: " + artifactId + ":" + version);
        }
        try {
            return artifactMaterializer.materialize(manifest,
                    Base64.getDecoder().decode(request.contentBase64()));
        } catch (IllegalArgumentException exception) {
            if ("Base64 artifact content is required".equals(exception.getMessage())) {
                throw exception;
            }
            throw new IllegalArgumentException("Invalid artifact upload: " + exception.getMessage(), exception);
        }
    }

    public synchronized ReleaseDeploymentResult deploy(String id, ReleaseDeploymentRequest request) {
        ProcessRelease release = requireStatus(id, ProcessReleaseStatus.PUBLISHED);
        if (request == null) {
            throw new IllegalArgumentException("Release deployment request is required");
        }
        List<ArtifactManifest> manifests = release.getArtifacts() == null
                ? List.of() : release.getArtifacts();
        Map<String, String> supplied = request.artifacts();
        Set<String> expected = new HashSet<>();
        for (ArtifactManifest manifest : manifests) {
            expected.add(artifactKey(manifest.getArtifactId(), manifest.getVersion()));
        }
        if (!supplied.keySet().equals(expected)) {
            Set<String> missing = new HashSet<>(expected);
            missing.removeAll(supplied.keySet());
            Set<String> unexpected = new HashSet<>(supplied.keySet());
            unexpected.removeAll(expected);
            throw new IllegalArgumentException("Deployment artifact coverage mismatch; missing="
                    + missing + ", unexpected=" + unexpected);
        }

        List<ArtifactDeployment> staged = new ArrayList<>();
        for (ArtifactManifest manifest : manifests) {
            String key = artifactKey(manifest.getArtifactId(), manifest.getVersion());
            staged.add(stageArtifact(id, manifest.getArtifactId(), manifest.getVersion(),
                    new ArtifactUploadRequest(supplied.get(key))));
        }
        ProcessRelease deployed = confirmDeployed(id);
        if (request.activate()) {
            deployed = activate(id);
        }
        return new ReleaseDeploymentResult(deployed, staged, readiness(id));
    }

    public synchronized ProcessRelease confirmDeployed(String id) {
        ProcessRelease release = requireStatus(id, ProcessReleaseStatus.PUBLISHED);
        DeploymentReadiness readiness = requireReady(release);
        release.setStatus(ProcessReleaseStatus.DEPLOYED);
        release.setDeployedAt(Instant.now());
        release.setDeploymentVerifiedAt(readiness.checkedAt());
        release.setDeployedArtifactHashes(readiness.verifiedArtifacts());
        return repository.save(release);
    }

    public synchronized ProcessRelease activate(String id) {
        ProcessRelease release = requireStatus(id, ProcessReleaseStatus.DEPLOYED);
        requireReady(release);
        repository.findByProcessAndEnvironment(release.getProcessDefinitionId(), release.getEnvironment())
                .stream()
                .filter(other -> !other.getId().equals(release.getId()))
                .filter(other -> other.getStatus() == ProcessReleaseStatus.ACTIVE)
                .forEach(other -> {
                    other.setStatus(ProcessReleaseStatus.DRAINING);
                    repository.save(other);
                });
        release.setStatus(ProcessReleaseStatus.ACTIVE);
        release.setActivatedAt(Instant.now());
        return repository.save(release);
    }

    public synchronized ProcessRelease retire(String id) {
        return transition(id, ProcessReleaseStatus.RETIRED, ProcessReleaseStatus.DRAINING);
    }

    public synchronized ProcessRelease rollback(String activeReleaseId, String targetReleaseId) {
        ProcessRelease active = requireStatus(activeReleaseId, ProcessReleaseStatus.ACTIVE);
        ProcessRelease target = requireStatus(targetReleaseId, ProcessReleaseStatus.DRAINING,
                ProcessReleaseStatus.RETIRED);
        if (!active.getProcessDefinitionId().equals(target.getProcessDefinitionId())
                || !active.getEnvironment().equals(target.getEnvironment())) {
            throw new IllegalStateException("Rollback target must belong to the same process and environment");
        }
        requireReady(target);

        active.setStatus(ProcessReleaseStatus.DRAINING);
        active.setRollbackReleaseId(target.getId());
        repository.save(active);

        target.setStatus(ProcessReleaseStatus.ACTIVE);
        target.setActivatedAt(Instant.now());
        return repository.save(target);
    }

    private String artifactKey(String artifactId, String version) {
        return artifactId + ":" + version;
    }

    private DeploymentReadiness requireReady(ProcessRelease release) {
        DeploymentReadiness readiness = deploymentVerifier.verify(release);
        if (!readiness.ready()) {
            throw new ReleaseDeploymentException(readiness.errors());
        }
        return readiness;
    }

    private ProcessRelease transition(String id, ProcessReleaseStatus target,
                                      ProcessReleaseStatus... allowed) {
        ProcessRelease release = requireStatus(id, allowed);
        release.setStatus(target);
        return repository.save(release);
    }

    private ProcessRelease requireStatus(String id, ProcessReleaseStatus... allowed) {
        ProcessRelease release = get(id);
        for (ProcessReleaseStatus status : allowed) {
            if (release.getStatus() == status) {
                return release;
            }
        }
        throw new IllegalStateException("Release " + id + " cannot transition from "
                + release.getStatus());
    }
}
