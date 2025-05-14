package ai.kompile.pipelines.steps.deeplearning4j;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.configschema.StepSchema;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.NDArray;
import ai.kompile.pipelines.framework.core.config.ConfigAccessor;
import ai.kompile.pipelines.framework.core.config.SchemaRegistry;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.util.ModelSerializer;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.*;


public class DL4JRunner implements PipelineStepRunner {

    private MultiLayerNetwork mlnModel;
    private ComputationGraph cgModel;
    private List<String> inputNames;  // Names of Data keys for input INDArrays
    private List<String> outputNames; // Names for Data keys for output INDArrays

    private boolean initialized = false;

    @Override
    public void init(StepConfig stepConfig, Context context) throws Exception {
        StepSchema schema = SchemaRegistry.getInstance().getSchema(stepConfig.runnerClassName())
                .orElseThrow(() -> new IllegalStateException(
                        "No schema found for runner: " + stepConfig.runnerClassName()));

        ConfigAccessor config = new ConfigAccessor(stepConfig.getParameters(), schema);

        String modelUriString = config.getString(DL4JRunnerConstants.PARAM_MODEL_URI);
        if (modelUriString == null || modelUriString.trim().isEmpty()) {
            throw new IllegalArgumentException("Parameter '" + DL4JRunnerConstants.PARAM_MODEL_URI +
                    "' is required for DL4JRunner and cannot be empty.");
        }

        this.inputNames = config.getStringList(DL4JRunnerConstants.PARAM_INPUT_NAMES, Collections.emptyList());
        this.outputNames = config.getStringList(DL4JRunnerConstants.PARAM_OUTPUT_NAMES, Collections.emptyList());


        File modelFile;
        try {
            URI uri = new URI(modelUriString);
            if (uri.getScheme() == null || "file".equalsIgnoreCase(uri.getScheme())) {
                modelFile = new File(uri.getPath());
            } else {
                // TODO: Add support for other URI schemes (http, s3 etc.) using a resource loading utility
                throw new IllegalArgumentException("Unsupported URI scheme for model: " + modelUriString +
                        ". Only local file paths are currently supported directly.");
            }
            if (!modelFile.exists() || !modelFile.isFile()) {
                throw new FileNotFoundException("Model file not found or is not a regular file: " + modelFile.getAbsolutePath());
            }
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("Invalid model URI syntax: " + modelUriString, e);
        }


        boolean loaded = false;
        try {
            mlnModel = ModelSerializer.restoreMultiLayerNetwork(modelFile, true);
            loaded = true;
        } catch (Exception eMLN) {
            try {
                cgModel = ModelSerializer.restoreComputationGraph(modelFile, true);
                loaded = true;
            } catch (Exception eCG) {
                throw new IOException("Failed to load DL4J model (tried MultiLayerNetwork and ComputationGraph) from URI: " + modelUriString, eCG);
            }
        }

        if (!loaded) { // Should not be reached if exceptions are thrown correctly
            throw new IOException("Model could not be loaded from URI: " + modelUriString);
        }

        // Validate input/output name counts if model type is known
        if (mlnModel != null) {
            if (this.inputNames.isEmpty()){
                this.inputNames = Collections.singletonList("input"); // Default input name for MLN
            }
            if(this.outputNames.isEmpty()){
                this.outputNames = Collections.singletonList("output"); // Default output name for MLN
            }
        } else if (cgModel != null) {
            if (this.inputNames.isEmpty() && cgModel.getNumInputArrays() > 0) {
                throw new IllegalArgumentException("ComputationGraph requires inputNames to be specified when it has inputs. Found " + cgModel.getNumInputArrays() + " model inputs.");
            }
            if (!this.inputNames.isEmpty() && this.inputNames.size() != cgModel.getNumInputArrays()) {
                throw new IllegalArgumentException(String.format(
                        "Number of provided inputNames (%d: %s) does not match ComputationGraph's number of input arrays (%d).",
                        this.inputNames.size(), this.inputNames, cgModel.getNumInputArrays()));
            }
            if (!this.outputNames.isEmpty() && this.outputNames.size() != cgModel.getNumOutputArrays()) {
                throw new IllegalArgumentException(String.format(
                        "Number of provided outputNames (%d: %s) does not match ComputationGraph's number of output arrays (%d).",
                        this.outputNames.size(), this.outputNames, cgModel.getNumOutputArrays()));
            }
        }

        this.initialized = true;
    }

