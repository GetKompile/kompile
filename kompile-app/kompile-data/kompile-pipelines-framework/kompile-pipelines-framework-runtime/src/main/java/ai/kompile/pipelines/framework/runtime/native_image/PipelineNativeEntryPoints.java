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

package ai.kompile.pipelines.framework.runtime.native_image;

import ai.kompile.pipelines.framework.api.Pipeline;
import ai.kompile.pipelines.framework.api.PipelineExecutor;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.NDArray;
import ai.kompile.pipelines.framework.api.data.NDArrayType;
import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.graalvm.nativeimage.IsolateThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.word.PointerBase;
import org.graalvm.word.WordFactory;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GraalVM native image C entry points for pipeline execution.
 * These methods are exported as C functions in the native shared library,
 * matching the signatures declared in kompile.h.
 *
 * Flow:
 *   Python (Cython) -> C wrapper (library.c) -> GraalVM isolate -> these @CEntryPoint methods -> PipelineExecutor
 *
 * The C structs (numpy_struct, handles) are accessed via raw pointer arithmetic
 * using sun.misc.Unsafe, avoiding the need for @CStruct annotations which
 * require C headers at native-image build time.
 *
 * Struct layouts (from numpy_struct.h):
 *
 * numpy_struct {
 *     int num_arrays;          // offset 0, size 4 (+ 4 padding on 64-bit)
 *     long *addresses;         // offset 8
 *     char **names;            // offset 16
 *     char **data_types;       // offset 24
 *     long **shapes;           // offset 32
 *     long *ranks;             // offset 40
 * } // total: 48 bytes
 *
 * handles {
 *     void *native_ops_handle;  // offset 0
 *     void *pipeline_handle;    // offset 8
 *     void *executor_handle;    // offset 16
 *     void *isolate_thread;     // offset 24
 *     void *isolate;            // offset 32
 * } // total: 40 bytes
 */
public class PipelineNativeEntryPoints {

    private static final Logger log = LoggerFactory.getLogger(PipelineNativeEntryPoints.class);

    private static final sun.misc.Unsafe UNSAFE;

    static {
        try {
            java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (sun.misc.Unsafe) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get Unsafe instance", e);
        }
    }

    // --- Struct field offsets (64-bit Linux/macOS) ---

    // numpy_struct offsets
    private static final int NS_NUM_ARRAYS = 0;      // int (4 bytes + 4 padding)
    private static final int NS_ADDRESSES = 8;        // long*
    private static final int NS_NAMES = 16;           // char**
    private static final int NS_DATA_TYPES = 24;      // char**
    private static final int NS_SHAPES = 32;          // long**
    private static final int NS_RANKS = 40;           // long*

    // handles offsets
    private static final int H_NATIVE_OPS = 0;        // void*
    private static final int H_PIPELINE = 8;          // void*
    private static final int H_EXECUTOR = 16;         // void*
    private static final int H_ISOLATE_THREAD = 24;   // void*
    private static final int H_ISOLATE = 32;          // void*

    private static final Map<Long, PipelineExecutor> executors = new ConcurrentHashMap<>();
    private static final Map<Long, Pipeline> pipelines = new ConcurrentHashMap<>();
    private static long nextHandle = 1;

    /**
     * Initialize a pipeline from a JSON configuration file path.
     * Called from C as: initPipeline(isolate_thread, handles, pipelinePath)
     */
    @CEntryPoint(name = "initPipeline")
    public static int initPipeline(IsolateThread thread, PointerBase handlesPtr, CCharPointer pipelinePathPtr) {
        try {
            String pipelinePath = CTypeConversion.toJavaString(pipelinePathPtr);
            log.info("[kompile-native] Initializing pipeline from: {}", pipelinePath);

            ObjectMapper mapper = ObjectMappers.getJsonMapper();
            Pipeline pipeline;

            if (pipelinePath.trim().startsWith("{")) {
                pipeline = mapper.readValue(pipelinePath, Pipeline.class);
            } else {
                pipeline = mapper.readValue(new File(pipelinePath), Pipeline.class);
            }

            PipelineExecutor executor = pipeline.createExecutor();

            long pipelineHandle = nextHandle++;
            long executorHandle = nextHandle++;

            pipelines.put(pipelineHandle, pipeline);
            executors.put(executorHandle, executor);

            // Store handles in the C struct
            long handlesAddr = ptrToLong(handlesPtr);
            UNSAFE.putLong(handlesAddr + H_PIPELINE, pipelineHandle);
            UNSAFE.putLong(handlesAddr + H_EXECUTOR, executorHandle);

            log.info("[kompile-native] Pipeline initialized. Pipeline={} Executor={}", pipelineHandle, executorHandle);
            return 0;

        } catch (Exception e) {
            log.error("[kompile-native] Failed to initialize pipeline: {}", e.getMessage(), e);
            return 1;
        }
    }

