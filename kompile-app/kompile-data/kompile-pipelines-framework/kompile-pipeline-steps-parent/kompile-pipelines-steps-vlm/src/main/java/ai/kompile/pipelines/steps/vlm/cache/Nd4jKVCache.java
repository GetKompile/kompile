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

package ai.kompile.pipelines.steps.vlm.cache;

import ai.kompile.pipelines.framework.api.data.NDArray;
import ai.kompile.pipelines.framework.api.data.NDArrayType;
import ai.kompile.pipelines.framework.api.kvcache.KVCache;
import ai.kompile.pipelines.framework.core.utils.NDArrayTypeConverter;
import org.nd4j.common.util.ArrayUtil;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

/**
 * ND4J-backed implementation of {@link KVCache} for transformer decoder KV caching.
 *
 * Stores key and value tensors for each transformer layer as INDArrays.
 * Supports concatenation along the sequence dimension for autoregressive decoding,
 * deep copying for SameDiff re-execution, and proper native memory cleanup.
 */
public class Nd4jKVCache implements KVCache {

    private final int numLayers;
    private final int numHeads;
    private final int headDim;
    private final INDArray[] keys;
    private final INDArray[] values;
    private int currentSequenceLength;

    private Nd4jKVCache(int numLayers, int numHeads, int headDim, INDArray[] keys, INDArray[] values, int seqLen) {
        this.numLayers = numLayers;
        this.numHeads = numHeads;
        this.headDim = headDim;
        this.keys = keys;
        this.values = values;
        this.currentSequenceLength = seqLen;
    }

    /**
     * Creates an empty KV cache with zero sequence length.
     * Initial key/value arrays are empty tensors with shape [1, numHeads, 0, headDim].
     */
    public static Nd4jKVCache empty(int numLayers, int numHeads, int headDim) {
        INDArray[] keys = new INDArray[numLayers];
        INDArray[] values = new INDArray[numLayers];
        for (int i = 0; i < numLayers; i++) {
            keys[i] = Nd4j.zeros(1, numHeads, 0, headDim);
            values[i] = Nd4j.zeros(1, numHeads, 0, headDim);
        }
        return new Nd4jKVCache(numLayers, numHeads, headDim, keys, values, 0);
    }

    /**
     * Creates a KV cache wrapping existing INDArray key/value tensors.
     */
    public static Nd4jKVCache wrap(int numLayers, int numHeads, int headDim,
                                    INDArray[] keys, INDArray[] values) {
        if (keys.length != numLayers || values.length != numLayers) {
            throw new IllegalArgumentException("keys and values arrays must have length == numLayers");
        }
        int seqLen = keys[0] != null && keys[0].rank() >= 3 ? (int) keys[0].shape()[2] : 0;
        return new Nd4jKVCache(numLayers, numHeads, headDim, keys, values, seqLen);
    }

    /**
     * Creates a KV cache from DL4J's DecoderUtils map-based output.
     *
     * <p>DecoderUtils.createEmptyKvCache() returns a Map with keys like
     * "past_key_values.0.key", "past_key_values.0.value", etc.
     * This factory converts that map into kompile's layer-indexed format.</p>
     *
     * @param decoderUtilsMap Map from DecoderUtils.createEmptyKvCache()
     * @param numLayers Number of KV cache layers
     * @param numHeads Number of attention heads
     * @param headDim Dimension of each attention head
     * @return Nd4jKVCache instance
     */
    public static Nd4jKVCache fromDecoderUtilsMap(Map<String, INDArray> decoderUtilsMap,
                                                    int numLayers, int numHeads, int headDim) {
        INDArray[] keys = new INDArray[numLayers];
        INDArray[] values = new INDArray[numLayers];

        for (int i = 0; i < numLayers; i++) {
            INDArray key = decoderUtilsMap.get("past_key_values." + i + ".key");
            INDArray value = decoderUtilsMap.get("past_key_values." + i + ".value");
            keys[i] = key != null ? key.dup() : Nd4j.zeros(1, numHeads, 0, headDim);
            values[i] = value != null ? value.dup() : Nd4j.zeros(1, numHeads, 0, headDim);
        }

        int seqLen = keys[0].rank() >= 3 ? (int) keys[0].shape()[2] : 0;
        return new Nd4jKVCache(numLayers, numHeads, headDim, keys, values, seqLen);
    }

    /**
     * Truncates the KV cache to keep only the most recent tokens.
     *
     * <p>This is useful for memory-aware cache management. When the sequence
     * exceeds a configurable maximum, older tokens are discarded by slicing
     * the sequence dimension to keep only the last {@code keepLastN} tokens.</p>
     *
     * @param keepLastN Number of most recent tokens to keep
     */
    public void truncate(int keepLastN) {
        if (currentSequenceLength <= keepLastN) {
            return; // No truncation needed
        }

        long startIdx = currentSequenceLength - keepLastN;
        for (int i = 0; i < numLayers; i++) {
            if (keys[i] != null && keys[i].shape()[2] > keepLastN) {
                INDArray oldKey = keys[i];
                INDArray oldValue = values[i];
                keys[i] = oldKey.get(NDArrayIndex.all(), NDArrayIndex.all(),
                        NDArrayIndex.interval(startIdx, currentSequenceLength), NDArrayIndex.all()).dup();
                values[i] = oldValue.get(NDArrayIndex.all(), NDArrayIndex.all(),
                        NDArrayIndex.interval(startIdx, currentSequenceLength), NDArrayIndex.all()).dup();
                try { oldKey.close(); } catch (Exception ignored) {}
                try { oldValue.close(); } catch (Exception ignored) {}
            }
        }
        this.currentSequenceLength = keepLastN;
    }

