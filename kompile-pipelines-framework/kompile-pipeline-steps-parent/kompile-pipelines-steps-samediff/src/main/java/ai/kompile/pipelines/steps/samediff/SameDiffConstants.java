package ai.kompile.pipelines.steps.samediff;

public final class SameDiffConstants {
    private SameDiffConstants() {}

    public static final String RUNNER_FQCN = "ai.kompile.pipelines.steps.samediff.SameDiffRunner";

    // Parameter Keys (matching names in the schema.json)
    public static final String PARAM_MODEL_URI = "modelUri";
    public static final String PARAM_OUTPUT_NAMES = "outputNames";
    public static final String PARAM_DEBUG_MODE = "debugMode";
    public static final String PARAM_VERBOSE_MODE = "verboseMode";
}