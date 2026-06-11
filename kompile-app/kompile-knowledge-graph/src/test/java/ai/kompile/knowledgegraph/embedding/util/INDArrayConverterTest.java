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

package ai.kompile.knowledgegraph.embedding.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for INDArrayConverter.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class INDArrayConverterTest {

    private INDArrayConverter converter;

    @BeforeEach
    void setUp() {
        converter = new INDArrayConverter();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // convertToDatabaseColumn
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void convertToDatabaseColumn_withNull_returnsNull() {
        byte[] result = converter.convertToDatabaseColumn(null);
        assertNull(result);
    }

    @Test
    void convertToDatabaseColumn_with1DArray_returnsNonNullBytes() {
        INDArray array = Nd4j.create(new float[]{1.0f, 2.0f, 3.0f});
        byte[] result = converter.convertToDatabaseColumn(array);
        assertNotNull(result);
        assertTrue(result.length > 0);
    }

    @Test
    void convertToDatabaseColumn_with2DArray_returnsNonNullBytes() {
        INDArray array = Nd4j.create(new float[][]{{1.0f, 2.0f}, {3.0f, 4.0f}});
        byte[] result = converter.convertToDatabaseColumn(array);
        assertNotNull(result);
    }

    @Test
    void convertToDatabaseColumn_encodesDimensionCount() {
        // Format: 4 bytes (num dims) + 4*dims (shape) + 4*elements (float data)
        INDArray array = Nd4j.create(new float[]{1.0f, 2.0f, 3.0f, 4.0f});
        byte[] result = converter.convertToDatabaseColumn(array);

        // Minimum: 4 (numDims=1) + 4 (shape[0]=4) + 16 (4 floats * 4 bytes) = 24 bytes
        assertTrue(result.length >= 24);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // convertToEntityAttribute
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void convertToEntityAttribute_withNull_returnsNull() {
        INDArray result = converter.convertToEntityAttribute(null);
        assertNull(result);
    }

    @Test
    void convertToEntityAttribute_withEmptyArray_returnsNull() {
        INDArray result = converter.convertToEntityAttribute(new byte[0]);
        assertNull(result);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Roundtrip tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void roundtrip_1DArray_preservesValues() {
        float[] originalData = {1.5f, -2.3f, 0.7f, 4.0f};
        INDArray original = Nd4j.create(originalData);

        byte[] bytes = converter.convertToDatabaseColumn(original);
        INDArray restored = converter.convertToEntityAttribute(bytes);

        assertNotNull(restored);
        assertEquals(original.length(), restored.length());

        float[] restoredData = restored.data().asFloat();
        assertArrayEquals(originalData, restoredData, 1e-5f);
    }

    @Test
    void roundtrip_2DArray_preservesShapeAndValues() {
        INDArray original = Nd4j.create(new float[][]{{1.0f, 2.0f, 3.0f}, {4.0f, 5.0f, 6.0f}});

        byte[] bytes = converter.convertToDatabaseColumn(original);
        INDArray restored = converter.convertToEntityAttribute(bytes);

        assertNotNull(restored);
        assertArrayEquals(original.shape(), restored.shape());

        float[] origData = original.data().asFloat();
        float[] restData = restored.data().asFloat();
        assertArrayEquals(origData, restData, 1e-5f);
    }

    @Test
    void roundtrip_zerosArray_preservesZeros() {
        INDArray original = Nd4j.zeros(5);

        byte[] bytes = converter.convertToDatabaseColumn(original);
        INDArray restored = converter.convertToEntityAttribute(bytes);

        assertNotNull(restored);
        float[] restoredData = restored.data().asFloat();
        for (float v : restoredData) {
            assertEquals(0.0f, v, 1e-7f);
        }
    }

    @Test
    void roundtrip_largeEmbedding_preservesValues() {
        INDArray original = Nd4j.rand(1, 768).muli(2).subi(1); // typical BERT embedding size

        byte[] bytes = converter.convertToDatabaseColumn(original);
        INDArray restored = converter.convertToEntityAttribute(bytes);

        assertNotNull(restored);
        assertEquals(768, restored.columns());

        float[] origData = original.data().asFloat();
        float[] restData = restored.data().asFloat();
        assertArrayEquals(origData, restData, 1e-5f);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Static utility methods
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void toDoubleArray_withNull_returnsNull() {
        double[] result = INDArrayConverter.toDoubleArray(null);
        assertNull(result);
    }

    @Test
    void toDoubleArray_returnsCorrectValues() {
        INDArray array = Nd4j.create(new float[]{1.0f, 2.0f, 3.0f});
        double[] result = INDArrayConverter.toDoubleArray(array);

        assertNotNull(result);
        assertEquals(3, result.length);
        assertEquals(1.0, result[0], 1e-5);
        assertEquals(2.0, result[1], 1e-5);
        assertEquals(3.0, result[2], 1e-5);
    }

    @Test
    void fromDoubleArray_withNull_returnsNull() {
        INDArray result = INDArrayConverter.fromDoubleArray(null);
        assertNull(result);
    }

    @Test
    void fromDoubleArray_withEmptyArray_returnsNull() {
        INDArray result = INDArrayConverter.fromDoubleArray(new double[0]);
        assertNull(result);
    }

    @Test
    void fromDoubleArray_returnsCorrectValues() {
        double[] data = {1.0, 2.0, 3.0};
        INDArray result = INDArrayConverter.fromDoubleArray(data);

        assertNotNull(result);
        assertEquals(3, result.length());
        assertEquals(1.0, result.getDouble(0), 1e-5);
    }

    @Test
    void fromDoubleArray_withShape_returnsCorrectShape() {
        double[] data = {1.0, 2.0, 3.0, 4.0, 5.0, 6.0};
        INDArray result = INDArrayConverter.fromDoubleArray(data, 2, 3);

        assertNotNull(result);
        assertArrayEquals(new long[]{2, 3}, result.shape());
    }

    @Test
    void toDoubleArray_fromDoubleArray_roundtrip() {
        INDArray original = Nd4j.rand(10);
        double[] doubles = INDArrayConverter.toDoubleArray(original);
        INDArray restored = INDArrayConverter.fromDoubleArray(doubles);

        assertNotNull(restored);
        assertEquals(original.length(), restored.length());

        for (int i = 0; i < original.length(); i++) {
            assertEquals(original.getDouble(i), restored.getDouble(i), 1e-5);
        }
    }
}
