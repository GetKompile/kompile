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

package ai.kompile.pipelines.steps.onnx;

import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.NDArray; // Kompile Pipelines NDArray interface
import ai.kompile.pipelines.framework.api.data.NDArrayType;
import ai.kompile.pipelines.framework.core.config.ConfigAccessor;
import ai.kompile.pipelines.framework.core.config.SchemaRegistry;
import ai.kompile.pipelines.framework.api.configschema.StepSchema;
import ai.kompile.pipelines.steps.onnx.utils.ONNXUtils; // Kompile's ONNXUtils

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.*;
import org.bytedeco.onnxruntime.*;
import org.nd4j.common.base.Preconditions;
import org.nd4j.common.util.ArrayUtil;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

import static org.bytedeco.onnxruntime.global.onnxruntime.*;

/**
 * PipelineStepRunner for executing ONNX models using Bytedeco's direct ONNX Runtime bindings.
 */

public class OnnxRuntimeRunner implements PipelineStepRunner {

    private Session session;
    private RunOptions runOptions;
    private MemoryInfo memoryInfo;
    private OrtAllocator allocator; // Changed to OrtAllocator
    private SessionOptions sessionOptions;
    private static Env env; // Shared environment, initialized once

    private List<String> configuredInputNames;
    private List<String> configuredOutputNames;

    private String[] actualModelInputNames;
    private String[] actualModelOutputNames;

    // Maps the user-configured input name (Data key) to its corresponding index in the actualModelInputNames array.
    private Map<String, Integer> dataKeyToModelInputIndexMap;


    private boolean initialized = false;
    private File modelFileRef; // To hold reference to the model file for cleanup if it's from classpath

    public OnnxRuntimeRunner() {
        // Constructor for factory instantiation
    }

    @Override
    public void init(StepConfig stepConfig, Context context) throws Exception {
        StepSchema schema = SchemaRegistry.getInstance().getSchema(stepConfig.runnerClassName())
                .orElseThrow(() -> new IllegalStateException("No schema found for runner: " + stepConfig.runnerClassName()));
        ConfigAccessor config = new ConfigAccessor(stepConfig.getParameters(), schema);

        String modelUriString = config.getString(ONNXRunnerConstants.PARAM_MODEL_URI);
        this.configuredInputNames = config.getStringList(ONNXRunnerConstants.PARAM_INPUT_NAMES);
        this.configuredOutputNames = config.getStringList(ONNXRunnerConstants.PARAM_OUTPUT_NAMES);

        Preconditions.checkNotNull(modelUriString, "Model URI cannot be null.");
        Preconditions.checkNotNull(this.configuredInputNames, "Input names list cannot be null.");
        Preconditions.checkNotNull(this.configuredOutputNames, "Output names list cannot be null.");
        Preconditions.checkArgument(!this.configuredOutputNames.isEmpty(), "Output names list cannot be empty for a model that produces outputs.");


        this.modelFileRef = resolveModelPath(modelUriString, context);

        // Initialize ONNX Runtime Environment (shared and static)
        synchronized (OnnxRuntimeRunner.class) {
            if (env == null) {
                env = new Env(0, new BytePointer("kompile-onnx-env-" + UUID.randomUUID().toString()));
                env.retainReference();
            } else {
                env.retainReference(); // Retain for this runner instance
            }
        }

        // Initialize Session Options
        sessionOptions = new SessionOptions();
        sessionOptions.SetIntraOpNumThreads(Runtime.getRuntime().availableProcessors());
        sessionOptions.SetGraphOptimizationLevel(ORT_ENABLE_ALL);
        sessionOptions.retainReference();

        // Initialize Allocator - Changed to OrtAllocator
        allocator = new OrtAllocator();
        allocator.retainReference();

        // Create Session
        Pointer modelPathPointer = Loader.getPlatform().toLowerCase().startsWith("windows") ?
                new CharPointer(modelFileRef.getAbsolutePath()) :
                new BytePointer(modelFileRef.getAbsolutePath());
        try {
            session = new Session(env, modelPathPointer, sessionOptions);
            session.retainReference();
        } catch (Exception e) {
            if (allocator != null) allocator.releaseReference(); // allocator is OrtAllocator now
            if (sessionOptions != null) sessionOptions.releaseReference();
            synchronized (OnnxRuntimeRunner.class) {
                if (env != null && env.releaseReference()) {
                    env.close(); env = null;
                }
            }
            throw new IOException("Failed to initialize ONNX session for model: " + modelFileRef.getAbsolutePath(), e);
        }

        // Initialize Run Options
        runOptions = new RunOptions();
        runOptions.retainReference();

        // Create MemoryInfo (typically for CPU)
        memoryInfo = MemoryInfo.CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);