    @Override
    public int getNumLayers() {
        return numLayers;
    }

    @Override
    public int getNumHeads() {
        return numHeads;
    }

    @Override
    public int getHeadDim() {
        return headDim;
    }

    @Override
    public int getCurrentSequenceLength() {
        return currentSequenceLength;
    }

    @Override
    public NDArray getKey(int layer) {
        return convertFromINDArray(keys[layer], "kv_cache_key_layer_" + layer);
    }

    @Override
    public NDArray getValue(int layer) {
        return convertFromINDArray(values[layer], "kv_cache_value_layer_" + layer);
    }

    /**
     * Returns the raw INDArray key for a layer (avoids conversion overhead).
     */
    public INDArray getKeyINDArray(int layer) {
        return keys[layer];
    }

    /**
     * Returns the raw INDArray value for a layer (avoids conversion overhead).
     */
    public INDArray getValueINDArray(int layer) {
        return values[layer];
    }

    @Override
    public void update(int layer, NDArray newKey, NDArray newValue) {
        INDArray newKeyArr = convertToINDArray(newKey);
        INDArray newValueArr = convertToINDArray(newValue);
        updateINDArray(layer, newKeyArr, newValueArr);
    }

    /**
     * Updates the cache with raw INDArrays (avoids conversion overhead).
     */
    public void updateINDArray(int layer, INDArray newKey, INDArray newValue) {
        INDArray oldKey = keys[layer];
        INDArray oldValue = values[layer];

        if (oldKey.shape()[2] == 0) {
            // First update: just use the new arrays
            keys[layer] = newKey.dup();
            values[layer] = newValue.dup();
        } else {
            // Concatenate along sequence dimension (dim 2)
            keys[layer] = Nd4j.concat(2, oldKey, newKey);
            values[layer] = Nd4j.concat(2, oldValue, newValue);
        }

        // Close old arrays
        if (oldKey != null) {
            try { oldKey.close(); } catch (Exception ignored) {}
        }
        if (oldValue != null) {
            try { oldValue.close(); } catch (Exception ignored) {}
        }

        this.currentSequenceLength = (int) keys[layer].shape()[2];
    }

    @Override
    public KVCache dup() {
        INDArray[] dupKeys = new INDArray[numLayers];
        INDArray[] dupValues = new INDArray[numLayers];
        for (int i = 0; i < numLayers; i++) {
            dupKeys[i] = keys[i].dup();
            dupValues[i] = values[i].dup();
        }
        return new Nd4jKVCache(numLayers, numHeads, headDim, dupKeys, dupValues, currentSequenceLength);
    }

    @Override
    public void close() {
        for (int i = 0; i < numLayers; i++) {
            if (keys[i] != null) {
                try { keys[i].close(); } catch (Exception ignored) {}
                keys[i] = null;
            }
            if (values[i] != null) {
                try { values[i].close(); } catch (Exception ignored) {}
                values[i] = null;
            }
        }
    }

    private INDArray convertToINDArray(NDArray ndArray) {
        Object nativeObj = ndArray.getNative();
        if (nativeObj instanceof INDArray) {
            return (INDArray) nativeObj;
        }
        // Fallback: convert from ByteBuffer
        ByteBuffer bb = ndArray.buffer().order(ByteOrder.nativeOrder());
        if (!bb.isDirect()) {
            ByteBuffer directBb = ByteBuffer.allocateDirect(bb.remaining()).order(ByteOrder.nativeOrder());
            directBb.put(bb.slice());
            directBb.flip();
            bb = directBb;
        }
        org.nd4j.linalg.api.buffer.DataType dt = mapType(ndArray.type());
        long[] shape = ndArray.shape();
        org.nd4j.linalg.api.buffer.DataBuffer dataBuffer =
                Nd4j.createBuffer(bb, dt, (int) ndArray.length());
        return Nd4j.create(dataBuffer, shape, Nd4j.getStrides(shape, 'c'), 0L, 'c', dt);
    }

    private static NDArray convertFromINDArray(INDArray arr, String name) {
        final long[] shape = arr.shape().clone();
        final INDArray ref = arr;
        final NDArrayType type = mapTypeReverse(arr.dataType());
        return new NDArray() {
            @Override public String name() { return name; }
            @Override public long[] shape() { return shape; }
            @Override public NDArrayType type() { return type; }
            @Override public ByteBuffer buffer() {
                INDArray contiguous = (ref.isView() || ref.ordering() != 'c') ? ref.dup('c') : ref;
                return contiguous.data().asNio().order(ByteOrder.nativeOrder());
            }
            @SuppressWarnings("unchecked")
            @Override public <T> T getNative() { return (T) ref; }
            @Override public long length() { return ArrayUtil.prod(shape); }
            @Override public int bufferSizeInBytes() {
                return (int)(length() * ref.dataType().width());
            }
        };
    }

    private static org.nd4j.linalg.api.buffer.DataType mapType(NDArrayType type) {
        return NDArrayTypeConverter.toNd4jDataType(type);
    }

    private static NDArrayType mapTypeReverse(org.nd4j.linalg.api.buffer.DataType type) {
        return NDArrayTypeConverter.fromNd4jDataType(type);
    }
}
