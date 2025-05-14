package ai.kompile.pipelines.steps.deeplearning4j;

public final class DL4JRunnerConstants {
    private DL4JRunnerConstants() {}

    public static final String RUNNER_FQCN = "ai.kompile.pipelines.steps.deeplearning4j.DL4JRunner";

    // Parameter Keys (matching names in the schema.json)
    public static final String PARAM_MODEL_URI = "modelUri";
    public static final String PARAM_INPUT_NAMES = "inputNames";
    public static final String PARAM_OUTPUT_NAMES = "outputNames";
    // Optional: A parameter to distinguish between MLN and CompGraph if auto-detection isn't robust enough
    // public static final String PARAM_MODEL_TYPE = "modelType"; // e.g., "MLN", "COMP_GRAPH"
}