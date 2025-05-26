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

package ai.kompile.pipelines.steps.onnx.utils;

import org.bytedeco.javacpp.*;
import org.bytedeco.javacpp.indexer.*;
import org.bytedeco.onnxruntime.MemoryInfo;
import org.bytedeco.onnxruntime.Value;
import org.bytedeco.onnxruntime.LongVector; // For shape from GetShape
import org.bytedeco.onnxruntime.OrtAllocator;
import org.bytedeco.onnxruntime.TypeInfo;
import org.bytedeco.onnxruntime.TensorTypeAndShapeInfo;


import org.nd4j.common.base.Preconditions;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;


import static org.bytedeco.onnxruntime.global.onnxruntime.*;
import static org.nd4j.linalg.api.buffer.DataType.*;

public class ONNXUtils {

    private ONNXUtils() {}

    /**
     * Validates that the INDArray data type matches the expected ONNX data type.
     * @param expected The expected ND4J DataType.
     * @param array The INDArray to validate.
     */
    public static void validateDataType(DataType expected, INDArray array) {
        if (!array.dataType().equals(expected))
            throw new RuntimeException("INDArray data type (" + array.dataType() + ") does not match required ONNX data type (" + expected + ")");
    }

    /**
     * Returns the ND4J DataType equivalent for an ONNX tensor element data type constant.
     * @param onnxDataType ONNX_TENSOR_ELEMENT_DATA_TYPE_* constant.
     * @return Equivalent ND4J DataType.
     */
    public static DataType dataTypeForOnnxType(int onnxDataType) {
        switch (onnxDataType) {
            case ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT:   return FLOAT;
            case ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT8:   return UINT8;
            case ONNX_TENSOR_ELEMENT_DATA_TYPE_INT8:    return INT8;
            case ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT16:  return UINT16;
            case ONNX_TENSOR_ELEMENT_DATA_TYPE_INT16:   return INT16;
            case ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32:   return INT32;
            case ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64:   return INT64;
            case ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL:    return BOOL;
            case ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT16: return FLOAT16;
            case ONNX_TENSOR_ELEMENT_DATA_TYPE_DOUBLE:  return DOUBLE;
            case ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT32:  return UINT32;
            case ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT64:  return UINT64;
            case ONNX_TENSOR_ELEMENT_DATA_TYPE_BFLOAT16:return BFLOAT16;
            case ONNX_TENSOR_ELEMENT_DATA_TYPE_STRING:
                throw new IllegalArgumentException("ONNX_TENSOR_ELEMENT_DATA_TYPE_STRING not directly supported by ND4J numerical INDArrays.");
            default:
                throw new IllegalArgumentException("Unsupported ONNX tensor element data type: " + onnxDataType);
        }
    }

    /**
     * Returns the ONNX tensor element data type constant for an ND4J DataType.
     * @param dataType ND4J DataType.
     * @return Equivalent ONNX_TENSOR_ELEMENT_DATA_TYPE_* constant.
     */
    public static int onnxTypeForDataType(DataType dataType) {
        if (dataType == FLOAT) return ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT;
        if (dataType == UINT8) return ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT8;
        if (dataType == INT8) return ONNX_TENSOR_ELEMENT_DATA_TYPE_INT8;
        if (dataType == UINT16) return ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT16;
        if (dataType == INT16) return ONNX_TENSOR_ELEMENT_DATA_TYPE_INT16;
        if (dataType == INT32) return ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32;
        if (dataType == INT64) return ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64;
        if (dataType == BOOL) return ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL;
        if (dataType == FLOAT16) return ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT16;
        if (dataType == DOUBLE) return ONNX_TENSOR_ELEMENT_DATA_TYPE_DOUBLE;
        if (dataType == UINT32) return ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT32;
        if (dataType == UINT64) return ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT64;
        if (dataType == BFLOAT16) return ONNX_TENSOR_ELEMENT_DATA_TYPE_BFLOAT16;
        throw new IllegalArgumentException("Unsupported ND4J DataType for ONNX conversion: " + dataType);
    }

