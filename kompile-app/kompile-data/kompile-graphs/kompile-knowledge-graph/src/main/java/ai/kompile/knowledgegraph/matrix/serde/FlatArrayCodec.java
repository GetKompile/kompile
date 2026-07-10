/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.matrix.serde;

import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Dtype-preserving INDArray byte-array codec for cross-process transport.
 *
 * <p>Uses the standard ND4J {@code Nd4j.write()}/{@code Nd4j.read()} serialization path, which
 * preserves all element types (fp16, bf16, fp8, int8, uint8, float32, float64) exactly — unlike a
 * plain JSON float array that flattens everything to float32.</p>
 *
 * <p>This codec is the wire format for subprocess-RPC calls that carry INDArray payloads as base64
 * JSON binary nodes (Jackson {@code node.put("flat", bytes)}).</p>
 */
public final class FlatArrayCodec {

    private FlatArrayCodec() { }

    /**
     * Serialize {@code arr} to a compact, dtype-preserving byte array using ND4J's native format.
     *
     * @param arr the array to serialize (must not be null)
     * @return serialized bytes
     * @throws RuntimeException wrapping any {@link IOException}
     */
    public static byte[] toFlatBytes(INDArray arr) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            Nd4j.write(arr, new DataOutputStream(baos));
        } catch (IOException e) {
            throw new RuntimeException("FlatArrayCodec: failed to serialize INDArray", e);
        }
        return baos.toByteArray();
    }

    /**
     * Deserialize an INDArray from bytes produced by {@link #toFlatBytes(INDArray)}.
     *
     * @param bytes serialized bytes (must not be null)
     * @return the deserialized array
     * @throws IOException if the bytes are malformed or truncated
     */
    public static INDArray fromFlatBytes(byte[] bytes) throws IOException {
        return Nd4j.read(new DataInputStream(new ByteArrayInputStream(bytes)));
    }
}
