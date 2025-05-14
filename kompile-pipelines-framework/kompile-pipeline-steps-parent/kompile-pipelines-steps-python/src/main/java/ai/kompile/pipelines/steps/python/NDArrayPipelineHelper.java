package ai.kompile.pipelines.steps.python;

import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.ValueType;
import ai.kompile.pipelines.framework.core.config.GenericStepConfig;
import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;
import ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "ndarray-pipeline-helper", mixinStandardHelpOptions = true,
        description = "Create an NDArray pipeline configuration for a Python step using Kompile Pipeline Framework.")
public class NDArrayPipelineHelper implements Callable<Integer> {

    @Option(names = {"-i", "--input-name"}, description = "Name of the primary input NDArray variable (Default: input)", defaultValue = "input")
    private String inputName;

    @Option(names = {"-o", "--output-name"}, description = "Name of the primary output NDArray variable (Default: output)", defaultValue = "output")
    private String outputName;

    @Option(names = {"-t", "--ndarray-type"}, description = "Type of the primary NDArray (e.g., FLOAT, DOUBLE, INT32). Valid values: ${COMPLETION-CANDIDATES}",
            required = true)
    private ValueType ndarrayType;

    @Option(names = {"-f", "--format"}, description = "Output format: json or yaml (Default: json)", defaultValue = "json")
    private String format;

    @Option(names = {"--python-code"}, description = "Python code to execute.")
    private String pythonCode;

    @Option(names = {"--script-path"}, description = "Path to the Python script to execute.")
    private String scriptPath;

    @Option(names = {"--input-variables"}, description = "Additional input variables as key-value pairs (e.g., name1=TYPE,name2=TYPE:SECONDARY_TYPE). Type from ai.kompile.pipelines.framework.api.data.ValueType.", split = ",")
    private Map<String, String> inputVariablesSpec;

    @Option(names = {"--output-variables"}, description = "Additional output variables as key-value pairs (e.g., name1=TYPE,name2=TYPE:SECONDARY_TYPE). Type from ai.kompile.pipelines.framework.api.data.ValueType.", split = ",")
    private Map<String, String> outputVariablesSpec;

    @Option(names = {"--extra-inputs"}, description = "Extra (literal) input parameters for Python script as key-value pairs (e.g., param1=value1,param2=value2)", split = ",")
    private Map<String, String> extraInputs;


    @Override
    public Integer call() throws Exception {
        // Correctly typed builder instance
        PythonConfig.PythonConfigBuilder pythonConfigBuilder = PythonConfig.builder();

        if (pythonCode != null && !pythonCode.isEmpty()) {
            pythonConfigBuilder.pythonCode(pythonCode);
        } else if (scriptPath != null && !scriptPath.isEmpty()) {
            pythonConfigBuilder.pythonCodePath(scriptPath);
        } else {
            System.err.println("Error: Either --python-code or --script-path must be specified.");
            return 1;
        }

        Map<String, PythonIO> finalIoInputs = new HashMap<>();
        Map<String, PythonIO> finalIoOutputs = new HashMap<>();

        finalIoInputs.put(inputName, PythonIO.builder().type(ValueType.NDARRAY).secondaryType(ndarrayType).build());
        finalIoOutputs.put(outputName, PythonIO.builder().type(ValueType.NDARRAY).secondaryType(ndarrayType).build());

        if (inputVariablesSpec != null) {
            for (Map.Entry<String, String> entry : inputVariablesSpec.entrySet()) {
                PythonIO pythonIO = parseVariableSpec(entry.getValue(), ndarrayType);
                if (pythonIO == null) return 1;
                finalIoInputs.put(entry.getKey(), pythonIO);
            }
        }

        if (outputVariablesSpec != null) {
            for (Map.Entry<String, String> entry : outputVariablesSpec.entrySet()) {
                PythonIO pythonIO = parseVariableSpec(entry.getValue(), ndarrayType);
                if (pythonIO == null) return 1;
                finalIoOutputs.put(entry.getKey(), pythonIO);
            }
        }

        pythonConfigBuilder.ioInputs(finalIoInputs);
        pythonConfigBuilder.ioOutputs(finalIoOutputs);

        if (extraInputs != null) {
            pythonConfigBuilder.extraInputs(extraInputs);
        }

        PythonConfig builtPythonConfig = pythonConfigBuilder.build();

        ObjectMapper objectMapper = ObjectMappers.getJsonMapper();
        Map<String, Object> paramsMap = objectMapper.convertValue(builtPythonConfig, new TypeReference<Map<String, Object>>() {});
        Data stepParams = Data.Factory.get().fromMap(paramsMap);

        StepConfig pythonGenericStepConfig = new GenericStepConfig(
                PythonRunnerConstants.RUNNER_FQCN,
                stepParams
        );

        SequencePipeline pipeline = SequencePipeline.builder()
                .steps(List.of(pythonGenericStepConfig))
                .build();

        String outputString;
        if ("json".equalsIgnoreCase(format)) {
            outputString = ObjectMappers.getJsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(pipeline);
        } else if ("yaml".equalsIgnoreCase(format)) {
            outputString = ObjectMappers.getYamlMapper().writerWithDefaultPrettyPrinter().writeValueAsString(pipeline);
        } else {
            System.err.println("Error: Invalid output format '" + format + "'. Choose 'json' or 'yaml'.");
            return 1;
        }

        System.out.println(outputString);
        return 0;
    }

    private PythonIO parseVariableSpec(String spec, ValueType defaultNdarrayType) {
        String[] parts = spec.split(":", 2);
        ValueType type;
        ValueType secondaryType = null;

        try {
            type = ValueType.valueOf(parts[0].trim().toUpperCase());
            if (parts.length > 1) {
                secondaryType = ValueType.valueOf(parts[1].trim().toUpperCase());
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Error: Invalid ValueType specified in variable spec: '" + spec + "'. " + e.getMessage());
            return null;
        }

        if (type == ValueType.NDARRAY && secondaryType == null) {
            secondaryType = defaultNdarrayType;
        }
        return PythonIO.builder().type(type).secondaryType(secondaryType).build();
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new NDArrayPipelineHelper()).execute(args);
        System.exit(exitCode);
    }
}