    /**
     * Converts an ONNX {@link Value} (tensor) into an ND4J {@link INDArray}.
     * @param value The ONNX Value to convert.
     * @return The corresponding INDArray.
     */
    public static INDArray getArrayFromValue(Value value) {
        Preconditions.checkNotNull(value, "Input ONNX Value cannot be null.");
        Preconditions.checkArgument(!value.isNull(), "Input ONNX Value native pointer is null.");
        Preconditions.checkArgument(value.IsTensor(), "ONNX Value is not a tensor, cannot convert to INDArray.");

        TensorTypeAndShapeInfo typeAndShapeInfo = value.GetTensorTypeAndShapeInfo();
        DataType dataType = dataTypeForOnnxType(typeAndShapeInfo.GetElementType());

        long[] shape;
        // LongVector from GetShape() is AutoCloseable
        try (LongVector shapeVector = typeAndShapeInfo.GetShape()) {
            int rank = (int) shapeVector.size();
            if (rank == 0 && typeAndShapeInfo.GetElementCount() == 1) {
                shape = new long[]{1};
            } else {
                shape = new long[rank];
                for (int j = 0; j < rank; j++) {
                    shape[j] = shapeVector.get(j);
                }
            }
        }

        boolean isEmptyTensor = false;
        for(long dim : shape) {
            if (dim == 0) {
                isEmptyTensor = true;
                break;
            }
        }
        if(isEmptyTensor && shape.length > 0) {
            return Nd4j.empty(dataType);
        }
        // Also check GetElementCount for empty tensor case
        if (typeAndShapeInfo.GetElementCount() == 0 && !isEmptyTensor) {
            return Nd4j.empty(dataType);
        }

        DataBuffer dataBuffer = getDataBufferFromValue(value, dataType);
        Preconditions.checkState(dataType.equals(dataBuffer.dataType()), "Data type mismatch between ONNX value metadata and buffer data type.");

        return Nd4j.create(dataBuffer, shape, Nd4j.getStrides(shape, 'c'), 0L);
    }

    /**
     * Creates a Bytedeco ONNX {@link Value} (tensor) from an ND4J {@link INDArray}.
     * This method now strictly uses LongPointer for the shape argument and aligns with ND4J's reference.
     * @param ndArray The INDArray to convert.
     * @param memoryInfo The ONNX MemoryInfo object, typically CPU memory.
     * @return The corresponding ONNX Value.
     */
    public static Value getTensorValue(INDArray ndArray, MemoryInfo memoryInfo) {
        // Ensure memoryInfo is not null before calling asOrtMemoryInfo
        Preconditions.checkNotNull(memoryInfo, "MemoryInfo cannot be null.");
        org.bytedeco.onnxruntime.OrtMemoryInfo ortMemoryInfo = memoryInfo.asOrtMemoryInfo(); // Use asOrtMemoryInfo()

        if (ndArray == null || ndArray.isEmpty()) {
            // For empty or null INDArray, create an empty ONNX tensor
            // According to ND4J reference: new LongPointer(0) for dims, and 0 for rank.
            try (LongPointer dims = new LongPointer(0)) { // Creates an empty LongPointer
                return Value.CreateTensor(
                        ortMemoryInfo,              // OrtMemoryInfo
                        new FloatPointer(),         // Pointer (empty data)
                        0L,                         // long (data_byte_count)
                        dims,                       // LongPointer (shape - empty)
                        0L,                         // long (shape_len - rank is 0)
                        onnxTypeForDataType(FLOAT)  // int (type - default to FLOAT for empty)
                );
            }
        }

        INDArray cContiguousArray = ndArray.isView() || ndArray.ordering() != 'c' ? ndArray.dup('c') : ndArray;

        Pointer inputTensorValuesPtr = cContiguousArray.data().pointer();
        long sizeInBytes = cContiguousArray.length() * cContiguousArray.data().getElementSize();

        long[] shapeArray = cContiguousArray.shape();
        // LongPointer is AutoCloseable, use try-with-resources
        try (LongPointer shapeLp = new LongPointer(shapeArray)) {
            return Value.CreateTensor(
                    ortMemoryInfo,          // OrtMemoryInfo
                    inputTensorValuesPtr,   // Pointer (data)
                    sizeInBytes,            // long (data_byte_count)
                    shapeLp,                // LongPointer (shape)
                    (long) cContiguousArray.rank(), // long (shape_len - rank)
                    onnxTypeForDataType(cContiguousArray.dataType()) // int (type)
            );
        }
    }