        // Get actual input node names from the model
        long numModelInputs = session.GetInputCount();
        this.actualModelInputNames = new String[(int) numModelInputs];
        for (int i = 0; i < numModelInputs; i++) {
            // Pass OrtAllocator directly
            try (BytePointer namePtr = session.GetInputNameAllocated(i, allocator)) {
                this.actualModelInputNames[i] = namePtr.getString();
            }
        }

        if (numModelInputs > 0 && (this.configuredInputNames == null || this.configuredInputNames.isEmpty())) {
            throw new IllegalArgumentException(
                    String.format("Model expects %d inputs, but 'inputNames' parameter is empty or not configured.", numModelInputs)
            );
        }
        if (this.configuredInputNames != null && this.configuredInputNames.size() != numModelInputs && numModelInputs > 0) {
            throw new IllegalArgumentException(
                    String.format("Mismatch: Configured 'inputNames' count (%d) does not match model's actual input count (%d). Configured: %s, Model: %s",
                            this.configuredInputNames.size(), numModelInputs, this.configuredInputNames, Arrays.toString(this.actualModelInputNames))
            );
        }

        this.dataKeyToModelInputIndexMap = new HashMap<>();
        if (numModelInputs > 0 && this.configuredInputNames != null) {
            for (int i = 0; i < this.actualModelInputNames.length; ++i) {
                if (i < this.configuredInputNames.size()) {
                    String configuredName = this.configuredInputNames.get(i);
                    if (!configuredName.equals(this.actualModelInputNames[i])) {
                        throw new IllegalArgumentException(String.format(
                                "Order mismatch or name mismatch: Configured input name '%s' at index %d does not match model's input name '%s' at the same index. " +
                                        "Please ensure 'inputNames' in the configuration match the order and names of the ONNX model's inputs. Model inputs: %s",
                                configuredName, i, this.actualModelInputNames[i], Arrays.toString(this.actualModelInputNames)
                        ));
                    }
                    this.dataKeyToModelInputIndexMap.put(configuredName, i);
                } else {
                    throw new IllegalStateException("Logic error: Fewer configuredInputNames than actualModelInputNames during mapping.");
                }
            }
        }

        // Get actual output node names from the model
        long numModelOutputs = session.GetOutputCount();
        this.actualModelOutputNames = new String[(int) numModelOutputs];
        for (int i = 0; i < numModelOutputs; i++) {
            try(BytePointer namePtr = session.GetOutputNameAllocated(i, allocator)) { // Pass OrtAllocator directly
                this.actualModelOutputNames[i] = namePtr.getString();
            }
        }


