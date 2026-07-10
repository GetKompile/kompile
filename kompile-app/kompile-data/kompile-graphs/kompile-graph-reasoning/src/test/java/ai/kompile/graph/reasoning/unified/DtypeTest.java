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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DtypeTest {

    @Test
    void codeRoundTrip() {
        for (Dtype d : Dtype.values()) {
            assertSame(d, Dtype.fromCode(d.code()));
        }
    }

    @Test
    void nameRoundTrip() {
        assertSame(Dtype.F16, Dtype.fromName("f16", Dtype.F32));
        assertSame(Dtype.F64, Dtype.fromName("F64", Dtype.F32));
        assertSame(Dtype.F32, Dtype.fromName("bogus", Dtype.F32));
        assertSame(Dtype.F32, Dtype.fromName(null, Dtype.F32));
    }

    @Test
    void halfFloatExactForRepresentableValues() {
        for (float v : new float[] {0.0f, -0.0f, 1.0f, -1.0f, 0.5f, 2.0f, -2.0f, 0.25f, 65504.0f}) {
            float back = Dtype.halfToFloat(Dtype.floatToHalf(v));
            assertEquals(v, back, 0.0f, "half round-trip should be exact for " + v);
        }
    }

    @Test
    void halfFloatApproxForOtherValues() {
        for (float v : new float[] {0.1f, 0.3f, -0.7f, 3.14159f, 123.4f}) {
            float back = Dtype.halfToFloat(Dtype.floatToHalf(v));
            assertEquals(v, back, Math.max(1e-3f, Math.abs(v) * 1e-2f), "half approx for " + v);
        }
    }

    @Test
    void halfFloatHandlesSpecials() {
        assertTrue(Float.isInfinite(Dtype.halfToFloat(Dtype.floatToHalf(Float.POSITIVE_INFINITY))));
        assertTrue(Float.isInfinite(Dtype.halfToFloat(Dtype.floatToHalf(Float.NEGATIVE_INFINITY))));
        assertTrue(Dtype.halfToFloat(Dtype.floatToHalf(Float.NEGATIVE_INFINITY)) < 0);
        assertTrue(Float.isNaN(Dtype.halfToFloat(Dtype.floatToHalf(Float.NaN))));
    }

    @Test
    void halfFloatUnderflowsToZero() {
        assertEquals(0.0f, Dtype.halfToFloat(Dtype.floatToHalf(1e-12f)), 0.0f);
    }

    @Test
    void bytesPerValue() {
        assertEquals(2, Dtype.F16.bytesPerValue());
        assertEquals(4, Dtype.F32.bytesPerValue());
        assertEquals(8, Dtype.F64.bytesPerValue());
        assertEquals(1, Dtype.I8.bytesPerValue());
    }
}
