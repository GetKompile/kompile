/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.pipelines.steps.python;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.ValueType;
import ai.kompile.pipelines.framework.core.config.ConfigAccessor;
import ai.kompile.pipelines.framework.core.config.SchemaRegistry;
import ai.kompile.pipelines.framework.api.configschema.StepSchema;

import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.io.FileUtils;


import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Executes Python code or scripts.
 * This is a simplified example relying on an external Python process execution.
 * Input and output data are serialized/deserialized via JSON passed through stdin/stdout.
 * A more robust implementation might use JEP, GraalVM Polyglot API, or gRPC.
 */

public class PythonRunner implements PipelineStepRunner {

    private String pythonCode;
    private String scriptPath; // URI to the script
    private Path actualScriptFile; // Local temporary file if scriptPath is a resource or remote
    private Map<String, String> inputVariables;  // DataKey -> PythonVarName
    private Map<String, String> outputVariables; // PythonVarName -> DataKey
    private String pythonExecutablePath; // Resolved path to python interpreter
    private String setupCode;
    private boolean returnFullPythonGlobals;
    private List<String> scriptArgs;
    private Map<String, String> environmentVariables;

    private boolean initialized = false;
    private Path tempScriptDir;


    // Helper Python script to wrap user code/script for I/O and execution
    private static final String PYTHON_WRAPPER_SCRIPT =
            "import sys, json, base64, pickle\n" +
                    "\n" +
                    "def decode_value(val_wrapper):\n" +
                    "    if not isinstance(val_wrapper, dict) or 'type' not in val_wrapper or 'value' not in val_wrapper:\n" +
                    "        return val_wrapper # Passthrough if not in expected format\n" +
                    "    val_type = val_wrapper['type']\n" +
                    "    val = val_wrapper['value']\n" +
                    "    if val_type == 'BYTES':\n" +
                    "        return base64.b64decode(val.encode('utf-8'))\n" +
                    "    # Add more type decoders as needed (e.g., for NDArray, Image)\n" +
                    "    # For complex objects, consider using pickle if Python versions are controlled\n" +
                    "    # For NDArray, one might expect a dict with shape, dtype, and base64 data\n" +
                    "    return val\n" +
                    "\n" +
                    "def encode_value(val):\n" +
                    "    if isinstance(val, bytes):\n" +
                    "        return {'type': 'BYTES', 'value': base64.b64encode(val).decode('utf-8')}\n" +
                    "    elif isinstance(val, (str, int, float, bool, list, dict, type(None))):\n" +
                    "        # For lists and dicts, recursively encode their contents if they contain special types\n" +
                    "        if isinstance(val, list):\n" +
                    "            return {'type': 'LIST', 'value': [encode_value(item) for item in val]}\n" +
                    "        if isinstance(val, dict):\n" +
                    "            return {'type': 'DATA', 'value': {k: encode_value(v) for k, v in val.items()}}\n" +
                    "        # Basic types are handled by JSON directly. This is a simplified wrapper.\n" +
                    "        # A more robust solution would map to Kompile ValueType explicitly.\n" +
                    "        return {'type': 'AUTO', 'value': val} # Let JSON handle basic types\n" +
                    "    # Add more type encoders as needed (e.g., for NumPy arrays -> to_list() or base64)\n" +
                    "    # For unknown complex types, try to convert to string or pickle\n" +
                    "    try:\n" +
                    "        return {'type': 'PICKLE_BASE64', 'value': base64.b64encode(pickle.dumps(val)).decode('utf-8')}\n" +
                    "    except Exception:\n" +
                    "        return {'type': 'STRING_REPR', 'value': repr(val)}\n"+
                    "\n" +
                    "input_json_str = sys.stdin.read()\n" +
                    "input_data_dict = json.loads(input_json_str)\n" +
                    "\n" +
                    "# Prepare globals for script execution\n" +
                    "exec_globals = {}\n" +
                    "for data_key, py_var_name in input_data_dict.get('__input_variables__', {}).items():\n" +
                    "    if data_key in input_data_dict:\n" +
                    "        exec_globals[py_var_name] = decode_value(input_data_dict[data_key])\n" +
                    "\n" +
                    "setup_code = input_data_dict.get('__setup_code__')\n" +
                    "user_code = input_data_dict.get('__user_code__')\n" +
                    "script_path = input_data_dict.get('__script_path__')\n" +
                    "script_args = input_data_dict.get('__script_args__', [])\n" +
                    "sys.argv = [script_path if script_path else 'script'] + script_args\n" +
                    "\n" +
                    "if setup_code:\n" +
                    "    exec(setup_code, exec_globals)\n" +
                    "\n" +
                    "if user_code:\n" +
                    "    exec(user_code, exec_globals)\n" +
                    "elif script_path:\n" +
                    "    with open(script_path, 'r') as f:\n" +
                    "        script_content = f.read()\n" +
                    "    exec(compile(script_content, script_path, 'exec'), exec_globals)\n" +
                    "\n" +
                    "output_data_dict = {}\n" +
                    "if input_data_dict.get('__return_full_globals__', False):\n" +
                    "    for key, val in exec_globals.items():\n" +
                    "        if not key.startswith('__'): # Exclude builtins\n" +
                    "             output_data_dict[key] = encode_value(val)\n" +
                    "else:\n" +
                    "    for py_var_name, data_key in input_data_dict.get('__output_variables__', {}).items():\n" +
                    "        if py_var_name in exec_globals:\n" +
                    "            output_data_dict[data_key] = encode_value(exec_globals[py_var_name])\n" +
                    "\n" +
                    "sys.stdout.write(json.dumps(output_data_dict))\n" +
                    "sys.stdout.flush()\n";