    @Override
    public Data exec(Data input, Context context) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("DL4JRunner not initialized. Call init() first.");
        }
        Objects.requireNonNull(input, "Input Data object cannot be null.");

        Data outputData = Data.empty();
        INDArray[] outputArrays;

        if (mlnModel != null) {
            String currentInputName = inputNames.get(0); // MLN has one input
            NDArray kompileInputNDArray = input.getNDArray(currentInputName);
            if (kompileInputNDArray == null) {
                throw new IllegalArgumentException("Input Data is missing required NDArray for key: '" + currentInputName + "' for MLN.");
            }
            INDArray nativeInput = convertToINDArray(kompileInputNDArray, currentInputName);
            outputArrays = new INDArray[]{mlnModel.output(nativeInput)};
        } else if (cgModel != null) {
            INDArray[] nativeInputs = new INDArray[inputNames.size()];
            for (int i = 0; i < inputNames.size(); i++) {
                String currentInputName = inputNames.get(i);
                NDArray kompileInputNDArray = input.getNDArray(currentInputName);
                if (kompileInputNDArray == null) {
                    throw new IllegalArgumentException("Input Data is missing required NDArray for key: '" + currentInputName + "' for ComputationGraph.");
                }
                nativeInputs[i] = convertToINDArray(kompileInputNDArray, currentInputName);
            }
            Map<String, INDArray> inputMap = new HashMap<>();
            for(int i = 0; i < inputNames.size(); i++) {
                // Use actual input names of the graph model if available, otherwise rely on order.
                // This assumes the order in this.inputNames matches the order of cgModel.getInputVertexNames() or similar.
                // A more robust mapping would use model's actual input names if provided by cgModel.
                inputMap.put(cgModel.getConfiguration().getNetworkInputs().get(i), nativeInputs[i]);
            }
            // outputArrays = cgModel.output(nativeInputs); // For unnamed inputs/outputs by order
            Map<String, INDArray> outputMap = cgModel.feedForward(false);
            // Ensure outputArrays matches the order of this.outputNames if specified, or model's output order
            if (!this.outputNames.isEmpty()) {
                outputArrays = new INDArray[this.outputNames.size()];
                for(int i = 0; i < this.outputNames.size(); ++i) {
                    String outputName = this.outputNames.get(i);
                    // If outputNames were from config, they must match actual model output names
                    // Or, if outputNames are just for Data keys, map from model's output names.
                    // For now, assume outputNames (if provided) match the keys in outputMap or order.
                    // A safer way is to iterate outputMap.keySet() if outputNames are just labels for Data.
                    String modelOutputName = cgModel.getConfiguration().getNetworkOutputs().get(i);
                    outputArrays[i] = outputMap.get(modelOutputName);
                    if(outputArrays[i] == null) {
                        throw new IllegalStateException("ComputationGraph did not produce output for configured/expected name: '" + modelOutputName + "' (mapped from config name '" + outputName + "'). Available outputs: " + outputMap.keySet());
                    }
                }
            } else { // Generic output naming if not specified
                outputArrays = outputMap.values().toArray(new INDArray[0]);
            }


        } else {
            throw new IllegalStateException("No DL4J model (MultiLayerNetwork or ComputationGraph) loaded.");
        }

        // Map output INDArrays to Kompile Pipelines NDArrays and put in outputData
        if (outputArrays.length == 0) {
        } else if (this.outputNames.isEmpty()) {
            if (outputArrays.length == 1) {
                outputData.put("output", convertFromINDArray(outputArrays[0], "output"));
            } else {
                for (int i = 0; i < outputArrays.length; i++) {
                    String outName = "output_" + i;
                    outputData.put(outName, convertFromINDArray(outputArrays[i], outName));
                }
            }
        } else {
            if (this.outputNames.size() != outputArrays.length) {
                throw new IllegalStateException(String.format(
                        "Number of specified outputNames (%d) does not match number of actual model output arrays (%d). Output names: %s",
                        this.outputNames.size(), outputArrays.length, this.outputNames));
            }
            for (int i = 0; i < outputArrays.length; i++) {
                outputData.put(this.outputNames.get(i), convertFromINDArray(outputArrays[i], this.outputNames.get(i)));
            }
        }

        return outputData;
    }

    /**
     * Converts a Kompile Pipelines NDArray to an ND4J INDArray.
     * This requires a concrete Kompile NDArray implementation that wraps or can be converted to INDArray.
     * This method should be implemented in a dedicated data conversion utility or module.
     *
     * @param kompileNDArray The Kompile NDArray.
     * @param name The name of the array (for logging/debugging).
     * @return The corresponding INDArray.
     * @throws UnsupportedOperationException if the conversion is not supported for the given NDArray type.
     */
    protected INDArray convertToINDArray(NDArray kompileNDArray, String name) {
        Objects.requireNonNull(kompileNDArray, "Kompile NDArray to convert cannot be null for name: " + name);
        // Attempt to get a native INDArray if the Kompile NDArray is a wrapper
        if (kompileNDArray.getNative() instanceof INDArray) {
            return (INDArray) kompileNDArray.getNative();
        }

        // Fallback: Manual conversion from ByteBuffer, shape, and type.
        // This is highly dependent on the Kompile NDArray's internal structure and ND4J's capabilities.
        // Example for a flat, C-ordered buffer:
        // Buffer needs to be direct for Nd4j.create() with buffer.
        ByteBuffer bb = kompileNDArray.buffer();
        if (!bb.isDirect()) {
            ByteBuffer directBb = ByteBuffer.allocateDirect(bb.remaining());
            directBb.put(bb.slice()); // Use slice to preserve original buffer's position/limit
            directBb.flip();
            bb = directBb;
        }

        // Map Kompile NDArrayType to ND4J DataType
        org.nd4j.linalg.api.buffer.DataType nd4jDataType;
        switch (kompileNDArray.type()) {
            case FLOAT:
               nd4jDataType = org.nd4j.linalg.api.buffer.DataType.FLOAT; break;
            case DOUBLE: nd4jDataType = org.nd4j.linalg.api.buffer.DataType.DOUBLE; break;
            case INT32: case INT: nd4jDataType = org.nd4j.linalg.api.buffer.DataType.INT32; break;
             case LONG: nd4jDataType = org.nd4j.linalg.api.buffer.DataType.INT64; break;
            case BYTE: case INT8: nd4jDataType = org.nd4j.linalg.api.buffer.DataType.INT8; break;
            case UINT8: nd4jDataType = org.nd4j.linalg.api.buffer.DataType.UINT8; break;
            case SHORT: case INT16: nd4jDataType = org.nd4j.linalg.api.buffer.DataType.INT16; break;
            // Add more mappings as needed (UINT16, FLOAT16, BFLOAT16, BOOLEAN, UTF8 if supported by ND4J in this way)
            default:
                throw new UnsupportedOperationException("Unsupported Kompile NDArrayType for INDArray conversion: " + kompileNDArray.type());
        }
        // Assuming C order ('c') for simplicity. Stride calculation might be needed for other orders.
        DataBuffer buff = Nd4j.createBuffer(bb,nd4jDataType,0);

        return Nd4j.create(buff,kompileNDArray.shape());
    }

    /**
     * Converts an ND4J INDArray to a Kompile Pipelines NDArray.
     * This requires a concrete Kompile NDArray implementation.
     * This method should be implemented in a dedicated data conversion utility or module.
     *
     * @param indArray The ND4J INDArray.
     * @param name The desired name for the Kompile NDArray.
     * @return The corresponding Kompile NDArray.
     * @throws UnsupportedOperationException if the conversion is not supported.
     */
    protected NDArray convertFromINDArray(INDArray indArray, String name) {
        Objects.requireNonNull(indArray, "INDArray to convert cannot be null for name: " + name);
        // This is where you would instantiate your concrete Kompile NDArray implementation
        // (e.g., new ai.kompile.pipelines.data.nd4j.ND4JNDArray(indArray, name); if you create such a class)

        throw new UnsupportedOperationException(
                "Conversion from INDArray to Kompile NDArray not fully implemented. " +
                        "A concrete Kompile NDArray implementation (e.g., wrapping INDArray) is needed.");
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void close() throws Exception {
        // DL4J models (MultiLayerNetwork, ComputationGraph) do not have an explicit close() method.
        // Garbage collection handles native resources via INDArray deallocation.
        // If specific ND4J resources needed cleanup (e.g. workspaces), it would go here.
        mlnModel = null;
        cgModel = null;
        initialized = false;
    }
}