package ai.kompile.pipelines.steps.documentparser;

public final class DocumentParserConstants {
    private DocumentParserConstants() {}

    public static final String RUNNER_FQCN = "ai.kompile.pipelines.steps.documentparser.DocumentParserRunner";

    // Parameter Keys (should match names in the schema.json)
    public static final String PARAM_INPUT_KEY = "inputKey";
    public static final String PARAM_OUTPUT_KEY_PREFIX = "outputKeyPrefix";
    public static final String PARAM_EXTRACTION_TYPES = "extractionTypes";
    public static final String PARAM_PASSWORD = "password"; // For encrypted documents
    public static final String PARAM_TIKA_CONFIG_PATH = "tikaConfigPath"; // Optional custom Tika config

    // Default Values
    public static final String DEFAULT_INPUT_KEY = "documentBytes";
    public static final String DEFAULT_OUTPUT_KEY_PREFIX = "parsedDocument";
}