    @Override
    public void init(StepConfig stepConfig, Context context) throws Exception {

        StepSchema schema = SchemaRegistry.getInstance().getSchema(stepConfig.runnerClassName())
                .orElseThrow(() -> new IllegalStateException("No schema found for runner: " + stepConfig.runnerClassName()));
        ConfigAccessor config = new ConfigAccessor(stepConfig.getParameters(), schema);

        this.pythonCode = config.getString(PythonRunnerConstants.PARAM_PYTHON_CODE, null);
        String scriptPathUri = config.getString(PythonRunnerConstants.PARAM_SCRIPT_PATH, null);
        this.inputVariables = parseMap(config.getData(PythonRunnerConstants.PARAM_INPUT_VARIABLES, Data.empty()));
        this.outputVariables = parseMap(config.getData(PythonRunnerConstants.PARAM_OUTPUT_VARIABLES, Data.empty()));
        this.pythonExecutablePath = resolvePythonPath(config.getString(PythonRunnerConstants.PARAM_PYTHON_PATH, null));
        this.setupCode = config.getString(PythonRunnerConstants.PARAM_SETUP_CODE, null);
        this.returnFullPythonGlobals = config.getBoolean(PythonRunnerConstants.PARAM_RETURN_FULL_PYTHON_GLOBALS,
                PythonRunnerConstants.DEFAULT_RETURN_FULL_PYTHON_GLOBALS);
        this.scriptArgs = config.getStringList(PythonRunnerConstants.PARAM_ARGS, Collections.emptyList());
        this.environmentVariables = parseMap(config.getData(PythonRunnerConstants.PARAM_ENVIRONMENT_VARIABLES, Data.empty()));


        if ((pythonCode == null || pythonCode.trim().isEmpty()) && (scriptPathUri == null || scriptPathUri.trim().isEmpty())) {
            throw new IllegalArgumentException("Either '" + PythonRunnerConstants.PARAM_PYTHON_CODE + "' or '" +
                    PythonRunnerConstants.PARAM_SCRIPT_PATH + "' must be provided.");
        }
        if (pythonCode != null && !pythonCode.trim().isEmpty() && scriptPathUri != null && !scriptPathUri.trim().isEmpty()) {
            throw new IllegalArgumentException("Parameters '" + PythonRunnerConstants.PARAM_PYTHON_CODE + "' and '" +
                    PythonRunnerConstants.PARAM_SCRIPT_PATH + "' are mutually exclusive.");
        }

        if (scriptPathUri != null) {
            // Resolve URI to a local file (could involve downloading if it's http/s3, etc.)
            // For simplicity, assuming file:/ URI or direct path for now.
            File scriptFile = new File(new URI(scriptPathUri).getPath());
            if (!scriptFile.exists() || !scriptFile.isFile()) {
                throw new FileNotFoundException("Python script not found at: " + scriptFile.getAbsolutePath());
            }
            this.scriptPath = scriptFile.getAbsolutePath(); // Store absolute path
        }

        // Create a temporary directory for the wrapper script if needed
        this.tempScriptDir = Files.createTempDirectory("kompile_python_runner_");


        this.initialized = true;
    }

