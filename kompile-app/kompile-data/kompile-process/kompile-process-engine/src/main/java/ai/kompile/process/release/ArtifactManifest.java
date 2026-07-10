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
 * Content-addressed metadata for an executable included in a process release.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ArtifactManifest implements Serializable {
    private static final long serialVersionUID = 1L;

    private String artifactId;
    private String version;
    private ExecutableKind kind;
    private String contentHash;
    private String mediaType;
    private String storageUri;
    private String runtimeProfileId;
    private List<String> dependencyLocks;
    private Instant createdAt;
    private String createdBy;
    private Map<String, Object> metadata;
}
