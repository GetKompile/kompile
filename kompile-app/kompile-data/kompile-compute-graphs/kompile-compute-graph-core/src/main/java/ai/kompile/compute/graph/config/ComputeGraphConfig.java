package ai.kompile.compute.graph.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Runtime configuration for the compute graph engine.
 * Managed via REST API and UI — NOT Spring properties.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ComputeGraphConfig {

    /**
     * Whether the compute graph engine is enabled.
     */
    @Builder.Default
    private boolean enabled = true;

    /**
     * Whether the scripting backend (JS/Python) is enabled.
     */
    @Builder.Default
    private boolean scriptingEnabled = true;

    /**
     * Whether the Drools rules backend is enabled.
     */
    @Builder.Default
    private boolean droolsEnabled = false;

    /**
     * Whether the Drools inference engine (cross-node rule chaining) is enabled.
     */
    @Builder.Default
    private boolean droolsInferenceEnabled = true;

    /**
     * Whether the Xircuits workflow engine (Python visual workflows) is enabled.
     */
    @Builder.Default
    private boolean xircuitsEnabled = false;

    /**
     * Path to the Xircuits executable (default: "xircuits", found on PATH).
     */
    @Builder.Default
    private String xircuitsExecutable = "xircuits";

    /**
     * Path to the Python executable for Xircuits fallback (default: "python3").
     */
    @Builder.Default
    private String xircuitsPythonExecutable = "python3";

    /**
     * Whether the n8n workflow engine (JavaScript workflow automation) is enabled.
     */
    @Builder.Default
    private boolean n8nEnabled = false;

    /**
     * Path to the n8n executable (default: "n8n", found on PATH).
     */
    @Builder.Default
    private String n8nExecutable = "n8n";

    /**
     * Path to npx for n8n fallback execution (default: "npx").
     */
    @Builder.Default
    private String n8nNpxExecutable = "npx";

    /**
     * Default timeout for workflow execution in seconds (Xircuits and n8n).
     */
    @Builder.Default
    private long workflowDefaultTimeoutSeconds = 300;

    /**
     * Default max CPU time for script execution (milliseconds).
     */
    @Builder.Default
    private long defaultMaxCpuTimeMs = 30000;

    /**
     * Default max heap memory for script execution (bytes).
     */
    @Builder.Default
    private long defaultMaxHeapMemoryBytes = 67108864; // 64MB

    /**
     * Default max stack frames for script execution.
     */
    @Builder.Default
    private int defaultMaxStackFrames = 256;

    /**
     * Whether to allow file I/O from scripts by default.
     */
    @Builder.Default
    private boolean defaultAllowIO = false;

    /**
     * Whether to allow network access from scripts by default.
     */
    @Builder.Default
    private boolean defaultAllowNetwork = false;

    /**
     * Whether to allow host Java class access from scripts by default.
     */
    @Builder.Default
    private boolean defaultAllowHostAccess = false;

    /**
     * Whether Apache Camel route execution is enabled.
     */
    @Builder.Default
    private boolean camelEnabled = false;

    /**
     * Maximum time in milliseconds for a single Camel route execution.
     */
    @Builder.Default
    private long camelRouteTimeoutMs = 60000;

    /**
     * Whether Drools decision table (XLS/CSV spreadsheet rules) execution is enabled.
     */
    @Builder.Default
    private boolean droolsDecisionTableEnabled = true;

    /**
     * Max rule firings per node (Drools).
     */
    @Builder.Default
    private int maxRuleFiringsPerNode = 1000;

    /**
     * Max total rule firings for inference engine (Drools).
     */
    @Builder.Default
    private int maxRuleFiringsTotal = 10000;
}
