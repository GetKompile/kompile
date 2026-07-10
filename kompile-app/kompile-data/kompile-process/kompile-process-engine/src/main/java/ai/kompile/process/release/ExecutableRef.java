package ai.kompile.process.release;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * Immutable reference from a process step to a published executable artifact.
 * The content hash pins the exact bytes even when a human-readable version is reused.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExecutableRef implements Serializable {
    private static final long serialVersionUID = 1L;

    private ExecutableKind kind;
    private String artifactId;
    private String version;
    private String contentHash;
    private String entrypoint;
    private String runtimeProfileId;
    private Map<String, Object> configuration;
}