    private Map<String, String> parseMap(Data dataMap) {
        if (dataMap == null || dataMap.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new HashMap<>();
        for (String key : dataMap.keySet()) {
            result.put(key, dataMap.getString(key));
        }
        return result;
    }

    private String resolvePythonPath(String configuredPath) {
        // ADR-0011 Python Path Resolution logic would go here.
        // For now, simple logic: if configured, use it, else try "python3" then "python".
        if (configuredPath != null && !configuredPath.trim().isEmpty()) {
            File f = new File(configuredPath);
            if (f.exists() && f.canExecute()) return f.getAbsolutePath();
            // Could also check if it's a command in PATH without full path
        }
        // Try common defaults
        if (isExecutable("python3")) return "python3";
        if (isExecutable("python")) return "python";
        throw new IllegalStateException("Python executable not found. Please specify a valid 'pythonPath' or ensure 'python3' or 'python' is in the system PATH.");
    }

    private boolean isExecutable(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command, "--version");
            Process p = pb.start();
            p.waitFor(5, TimeUnit.SECONDS);
            return p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Override
    public Data exec(Data input, Context context) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("PythonRunner not initialized.");
        }
        Objects.requireNonNull(input, "Input Data cannot be null.");

        // Prepare the input dictionary for the Python wrapper script
        Map<String, Object> pythonInputMap = new HashMap<>();
        // Add special __ control keys
        pythonInputMap.put("__input_variables__", this.inputVariables);
        pythonInputMap.put("__output_variables__", this.outputVariables);
        pythonInputMap.put("__return_full_globals__", this.returnFullPythonGlobals);
        if (this.setupCode != null) pythonInputMap.put("__setup_code__", this.setupCode);
        if (this.pythonCode != null) pythonInputMap.put("__user_code__", this.pythonCode);
        if (this.scriptPath != null) pythonInputMap.put("__script_path__", this.scriptPath);
        if (this.scriptArgs != null) pythonInputMap.put("__script_args__", this.scriptArgs);


        // Populate with actual data based on inputVariables mapping
        for (Map.Entry<String, String> entry : this.inputVariables.entrySet()) {
            String dataKey = entry.getKey();
            // String pythonVarName = entry.getValue(); // Already in exec_globals in Python
            if (input.has(dataKey)) {
                // Need to wrap values with type information for robust deserialization in Python
                pythonInputMap.put(dataKey, wrapValueForPython(input.get(dataKey), input.type(dataKey), input.listType(dataKey)));
            } else {
                // Python script will see this variable as undefined in exec_globals
            }
        }

        String inputJson = ObjectMappers.getJsonMapper().writeValueAsString(pythonInputMap);

        // Create a temporary wrapper script file
        File wrapperScriptFile = new File(tempScriptDir.toFile(), "wrapper_" + UUID.randomUUID().toString() + ".py");
        Files.write(wrapperScriptFile.toPath(), PYTHON_WRAPPER_SCRIPT.getBytes(StandardCharsets.UTF_8));


        CommandLine cmdLine = new CommandLine(this.pythonExecutablePath);
        cmdLine.addArgument(wrapperScriptFile.getAbsolutePath());

        DefaultExecutor executor = new DefaultExecutor();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
        PumpStreamHandler streamHandler = new PumpStreamHandler(outputStream, errorStream, new ByteArrayInputStream(inputJson.getBytes(StandardCharsets.UTF_8)));
        executor.setStreamHandler(streamHandler);

        // Set environment variables if any
        if (this.environmentVariables != null && !this.environmentVariables.isEmpty()) {
            // DefaultExecutor doesn't directly take env vars in execute.
            // Need to use ProcessBuilder if fine-grained env control is needed,
            // or assume they are set externally if this simplified executor is used.
            // For now, logging a warning.
        }



        int exitValue;
        try {
            exitValue = executor.execute(cmdLine); // This is synchronous
        } catch (IOException e) {
            String errOutput = errorStream.toString(StandardCharsets.UTF_8.name());
            throw new IOException("Python script execution failed. Stderr: " + errOutput, e);
        } finally {
            wrapperScriptFile.delete(); // Clean up temp wrapper script
        }


        String stdOutput = outputStream.toString(StandardCharsets.UTF_8.name());
        String errOutput = errorStream.toString(StandardCharsets.UTF_8.name());

        if (exitValue != 0) {
            throw new RuntimeException("Python script execution failed (exit code " + exitValue + "). Stderr: " + errOutput);
        }


        // Deserialize output JSON from Python script's stdout
        Map<String, Object> rawOutputMap = ObjectMappers.getJsonMapper().readValue(stdOutput, new TypeReference<Map<String, Object>>() {});
        Data outputData = Data.empty();

