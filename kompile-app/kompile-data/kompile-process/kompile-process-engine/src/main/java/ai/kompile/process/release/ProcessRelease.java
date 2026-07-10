package ai.kompile.process.release;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable deployment unit that pins a process definition and all runtime artifacts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProcessRelease implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private String processDefinitionId;
    private int processDefinitionVersion;
    private String environment;
    private ProcessReleaseStatus status;
    private String manifestHash;
    private List<ArtifactManifest> artifacts;
    private List<RuntimeProfile> runtimeProfiles;
    private List<String> secretRefs;
    private String rollbackReleaseId;
    private Instant createdAt;
    private String createdBy;
    private Instant validatedAt;
    private Instant deployedAt;
    private Instant deploymentVerifiedAt;
    private Map<String, String> deployedArtifactHashes;
    private Instant activatedAt;
    private Map<String, Object> metadata;
}
