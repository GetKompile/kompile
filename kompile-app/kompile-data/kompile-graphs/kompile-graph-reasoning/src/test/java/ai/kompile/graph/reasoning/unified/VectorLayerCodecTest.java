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
package ai.kompile.graph.reasoning.unified;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorLayerCodecTest {

    private static VectorLayer roundTrip(VectorLayer layer) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(bos)) {
            VectorBlobCodec.write(dos, layer);
        }
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
            return VectorBlobCodec.read(dis);
        }
    }

    @Test
    void f64IsLossless() throws IOException {
        VectorLayer t = new VectorLayer("kge", VectorLayer.Target.ENTITY, 3, Dtype.F64);
        t.put("a", new double[] {0.123456789012345, -9.87654321, 1e-9});
        t.put("b", new double[] {Math.PI, Math.E, -Math.sqrt(2)});

        VectorLayer back = roundTrip(t);
        assertEquals("kge", back.name());
        assertSame(VectorLayer.Target.ENTITY, back.target());
        assertSame(Dtype.F64, back.dtype());
        assertEquals(3, back.dim());
        assertEquals(2, back.size());
        assertArrayEquals(t.get("a"), back.get("a"), 0.0);
        assertArrayEquals(t.get("b"), back.get("b"), 0.0);
    }

    @Test
    void f32IsLosslessForFloatSources() throws IOException {
        float[] src = {0.1f, 0.2f, 0.3f, -0.4f};
        double[] widened = new double[src.length];
        for (int i = 0; i < src.length; i++) widened[i] = src[i];

        VectorLayer t = new VectorLayer("embedding", VectorLayer.Target.ENTITY, 4, Dtype.F32);
        t.put("x", widened);
        VectorLayer back = roundTrip(t);
        assertArrayEquals(widened, back.get("x"), 0.0);
    }

    @Test
    void f16IsApproximate() throws IOException {
        VectorLayer t = new VectorLayer("half", VectorLayer.Target.ENTITY, 3, Dtype.F16);
        double[] v = {0.5, -0.25, 1.5};
        t.put("x", v);
        VectorLayer back = roundTrip(t);
        assertArrayEquals(v, back.get("x"), 1e-2);
    }

    @Test
    void i8QuantizationIsWithinScale() throws IOException {
        VectorLayer t = new VectorLayer("q", VectorLayer.Target.ENTITY, 4, Dtype.I8);
        double[] v = {1.0, -0.5, 0.25, -1.0}; // maxAbs = 1.0 -> scale = 1/127
        t.put("x", v);
        VectorLayer back = roundTrip(t);
        assertArrayEquals(v, back.get("x"), 1.0 / 127.0 + 1e-9);
    }

    @Test
    void globalTargetKeyedByArbitraryLabels() throws IOException {
        VectorLayer t = new VectorLayer("kgeRelation", VectorLayer.Target.GLOBAL, 2, Dtype.F64);
        t.put("WORKS_AT", new double[] {0.1, 0.2});
        t.put("KNOWS", new double[] {-0.3, 0.4});

        VectorLayer back = roundTrip(t);
        assertSame(VectorLayer.Target.GLOBAL, back.target());
        assertArrayEquals(new double[] {0.1, 0.2}, back.get("WORKS_AT"), 0.0);
        assertNull(back.get("MISSING"));
    }

    @Test
    void unicodeIdsSurvive() throws IOException {
        VectorLayer t = new VectorLayer("u", VectorLayer.Target.ENTITY, 1, Dtype.F64);
        t.put("café-☕-日本", new double[] {42.0});
        VectorLayer back = roundTrip(t);
        assertArrayEquals(new double[] {42.0}, back.get("café-☕-日本"), 0.0);
    }

    @Test
    void emptyLayerRoundTrips() throws IOException {
        VectorLayer t = new VectorLayer("empty", VectorLayer.Target.ENTITY, 5, Dtype.F32);
        VectorLayer back = roundTrip(t);
        assertTrue(back.isEmpty());
        assertEquals(5, back.dim());
        assertEquals("empty", back.name());
    }
}