        for (Map.Entry<String, Object> entry : rawOutputMap.entrySet()) {
            // The 'value' from Python is a map like {'type': 'X', 'value': Y}
            if (entry.getValue() instanceof Map) {
                Map<String, Object> valueWrapper = (Map<String, Object>) entry.getValue();
                Object actualValue = unwrapValueFromPython(valueWrapper);
                outputData.put(entry.getKey(), actualValue); // JData.put(Object) will infer type
            } else {
                // Should not happen if Python wrapper always uses the type/value dict
                outputData.put(entry.getKey(), entry.getValue());
            }
        }
        return outputData;
    }

    private Object wrapValueForPython(Object rawValue, ValueType valueType, ValueType listElementType) {
        Map<String, Object> wrapper = new HashMap<>();
        if (rawValue == null) {
            wrapper.put("type", "NULL"); // Or let it be just null and handle in Python
            wrapper.put("value", null);
            return wrapper;
        }

        switch (valueType) {
            case BYTES:
                wrapper.put("type", "BYTES");
                wrapper.put("value", Base64.getEncoder().encodeToString((byte[]) rawValue));
                break;
            case LIST:
                wrapper.put("type", "LIST");
                List<?> rawList = (List<?>) rawValue;
                // Recursively wrap elements if they are complex types
                wrapper.put("value", rawList.stream()
                        .map(el -> wrapValueForPython(el, listElementType, null)) // Assuming listElementType applies to all
                        .collect(Collectors.toList()));
                // TODO: Need to know the type of elements in the list for robust wrapping.
                // For now, this is a simplified recursive call assuming listElementType is consistent.
                break;
            // For NDArray, Image, Point, BoundingBox, Data:
            // These would ideally be converted to a map/dict structure that Python can understand.
            // E.g., NDArray -> { "shape": [...], "dtype": "float32", "data_base64": "..." }
            // For simplicity here, rely on default object-to-JSON by Jackson, then Python wrapper's encode_value
            case NDARRAY:
            case IMAGE:
            case POINT:
            case BOUNDING_BOX:
            case DATA: // Nested Data
                // Fallthrough to generic object handling, which will be serialized to JSON by Jackson
                // and then Python's encode_value will try to handle it (e.g. via pickle).
                // A more robust way is to convert to a Map<String,Object> here.
                if (rawValue instanceof Data) {
                    wrapper.put("type", "DATA_MAP"); // Special type for Python to recognize as nested Data
                    wrapper.put("value", ((Data)rawValue).toMap());
                } else {
                    // For other complex types, let Jackson serialize and Python wrapper handle best-effort
                    wrapper.put("type", valueType.name()); // Use the actual ValueType
                    wrapper.put("value", rawValue); // Jackson will serialize this
                }
                break;
            default: // STRING, INT64, DOUBLE, BOOLEAN
                wrapper.put("type", valueType.name());
                wrapper.put("value", rawValue);
                break;
        }
        return wrapper;
    }

    private Object unwrapValueFromPython(Map<String, Object> valueWrapper) {
        if (valueWrapper == null) return null;
        String typeStr = (String) valueWrapper.get("type");
        Object value = valueWrapper.get("value");
        if (value == null) return null;

        if ("BYTES".equals(typeStr)) {
            return Base64.getDecoder().decode(((String)value).getBytes(StandardCharsets.UTF_8));
        } else if ("PICKLE_BASE64".equals(typeStr)) {
            return value; // Return as string, Java side can't easily unpickle.
        } else if ("STRING_REPR".equals(typeStr)) {
            return value; // It's already a string representation
        } else if ("DATA_MAP".equals(typeStr) && value instanceof Map) {
            return Data.fromMap((Map<String,Object>)value);
        } else if ("LIST".equals(typeStr) && value instanceof List) {
            // Recursively unwrap list elements
            return ((List<?>) value).stream()
                    .map(item -> (item instanceof Map) ? unwrapValueFromPython((Map<String, Object>) item) : item)
                    .collect(Collectors.toList());
        }
        // For AUTO or unhandled types, assume 'value' is directly usable or JSON-primitive
        return value;
    }


    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void close() throws Exception {
        if (tempScriptDir != null) {
            try {
                FileUtils.deleteDirectory(tempScriptDir.toFile());
            } catch (IOException e) {
            }
        }
        // If using JEP or GraalVM Python context, close them here.
        initialized = false;
    }
}