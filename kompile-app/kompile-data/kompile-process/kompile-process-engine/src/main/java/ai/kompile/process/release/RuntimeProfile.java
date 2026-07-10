package ai.kompile.process.release;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Versioned runtime and security constraints applied to executable artifacts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RuntimeProfile implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private String version;
    private String runtime;
    private String runtimeVersion;
    private long maxMemoryBytes;
    private long maxCpuMillis;
    private long maxWallTimeMillis;
    private long maxOutputBytes;
    private boolean networkAllowed;
    private boolean fileSystemAllowed;
    private boolean hostAccessAllowed;
    private List<String> allowedHosts;
    private List<String> allowedSecretRefs;
    private Map<String, String> environment;
}
