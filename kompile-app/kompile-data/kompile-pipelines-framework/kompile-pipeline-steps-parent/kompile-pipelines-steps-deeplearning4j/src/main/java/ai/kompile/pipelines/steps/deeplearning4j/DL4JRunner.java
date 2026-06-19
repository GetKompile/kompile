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

package ai.kompile.pipelines.steps.deeplearning4j;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.configschema.StepSchema;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.NDArray;
import ai.kompile.pipelines.framework.api.data.NDArrayType;
import ai.kompile.pipelines.framework.core.config.ConfigAccessor;
import ai.kompile.pipelines.framework.core.config.SchemaRegistry;
import ai.kompile.pipelines.framework.core.utils.NDArrayTypeConverter;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.util.ModelSerializer;
import org.nd4j.common.util.ArrayUtil;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;


import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
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
                modelFile = uri.getScheme() == null ? new File(modelUriString) : new File(uri.getPath());
            } else if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
                modelFile = downloadModelToTempFile(uri, modelUriString);
            } else if ("s3".equalsIgnoreCase(uri.getScheme())) {
                // Convert s3://bucket/key to HTTPS URL (S3 public or pre-signed URL pattern)
                String bucket = uri.getHost();
                String key = uri.getPath().startsWith("/") ? uri.getPath().substring(1) : uri.getPath();
                URI httpsUri = new URI("https", bucket + ".s3.amazonaws.com", "/" + key, null);
                modelFile = downloadModelToTempFile(httpsUri, modelUriString);
            } else {
                throw new IllegalArgumentException("Unsupported URI scheme for model: " + modelUriString +
                        ". Supported schemes: file, http, https, s3.");
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
        DataType nd4jDataType = NDArrayTypeConverter.toNd4jDataType(kompileNDArray.type());
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

        final NDArrayType kompileType = mapNd4jToKompileType(indArray.dataType(), name);

        // Ensure the INDArray is C-contiguous for reliable buffer access
        INDArray contiguousIndArray = (indArray.isView() || indArray.ordering() != 'c') ? indArray.dup('c') : indArray;

        // Get a direct ByteBuffer view or copy
        ByteBuffer bb = contiguousIndArray.data().asNio();
        ByteBuffer ownedBuffer;
        if (bb.isDirect()) {
            ownedBuffer = bb.slice().order(ByteOrder.nativeOrder());
        } else {
            ownedBuffer = ByteBuffer.allocateDirect(bb.remaining()).order(ByteOrder.nativeOrder());
            ownedBuffer.put(bb.slice());
            ownedBuffer.flip();
        }

        final String finalName = name;
        final long[] finalShape = indArray.shape().clone();
        final ByteBuffer finalBuffer = ownedBuffer.asReadOnlyBuffer();
        final INDArray nativeRef = indArray;

        return new NDArray() {
            @Override public String name() { return finalName; }
            @Override public long[] shape() { return finalShape; }
            @Override public NDArrayType type() { return kompileType; }
            @Override public ByteBuffer buffer() { return finalBuffer.duplicate(); }
            @SuppressWarnings("unchecked")
            @Override public <T> T getNative() { return (T) nativeRef; }
            @Override public long length() { return ArrayUtil.prod(finalShape); }
            @Override public int bufferSizeInBytes() { return finalBuffer.remaining(); }
        };
    }

    private static NDArrayType mapNd4jToKompileType(DataType nd4jType, String name) {
        return NDArrayTypeConverter.fromNd4jDataType(nd4jType);
    }

    private File downloadModelToTempFile(URI uri, String originalUri) throws IOException {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofMinutes(10))
                    .GET()
                    .build();

            Path tempFile = Files.createTempFile("dl4j-model-", ".bin");
            tempFile.toFile().deleteOnExit();

            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                throw new IOException("HTTP error " + response.statusCode() + " downloading model from: " + originalUri);
            }

            try (InputStream is = response.body()) {
                Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            return tempFile.toFile();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted for model: " + originalUri, e);
        }
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