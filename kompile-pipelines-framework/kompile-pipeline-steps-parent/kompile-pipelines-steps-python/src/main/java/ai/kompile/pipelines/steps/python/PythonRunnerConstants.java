package ai.kompile.pipelines.steps.python;

public final class PythonRunnerConstants {
    private PythonRunnerConstants() {}

    public static final String RUNNER_FQCN = "ai.kompile.pipelines.steps.python.PythonRunner";

    // Parameter Keys (matching names in the schema.json)
    public static final String PARAM_PYTHON_CODE = "pythonCode";
    public static final String PARAM_SCRIPT_PATH = "scriptPath"; // URI for the script
    public static final String PARAM_INPUT_VARIABLES = "inputVariables";   // Map<String, String> DataKey -> PythonVarName
    public static final String PARAM_OUTPUT_VARIABLES = "outputVariables";  // Map<String, String> PythonVarName -> DataKey
    public static final String PARAM_PYTHON_PATH = "pythonPath"; // Path to python executable or virtual env
    public static final String PARAM_SETUP_CODE = "setupCode"; // Code to run once at init
    public static final String PARAM_RETURN_FULL_PYTHON_GLOBALS = "returnFullPythonGlobals"; // Boolean
    public static final String PARAM_ARGS = "args"; // List<String> for scriptPath
    public static final String PARAM_ENVIRONMENT_VARIABLES = "environmentVariables"; // Map<String, String>

    // Default Values (if applicable, though schema often handles this)
    public static final boolean DEFAULT_RETURN_FULL_PYTHON_GLOBALS = false;
}