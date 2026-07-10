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

/**
 * The numeric element type a {@link VectorLayer} is encoded with when a {@link UnifiedGraph} is
 * persisted.
 *
 * <p>Weight vectors in the graph model are {@code double[]}, but the underlying kompile sources are
 * typically {@code float32} (sentence embeddings, TransE/RotatE KGE vectors). Each vector layer can
 * pick the on-disk element type independently, trading size against precision:</p>
 *
 * <ul>
 *   <li>{@link #F64} — lossless for {@code double[]} (8 bytes/element).</li>
 *   <li>{@link #F32} — lossless for the {@code float32} sources; the default (4 bytes/element).</li>
 *   <li>{@link #F16} — IEEE-754 half precision, ~3 significant digits (2 bytes/element).</li>
 *   <li>{@link #I8} — symmetric linear int8 quantization with a per-layer scale (1 byte/element).</li>
 * </ul>
 *
 * <p>{@link #F16} exists because the kompile subprocess transport ({@code FlatArrayCodec}) and the
 * ND4J layer both use half precision for memory-bound vector fleets; representing it here keeps the
 * graph able to carry "all weight vectors" at their native width. Java 17 lacks
 * {@code Float.float16ToFloat}/{@code floatToFloat16}, so the conversions are implemented directly.</p>
 */
public enum Dtype {

    /** IEEE-754 half precision (binary16), 2 bytes/element. Lossy. */
    F16(0, 2),
    /** IEEE-754 single precision (binary32), 4 bytes/element. Lossless for float32 sources. */
    F32(1, 4),
    /** IEEE-754 double precision (binary64), 8 bytes/element. Lossless for {@code double[]}. */
    F64(2, 8),
    /** Symmetric int8 quantization with a per-layer scale, 1 byte/element. Lossy. */
    I8(3, 1);

    private final int code;
    private final int bytesPerValue;

    Dtype(int code, int bytesPerValue) {
        this.code = code;
        this.bytesPerValue = bytesPerValue;
    }

    /** Stable numeric code written to the vector blob header (never reorder existing codes). */
    public int code() {
        return code;
    }

    /** On-disk bytes per vector element. */
    public int bytesPerValue() {
        return bytesPerValue;
    }

    /** Resolve a {@link Dtype} from its on-disk {@link #code()}. */
    public static Dtype fromCode(int code) {
        for (Dtype d : values()) {
            if (d.code == code) return d;
        }
        throw new IllegalArgumentException("Unknown Dtype code: " + code);
    }

    /** Resolve a {@link Dtype} from its name, case-insensitively, defaulting to {@link #F32}. */
    public static Dtype fromName(String name, Dtype fallback) {
        if (name == null) return fallback;
        for (Dtype d : values()) {
            if (d.name().equalsIgnoreCase(name.trim())) return d;
        }
        return fallback;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // IEEE-754 half precision (binary16) <-> float  (Java 17 has no built-in)
    // ═════════════════════════════════════════════════════════════════════════

    /** Convert an IEEE-754 half-precision value (as the low 16 bits of {@code h}) to a float. */
    public static float halfToFloat(short h) {
        int bits = h & 0xffff;
        int sign = (bits >>> 15) & 0x1;
        int exp  = (bits >>> 10) & 0x1f;
        int mant =  bits & 0x3ff;
        int out;
        if (exp == 0) {
            if (mant == 0) {
                out = sign << 31;                       // signed zero
            } else {
                // subnormal — normalize into a float normal
                int e = -1;
                do {
                    e++;
                    mant <<= 1;
                } while ((mant & 0x400) == 0);
                mant &= 0x3ff;
                out = (sign << 31) | ((127 - 15 - e) << 23) | (mant << 13);
            }
        } else if (exp == 0x1f) {
            out = (sign << 31) | 0x7f800000 | (mant << 13);   // Inf / NaN
        } else {
            out = (sign << 31) | ((exp + (127 - 15)) << 23) | (mant << 13);
        }
        return Float.intBitsToFloat(out);
    }

    /** Convert a float to IEEE-754 half precision (round-to-nearest), returned in the low 16 bits. */
    public static short floatToHalf(float value) {
        int bits = Float.floatToIntBits(value);
        int sign = (bits >>> 16) & 0x8000;
        int rawExp = (bits >>> 23) & 0xff;
        int mant = bits & 0x7fffff;

        if (rawExp == 0xff) {                              // Inf / NaN
            return (short) (sign | 0x7c00 | (mant != 0 ? 0x0200 : 0));
        }
        int exp = rawExp - (127 - 15);
        if (exp >= 0x1f) {                                 // overflow -> Inf
            return (short) (sign | 0x7c00);
        }
        if (exp <= 0) {                                    // subnormal / underflow
            if (exp < -10) {
                return (short) sign;                       // too small -> signed zero
            }
            mant = (mant | 0x800000) >>> (1 - exp);
            if ((mant & 0x1000) != 0) {
                mant += 0x2000;                            // round to nearest
            }
            return (short) (sign | (mant >>> 13));
        }
        int half = sign | (exp << 10) | (mant >>> 13);
        if ((mant & 0x1000) != 0) {
            half += 1;                                     // round to nearest (carries into exp if needed)
        }
        return (short) half;
    }
}