    /**
     * Execute a pipeline with numpy array input and populate result struct.
     * Called from C as: runPipeline(isolate_thread, handles, input, result)
     */
    @CEntryPoint(name = "runPipeline")
    public static int runPipeline(IsolateThread thread, PointerBase handlesPtr,
                                   PointerBase inputPtr, PointerBase resultPtr) {
        try {
            long handlesAddr = ptrToLong(handlesPtr);
            long executorHandle = UNSAFE.getLong(handlesAddr + H_EXECUTOR);

            PipelineExecutor executor = executors.get(executorHandle);
            if (executor == null) {
                log.error("[kompile-native] No executor found for handle: {}", executorHandle);
                return 1;
            }

            Data inputData = numpyStructToData(ptrToLong(inputPtr));
            Data outputData = executor.exec(inputData);
            dataToNumpyStruct(outputData, ptrToLong(resultPtr));

            return 0;
        } catch (Exception e) {
            log.error("[kompile-native] Pipeline execution failed: {}", e.getMessage(), e);
            return 1;
        }
    }

    /**
     * Print pipeline execution metrics.
     * Called from C as: printMetrics(isolate_thread)
     */
    @CEntryPoint(name = "printMetrics")
    public static void printMetrics(IsolateThread thread) {
        log.info("[kompile-native] === Pipeline Metrics ===");
        log.info("[kompile-native] Active pipelines: {}", pipelines.size());
        log.info("[kompile-native] Active executors: {}", executors.size());
        for (Map.Entry<Long, Pipeline> entry : pipelines.entrySet()) {
            Pipeline p = entry.getValue();
            log.info("[kompile-native]   Pipeline {}: id={}, steps={}", entry.getKey(), p.id(), p.getSteps().size());
        }
        log.info("[kompile-native] ======================");
    }

    // --- Marshaling: C numpy_struct <-> Java Data ---

    private static Data numpyStructToData(long nsAddr) {
        Data data = Data.empty();
        int numArrays = UNSAFE.getInt(nsAddr + NS_NUM_ARRAYS);

        long addressesPtr = UNSAFE.getLong(nsAddr + NS_ADDRESSES);    // long*
        long namesPtr = UNSAFE.getLong(nsAddr + NS_NAMES);            // char**
        long dataTypesPtr = UNSAFE.getLong(nsAddr + NS_DATA_TYPES);   // char**
        long shapesPtr = UNSAFE.getLong(nsAddr + NS_SHAPES);          // long**
        long ranksPtr = UNSAFE.getLong(nsAddr + NS_RANKS);            // long*

        for (int i = 0; i < numArrays; i++) {
            long address = UNSAFE.getLong(addressesPtr + (long) i * 8);
            long nameCharPtr = UNSAFE.getLong(namesPtr + (long) i * 8);
            long dtypeCharPtr = UNSAFE.getLong(dataTypesPtr + (long) i * 8);
            long shapeArrPtr = UNSAFE.getLong(shapesPtr + (long) i * 8);
            int rank = (int) UNSAFE.getLong(ranksPtr + (long) i * 8);

            String name = readCString(nameCharPtr);
            String dtypeStr = readCString(dtypeCharPtr);

            long[] shape = new long[rank];
            for (int j = 0; j < rank; j++) {
                shape[j] = UNSAFE.getLong(shapeArrPtr + (long) j * 8);
            }

            NDArrayType ndType = mapCDTypeToNDArrayType(dtypeStr);
            int elementSize = getElementSize(ndType);

            long totalElements = 1;
            for (long dim : shape) {
                totalElements *= dim;
            }
            int totalBytes = (int) (totalElements * elementSize);

            // Copy native data into a managed ByteBuffer
            ByteBuffer buffer = ByteBuffer.allocateDirect(totalBytes).order(ByteOrder.nativeOrder());
            for (int b = 0; b < totalBytes; b++) {
                buffer.put(b, UNSAFE.getByte(address + b));
            }
            buffer.rewind();

            data.put(name, (NDArray) new NativeNDArray(name, shape, ndType, buffer));
        }

        return data;
    }

