package ai.kompile.pipelines.steps.onnx;

public final class ONNXRunnerConstants {
    private ONNXRunnerConstants() {}

    public static final String RUNNER_FQCN = "ai.kompile.pipelines.steps.onnx.ONNXRunner";

    // Parameter Keys (matching names in the schema.json)
    public static final String PARAM_MODEL_URI = "modelUri";
    public static final String PARAM_INPUT_NAMES = "inputNames";   // List of ONNX input node names, also used as Data keys
    public static final String PARAM_OUTPUT_NAMES = "outputNames";  // List of ONNX output node names, also used as Data keys

    // Optional parameters that could be added in the future:
    // public static final String PARAM_EXECUTION_PROVIDER = "executionProvider"; // e.g., "CPU", "CUDA", "TENSORRT"
    // public static final String PARAM_LOG_SEVERITY_LEVEL = "logSeverityLevel"; // e.g., "VERBOSE", "INFO", "WARNING", "ERROR"
    // public static final String PARAM_INTRA_OP_NUM_THREADS = "intraOpNumThreads";
    // public static final String PARAM_GRAPH_OPTIMIZATION_LEVEL = "graphOptimizationLevel"; // e.g., "NO_OPTIMIZATION", "BASIC", "EXTENDED", "ALL"
}