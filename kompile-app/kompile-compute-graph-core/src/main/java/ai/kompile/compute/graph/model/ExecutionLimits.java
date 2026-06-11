package ai.kompile.compute.graph.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;

/**
 * Resource limits applied to script execution on a single node.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionLimits {

    /**
     * Maximum CPU time for script execution.
     */
    @Builder.Default
    private Duration maxCpuTime = Duration.ofSeconds(30);

    /**
     * Maximum heap memory the script can allocate.
     */
    @Builder.Default
    private long maxHeapMemoryBytes = 64 * 1024 * 1024; // 64MB

    /**
     * Maximum stack depth to prevent recursion bombs.
     */
    @Builder.Default
    private int maxStackFrames = 256;

    /**
     * Whether to allow file I/O from scripts.
     */
    @Builder.Default
    private boolean allowIO = false;

    /**
     * Whether to allow network access from scripts.
     */
    @Builder.Default
    private boolean allowNetwork = false;

    /**
     * Whether to allow access to host Java classes.
     */
    @Builder.Default
    private boolean allowHostAccess = false;

    public static ExecutionLimits defaults() {
        return ExecutionLimits.builder().build();
    }

    public static ExecutionLimits unrestricted() {
        return ExecutionLimits.builder()
                .maxCpuTime(Duration.ofMinutes(10))
                .maxHeapMemoryBytes(512 * 1024 * 1024L)
                .maxStackFrames(1024)
                .allowIO(true)
                .allowNetwork(true)
                .allowHostAccess(true)
                .build();
    }
}