    /**
     * Extracts the DataBuffer from an ONNX Value.
     * @param tens The ONNX Value (must be a tensor).
     * @param expectedDataType The expected ND4J DataType.
     * @return ND4J DataBuffer.
     */
    public static DataBuffer getDataBufferFromValue(Value tens, DataType expectedDataType) {
        Preconditions.checkNotNull(tens, "Input ONNX Value for DataBuffer extraction cannot be null.");
        Preconditions.checkArgument(!tens.isNull(), "Input ONNX Value (native) for DataBuffer extraction is null.");

        // PointerScope is good practice for managing native resources within a block
        try (PointerScope scope = new PointerScope()) {
            TensorTypeAndShapeInfo typeAndShapeInfo = tens.GetTensorTypeAndShapeInfo();
            int onnxElementType = typeAndShapeInfo.GetElementType();
            long elementCount = typeAndShapeInfo.GetElementCount();

            if (elementCount == 0) { // Handle empty tensor
                return Nd4j.createBuffer(0);
            }

            DataBuffer buffer;
            // Ensure capacity is set on the pointers obtained from GetTensorMutableData*
            switch (onnxElementType) {
                case ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT:
                    FloatPointer pFloat = tens.GetTensorMutableDataFloat().capacity(elementCount);
                    buffer = Nd4j.createBuffer(pFloat, DataType.FLOAT, elementCount, FloatIndexer.create(pFloat));
                    break;
                case ONNX_TENSOR_ELEMENT_DATA_TYPE_DOUBLE:
                    DoublePointer pDouble = tens.GetTensorMutableDataDouble().capacity(elementCount);
                    buffer = Nd4j.createBuffer(pDouble, DataType.DOUBLE, elementCount, DoubleIndexer.create(pDouble));
                    break;
                case ONNX_TENSOR_ELEMENT_DATA_TYPE_INT8:
                    BytePointer pInt8 = tens.GetTensorMutableDataByte().capacity(elementCount);
                    buffer = Nd4j.createBuffer(pInt8, DataType.INT8, elementCount, ByteIndexer.create(pInt8));
                    break;
                case ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT8:
                    BytePointer pUint8 = tens.GetTensorMutableDataUByte().capacity(elementCount);
                    buffer = Nd4j.createBuffer(pUint8, DataType.UINT8, elementCount, ByteIndexer.create(pUint8));
                    break;
                case ONNX_TENSOR_ELEMENT_DATA_TYPE_INT16:
                    ShortPointer pInt16 = tens.GetTensorMutableDataShort().capacity(elementCount);
                    buffer = Nd4j.createBuffer(pInt16, DataType.INT16, elementCount, ShortIndexer.create(pInt16));
                    break;
                case ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT16:
                    ShortPointer pUint16 = tens.GetTensorMutableDataUShort().capacity(elementCount);
                    buffer = Nd4j.createBuffer(pUint16, DataType.UINT16, elementCount, ShortIndexer.create(pUint16));
                    break;
                case ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32:
                    IntPointer pInt32 = tens.GetTensorMutableDataInt().capacity(elementCount);
                    buffer = Nd4j.createBuffer(pInt32, DataType.INT32, elementCount, IntIndexer.create(pInt32));
                    break;
                case ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT32:
                    IntPointer pUint32 = tens.GetTensorMutableDataUInt().capacity(elementCount);
                    buffer = Nd4j.createBuffer(pUint32, DataType.UINT32, elementCount, IntIndexer.create(pUint32));
                    break;
                case ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64:
                    LongPointer pInt64 = tens.GetTensorMutableDataLong().capacity(elementCount);
                    buffer = Nd4j.createBuffer(pInt64, DataType.INT64, elementCount, LongIndexer.create(pInt64));
                    break;
                case ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT64:
                    LongPointer pUint64 = tens.GetTensorMutableDataULong().capacity(elementCount);
                    buffer = Nd4j.createBuffer(pUint64, DataType.UINT64, elementCount, LongIndexer.create(pUint64));
                    break;
                case ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL:
                    BoolPointer pBool = tens.GetTensorMutableDataBool().capacity(elementCount);
                    byte[] boolBytes = new byte[(int)elementCount];
                    for(int i=0; i<elementCount; i++) {
                        boolBytes[i] = pBool.get(i) ? (byte)1 : (byte)0;
                    }
                    BytePointer boolAsBytePtr = new BytePointer(boolBytes);
                    buffer = Nd4j.createBuffer(boolAsBytePtr, DataType.BOOL, elementCount, ByteIndexer.create(boolAsBytePtr));
                    break;
                case ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT16:
                    ShortPointer pFloat16 = tens.GetTensorMutableDataShort().capacity(elementCount);
                    buffer = Nd4j.createBuffer(pFloat16, DataType.FLOAT16, elementCount, ShortIndexer.create(pFloat16));
                    break;
                case ONNX_TENSOR_ELEMENT_DATA_TYPE_BFLOAT16:
                    ShortPointer pBFloat16 = tens.GetTensorMutableDataShort().capacity(elementCount);
                    buffer = Nd4j.createBuffer(pBFloat16, DataType.BFLOAT16, elementCount, ShortIndexer.create(pBFloat16));
                    break;
                default:
                    throw new RuntimeException("Unsupported ONNX data type for DataBuffer extraction: " + onnxElementType);
            }
            return buffer;
        }
    }

    /**
     * Gets the ONNX logging level based on SLF4J logger configuration.
     * @param logger SLF4J Logger.
     * @return ORT_LOGGING_LEVEL_* constant.
     */
    public static int getOnnxLogLevelFromLogger(Logger logger) {
        if (logger.isErrorEnabled()) return ORT_LOGGING_LEVEL_ERROR;
        if (logger.isWarnEnabled()) return ORT_LOGGING_LEVEL_WARNING;
        if (logger.isInfoEnabled()) return ORT_LOGGING_LEVEL_INFO;
        if (logger.isDebugEnabled() || logger.isTraceEnabled()) return ORT_LOGGING_LEVEL_VERBOSE;
        return ORT_LOGGING_LEVEL_WARNING; // Default if none of the above match
    }
}