        this.initialized = true;
    }

    private File resolveModelPath(String modelUriString, Context context) throws Exception {
        URI uri = new URI(modelUriString);
        File modelFile;
        String scheme = uri.getScheme();

        if ("file".equalsIgnoreCase(scheme) || scheme == null) {
            modelFile = new File(uri.isAbsolute() ? uri.getPath() : modelUriString);
        } else if ("classpath".equalsIgnoreCase(scheme)) {
            String resourcePath = uri.getSchemeSpecificPart();
            if (resourcePath.startsWith("//")) resourcePath = resourcePath.substring(2);
            if (resourcePath.startsWith("/")) resourcePath = resourcePath.substring(1);

            try (InputStream resourceStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
                if (resourceStream == null) {
                    throw new FileNotFoundException("ONNX model resource not found in classpath at: " + resourcePath +
                            " (derived from URI: " + modelUriString + ")");
                }
                Path tempDir = context.get("kompile.tempDir", Path.class)
                        .orElseGet(() -> {
                            try {
                                Path defaultTemp = Files.createTempDirectory("kompile-pipelines-global-temp-");
                                return defaultTemp;
                            } catch (IOException e) {
                                throw new RuntimeException("Failed to create default temp directory", e);
                            }
                        });
                if(!Files.exists(tempDir)) Files.createDirectories(tempDir);

                Path tempModelFile = Files.createTempFile(tempDir, "kompile-onnx-model-", ".onnx");
                Files.copy(resourceStream, tempModelFile, StandardCopyOption.REPLACE_EXISTING);
                modelFile = tempModelFile.toFile();
            }
        } else {
            throw new IllegalArgumentException("Unsupported URI scheme for ONNX model: " + modelUriString +
                    ". Supported schemes are 'file' and 'classpath'.");
        }

        if (!modelFile.exists() || !modelFile.isFile()) {
            throw new FileNotFoundException("ONNX Model file not found or is not a regular file: " + modelFile.getAbsolutePath());
        }
        return modelFile;
    }


    @Override
    public Data exec(Data input, Context context) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("ONNXRunner (Bytedeco) not initialized.");
        }
        Objects.requireNonNull(input, "Input Data object cannot be null.");
        Objects.requireNonNull(session, "ONNX Session is not initialized.");

        Data outputData = Data.empty();

        Value inputTensorContainer = null;
        List<Value> individualInputTensors = new ArrayList<>();

        BytePointer[] inputNameBytePointers = new BytePointer[this.actualModelInputNames.length];

        if (this.actualModelInputNames.length > 0) {
            inputTensorContainer = new Value(this.actualModelInputNames.length);

            for (int i = 0; i < this.actualModelInputNames.length; i++) {
                String actualModelInputName = this.actualModelInputNames[i];
                String dataKeyForThisModelInput = this.configuredInputNames.get(i);

                if (!dataKeyForThisModelInput.equals(actualModelInputName)) {
                    throw new IllegalStateException(String.format(
                            "Mismatch during exec: Configured input name '%s' (Data key) at index %d does not match expected model input name '%s' at the same index. " +
                                    "This indicates an issue with input name mapping established during init.",
                            dataKeyForThisModelInput, i, actualModelInputName
                    ));
                }

                NDArray kompileNDArray = input.getNDArray(dataKeyForThisModelInput);
                if (kompileNDArray == null) {
                    throw new IllegalArgumentException("Input Data is missing required NDArray for key: '" + dataKeyForThisModelInput +
                            "' (expected for model input '" + actualModelInputName + "').");
                }
                INDArray nativeInput = convertToINDArray(kompileNDArray, actualModelInputName);

                Value tensor = ONNXUtils.getTensorValue(nativeInput, memoryInfo);
                individualInputTensors.add(tensor);

                inputTensorContainer.position(i).put(tensor);

                inputNameBytePointers[i] = new BytePointer(actualModelInputName);
            }
            if (this.actualModelInputNames.length > 0) {
                inputTensorContainer.position(0);
            }
        }


        BytePointer[] outputNameBytePointers = new BytePointer[this.actualModelOutputNames.length];
        for (int i = 0; i < this.actualModelOutputNames.length; ++i) {
            outputNameBytePointers[i] = new BytePointer(this.actualModelOutputNames[i]);
        }

        ValueVector outputVector = null;
        try (PointerPointer<BytePointer> inputNodeNamesPtr = new PointerPointer<>(inputNameBytePointers);
             PointerPointer<BytePointer> outputNodeNamesPtr = new PointerPointer<>(outputNameBytePointers)) {

            outputVector = session.Run(
                    runOptions,
                    inputNodeNamesPtr,
                    inputTensorContainer,
                    this.actualModelInputNames.length,
                    outputNodeNamesPtr,
                    this.actualModelOutputNames.length
            );
            if (outputVector != null) {
                outputVector.retainReference();
            }

        } catch (Exception e) {
            throw e;
        } finally {
            for (Value val : individualInputTensors) {
                if (val != null && !val.isNull()) {
                    val.close();
                }
            }
            if (inputTensorContainer != null && !inputTensorContainer.isNull()) {
                inputTensorContainer.close();
            }
        }

        if (outputVector == null) {
            throw new RuntimeException("ONNX model execution returned null output vector.");
        }


        try {
            for (int i = 0; i < outputVector.size(); i++) {
                try (Value outValue = outputVector.get(i)) {
                    String actualOutputNodeName = this.actualModelOutputNames[i];
                    INDArray nd4jOutput = ONNXUtils.getArrayFromValue(outValue);
                    NDArray kompileNDArray = convertFromINDArray(nd4jOutput, actualOutputNodeName);

                    String outputDataKey = actualOutputNodeName;
                    if (this.configuredOutputNames.size() == this.actualModelOutputNames.length && i < this.configuredOutputNames.size()) {
                        outputDataKey = this.configuredOutputNames.get(i);
                    }
                    if (this.configuredOutputNames.contains(outputDataKey)) {
                        outputData.put(outputDataKey, kompileNDArray);
                    } else if (this.configuredOutputNames.size() != this.actualModelOutputNames.length && this.configuredOutputNames.contains(actualOutputNodeName)) {
                        outputData.put(actualOutputNodeName, kompileNDArray);
                    }
                }
            }
        } finally {
            if (outputVector != null && !outputVector.isNull()) {
                outputVector.releaseReference();
                outputVector.close();
            }
        }
        return outputData;
    }


    // --- Kompile NDArray <-> ND4J INDArray Conversion Helpers ---
    protected INDArray convertToINDArray(NDArray kompileNDArray, String name) {
        Objects.requireNonNull(kompileNDArray, "Kompile NDArray to convert cannot be null for name: " + name);
        if (kompileNDArray.getNative() instanceof INDArray) {
            return (INDArray) kompileNDArray.getNative();
        }

        ByteBuffer bb = kompileNDArray.buffer().order(ByteOrder.nativeOrder());
        if (!bb.isDirect()) {
            ByteBuffer directBb = ByteBuffer.allocateDirect(bb.remaining()).order(ByteOrder.nativeOrder());
            directBb.put(bb.slice());
            directBb.flip();
            bb = directBb;
        }

        DataType nd4jDataType;
        NDArrayType sourceKompileType = kompileNDArray.type();

        switch (sourceKompileType) {
            case FLOAT:    nd4jDataType = DataType.FLOAT; break;
            case DOUBLE:   nd4jDataType = DataType.DOUBLE; break;
            case INT:
            case INT32:    nd4jDataType = DataType.INT32; break;
            case LONG:     // Kompile's LONG is an alias for INT64
                nd4jDataType = DataType.INT64; break;
            case BYTE:
            case INT8:     nd4jDataType = DataType.INT8; break;
            case UINT8:    nd4jDataType = DataType.UINT8; break;
            case SHORT:
            case INT16:    nd4jDataType = DataType.INT16; break;
            case UINT16:   nd4jDataType = DataType.UINT16; break;
            case UINT32:   nd4jDataType = DataType.UINT32; break;
            case UINT64:   nd4jDataType = DataType.UINT64; break;
            case BOOLEAN:  nd4jDataType = DataType.BOOL; break;
            case FLOAT16:  nd4jDataType = DataType.HALF; break;
            case BFLOAT16: nd4jDataType = DataType.BFLOAT16; break;
            case UTF8:
            default:
                throw new UnsupportedOperationException(
                        "Unsupported Kompile NDArrayType '" + sourceKompileType + "' for INDArray conversion for input '" + name + "'.");
        }

        long[] shape = kompileNDArray.shape();
        if (shape == null || shape.length == 0) {
            if (kompileNDArray.length() == 1) shape = new long[]{1};
            else if (kompileNDArray.length() == 0) return Nd4j.empty(nd4jDataType);
            else throw new IllegalArgumentException("Cannot determine INDArray shape from Kompile NDArray with null/empty shape and length > 1");
        }

        org.nd4j.linalg.api.buffer.DataBuffer dataBuffer = Nd4j.createBuffer(bb,nd4jDataType, ArrayUtil.prod(shape));
        return Nd4j.create(dataBuffer, shape, Nd4j.getStrides(shape, 'c'), 0L, 'c', nd4jDataType);
    }

    protected NDArray convertFromINDArray(INDArray indArray, String name) {
        Objects.requireNonNull(indArray, "INDArray to convert cannot be null for name: " + name);
        final NDArrayType kompileType;
        DataType sourceNd4jType = indArray.dataType();

        switch (sourceNd4jType) {
            case FLOAT:    kompileType = NDArrayType.FLOAT; break;
            case DOUBLE:   kompileType = NDArrayType.DOUBLE; break;
            case INT:      kompileType = NDArrayType.INT32; break;
            case LONG:     kompileType = NDArrayType.LONG; break;
            case BYTE:     kompileType = NDArrayType.INT8; break;
            case UBYTE:    kompileType = NDArrayType.UINT8; break;
            case SHORT:    kompileType = NDArrayType.INT16; break;
            case UINT16:   kompileType = NDArrayType.UINT16; break;
            case UINT32:     kompileType = NDArrayType.UINT32; break;
            case UINT64:    kompileType = NDArrayType.UINT64; break;
            case BOOL:     kompileType = NDArrayType.BOOLEAN; break;
            case HALF:     kompileType = NDArrayType.FLOAT16; break;
            case BFLOAT16: kompileType = NDArrayType.BFLOAT16; break;
            case UTF8:     kompileType = NDArrayType.UTF8; break;
            case COMPRESSED:
            case UNKNOWN:
            default:
                throw new UnsupportedOperationException(
                        "Unsupported ND4J DataType '" + sourceNd4jType + "' for Kompile NDArray conversion for output '" + name + "'.");
        }

        INDArray contiguousIndArray = indArray.dup('c');
        ByteBuffer bb = contiguousIndArray.data().asNio().order(ByteOrder.nativeOrder());

        ByteBuffer ownedBuffer = ByteBuffer.allocateDirect(bb.remaining()).order(ByteOrder.nativeOrder());
        ownedBuffer.put(bb.slice());
        ownedBuffer.flip();

        final String finalName = name;
        final long[] finalShape = indArray.shape().clone();
        final ByteBuffer finalBuffer = ownedBuffer;
        final INDArray nativeRef = contiguousIndArray;

        return new NDArray() {
            @Override public String name() { return finalName; }
            @Override public long[] shape() { return finalShape; }
            @Override public NDArrayType type() { return kompileType; }
            @Override public ByteBuffer buffer() { return finalBuffer.asReadOnlyBuffer(); }
            @Override public <T> T getNative() { return (T) nativeRef; }
        };
    }
    // --- End Conversion Helpers ---

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void close() throws Exception {
        // Close resources in reverse order of creation, and null-check
        if (memoryInfo != null && !memoryInfo.isNull()) {
            memoryInfo.close(); memoryInfo = null;
        }
        if (runOptions != null && !runOptions.isNull()) {
            runOptions.releaseReference(); runOptions.close(); runOptions = null;
        }
        if (session != null && !session.isNull()) {
            session.releaseReference(); session.close(); session = null;
        }
        if (allocator != null && !allocator.isNull()) { // allocator is OrtAllocator
            allocator.releaseReference(); allocator.close(); allocator = null;
        }
        if (sessionOptions != null && !sessionOptions.isNull()) {
            sessionOptions.releaseReference(); sessionOptions.close(); sessionOptions = null;
        }

        synchronized (OnnxRuntimeRunner.class) {
            if (env != null && !env.isNull()) {
                if (env.releaseReference()) {
                    env.close();
                    env = null;
                }
            }
        }

        if (modelFileRef != null && modelFileRef.getName().startsWith("kompile-onnx-model-")) {
            try {
                Path tempFileParentDir = modelFileRef.getParentFile().toPath();
                if (Files.exists(modelFileRef.toPath())) {
                    Files.delete(modelFileRef.toPath());
                }
                if (tempFileParentDir.getFileName().toString().startsWith("kompile-pipelines-temp-") ||
                        tempFileParentDir.getFileName().toString().startsWith("kompile-pipelines-global-temp-")) {
                    try (var stream = Files.list(tempFileParentDir)) {
                        if (stream.findAny().isEmpty()) {
                            Files.delete(tempFileParentDir);
                        }
                    } catch (IOException e) {
                    }
                }
            } catch (IOException e) {
            }
        }

        initialized = false;
    }
}