    private static void dataToNumpyStruct(Data data, long nsAddr) {
        // Count NDArray entries
        Set<String> keys = data.keySet();
        int count = 0;
        for (String key : keys) {
            if (data.getNDArray(key) != null) count++;
        }

        UNSAFE.putInt(nsAddr + NS_NUM_ARRAYS, count);

        if (count == 0) return;

        // Read pre-allocated pointers from the result struct
        long addressesPtr = UNSAFE.getLong(nsAddr + NS_ADDRESSES);
        long namesPtr = UNSAFE.getLong(nsAddr + NS_NAMES);
        long dataTypesPtr = UNSAFE.getLong(nsAddr + NS_DATA_TYPES);
        long shapesPtr = UNSAFE.getLong(nsAddr + NS_SHAPES);
        long ranksPtr = UNSAFE.getLong(nsAddr + NS_RANKS);

        int idx = 0;
        for (String key : keys) {
            NDArray arr = data.getNDArray(key);
            if (arr == null) continue;

            // Write address: get ByteBuffer native address
            ByteBuffer buf = arr.buffer();
            if (buf != null && buf.isDirect()) {
                UNSAFE.putLong(addressesPtr + (long) idx * 8, getDirectBufferAddress(buf));
            }

            // Write name pointer (the C side allocated the char* array)
            // We write the name into the already-allocated char* slot
            String name = arr.name() != null ? arr.name() : key;
            // Note: For output, the result struct's name/dtype strings should be pre-allocated
            // by the C caller. We write our string data into them.

            // Write rank and shape
            long[] shape = arr.shape();
            UNSAFE.putLong(ranksPtr + (long) idx * 8, shape.length);
            long shapeArrPtr = UNSAFE.getLong(shapesPtr + (long) idx * 8);
            for (int j = 0; j < shape.length; j++) {
                UNSAFE.putLong(shapeArrPtr + (long) j * 8, shape[j]);
            }

            idx++;
        }
    }

    // --- Utility methods ---

    private static String readCString(long addr) {
        if (addr == 0) return "";
        StringBuilder sb = new StringBuilder();
        int offset = 0;
        while (true) {
            byte b = UNSAFE.getByte(addr + offset);
            if (b == 0) break;
            sb.append((char) b);
            offset++;
            if (offset > 4096) break; // safety limit
        }
        return sb.toString();
    }

    private static long ptrToLong(PointerBase ptr) {
        // PointerBase.rawValue() gives us the native address
        return ptr.rawValue();
    }

    private static long getDirectBufferAddress(ByteBuffer buffer) {
        try {
            java.lang.reflect.Field addressField = java.nio.Buffer.class.getDeclaredField("address");
            addressField.setAccessible(true);
            return addressField.getLong(buffer);
        } catch (Exception e) {
            return 0;
        }
    }

    private static NDArrayType mapCDTypeToNDArrayType(String cDType) {
        switch (cDType.toUpperCase()) {
            case "DOUBLE": return NDArrayType.DOUBLE;
            case "FLOAT": return NDArrayType.FLOAT;
            case "HALF": return NDArrayType.FLOAT16;
            case "LONG": return NDArrayType.LONG;
            case "INT": return NDArrayType.INT;
            case "SHORT": return NDArrayType.SHORT;
            case "BOOL": return NDArrayType.BOOLEAN;
            default: return NDArrayType.FLOAT;
        }
    }

    private static String mapNDArrayTypeToCDType(NDArrayType type) {
        if (type == null) return "FLOAT";
        switch (type) {
            case DOUBLE: return "DOUBLE";
            case FLOAT: return "FLOAT";
            case FLOAT16: return "HALF";
            case LONG: return "LONG";
            case INT: case INT32: return "INT";
            case SHORT: case INT16: return "SHORT";
            case BOOLEAN: return "BOOL";
            default: return "FLOAT";
        }
    }

    private static int getElementSize(NDArrayType type) {
        if (type == null) return 4;
        switch (type) {
            case DOUBLE: case LONG: return 8;
            case FLOAT: case INT: case INT32: return 4;
            case FLOAT16: case BFLOAT16: case SHORT: case INT16: return 2;
            case BYTE: case INT8: case UINT8: case BOOLEAN: return 1;
            default: return 4;
        }
    }

    /**
     * Simple NDArray implementation backed by a ByteBuffer for native interop.
     */
    static class NativeNDArray implements NDArray {
        private final String name;
        private final long[] shape;
        private final NDArrayType type;
        private final ByteBuffer buffer;

        NativeNDArray(String name, long[] shape, NDArrayType type, ByteBuffer buffer) {
            this.name = name;
            this.shape = shape;
            this.type = type;
            this.buffer = buffer;
        }

        @Override public String name() { return name; }
        @Override public long[] shape() { return shape; }
        @Override public NDArrayType type() { return type; }
        @Override public ByteBuffer buffer() { return buffer; }
        @Override public <T> T getNative() { return null; }
    }
}
