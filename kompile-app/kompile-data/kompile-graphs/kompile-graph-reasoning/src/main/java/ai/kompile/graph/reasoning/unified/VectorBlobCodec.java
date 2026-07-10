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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Reads and writes a single {@link VectorLayer} as a compact, self-describing binary blob — the
 * on-disk form of one weight-vector layer of a persisted {@link UnifiedGraph}.
 *
 * <h2>Blob layout</h2>
 * <p>All multi-byte integers/floats are big-endian ({@link DataOutputStream} order). The blob is
 * fully self-contained (its own ids and dtype) so a layer can be read without any external index:</p>
 * <pre>
 *   magic         : 4 bytes  = 'K','V','E','C'
 *   version       : int      = 1
 *   dtypeCode     : int      (see {@link Dtype#code()})
 *   targetCode    : int      (see {@link VectorLayer.Target#code()})
 *   name          : int len + UTF-8 bytes
 *   count         : int      (number of rows)
 *   dim           : int      (values per row)
 *   scaleBits     : long     (Double.doubleToLongBits; the int8 quantization scale, else 0.0)
 *   rows[count]   : { int idLen + UTF-8 id ; dim values encoded per dtype }
 * </pre>
 *
 * <p>Element encodings: {@link Dtype#F16 F16} → 2-byte half; {@link Dtype#F32 F32} → 4-byte float;
 * {@link Dtype#F64 F64} → 8-byte double; {@link Dtype#I8 I8} → 1-byte signed value where
 * {@code stored = round(value / scale)} clamped to {@code [-127, 127]} and {@code scale = maxAbs/127}
 * over the whole layer (so the largest-magnitude component maps to ±127).</p>
 */
public final class VectorBlobCodec {

    private static final byte[] MAGIC = {'K', 'V', 'E', 'C'};
    private static final int VERSION = 1;

    private VectorBlobCodec() { }

    // ═════════════════════════════════════════════════════════════════════════
    // Write
    // ═════════════════════════════════════════════════════════════════════════

    /** Serialize {@code layer} to {@code out} (does not close the stream). */
    public static void write(DataOutputStream out, VectorLayer layer) throws IOException {
        Dtype dtype = layer.dtype();
        int dim = layer.dim();
        Map<String, double[]> rows = layer.rows();

        double scale = dtype == Dtype.I8 ? computeI8Scale(rows) : 0.0;

        out.write(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(dtype.code());
        out.writeInt(layer.target().code());
        writeString(out, layer.name());
        out.writeInt(rows.size());
        out.writeInt(dim);
        out.writeLong(Double.doubleToLongBits(scale));

        for (Map.Entry<String, double[]> e : rows.entrySet()) {
            writeString(out, e.getKey());
            double[] v = e.getValue();
            for (int i = 0; i < dim; i++) {
                double value = i < v.length ? v[i] : 0.0;
                switch (dtype) {
                    case F16 -> out.writeShort(Dtype.floatToHalf((float) value));
                    case F32 -> out.writeFloat((float) value);
                    case F64 -> out.writeDouble(value);
                    case I8  -> out.writeByte(quantizeI8(value, scale));
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Read
    // ═════════════════════════════════════════════════════════════════════════

    /** Deserialize a {@link VectorLayer} from {@code in} (does not close the stream). */
    public static VectorLayer read(DataInputStream in) throws IOException {
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (magic[0] != MAGIC[0] || magic[1] != MAGIC[1] || magic[2] != MAGIC[2] || magic[3] != MAGIC[3]) {
            throw new IOException("Not a KVEC vector blob (bad magic)");
        }
        int version = in.readInt();
        if (version != VERSION) {
            throw new IOException("Unsupported KVEC version: " + version);
        }
        Dtype dtype = Dtype.fromCode(in.readInt());
        VectorLayer.Target target = VectorLayer.Target.fromCode(in.readInt());
        String name = readString(in);
        int count = in.readInt();
        int dim = in.readInt();
        double scale = Double.longBitsToDouble(in.readLong());

        VectorLayer layer = new VectorLayer(name, target, dim, dtype);
        for (int r = 0; r < count; r++) {
            String id = readString(in);
            double[] v = new double[dim];
            for (int i = 0; i < dim; i++) {
                v[i] = switch (dtype) {
                    case F16 -> Dtype.halfToFloat(in.readShort());
                    case F32 -> in.readFloat();
                    case F64 -> in.readDouble();
                    case I8  -> in.readByte() * scale;
                };
            }
            layer.put(id, v);
        }
        return layer;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static double computeI8Scale(Map<String, double[]> rows) {
        double maxAbs = 0.0;
        for (double[] v : rows.values()) {
            for (double x : v) {
                double a = Math.abs(x);
                if (a > maxAbs) maxAbs = a;
            }
        }
        return maxAbs == 0.0 ? 1.0 : maxAbs / 127.0;
    }

    private static byte quantizeI8(double value, double scale) {
        long q = Math.round(value / scale);
        if (q > 127) q = 127;
        if (q < -127) q = -127;
        return (byte) q;
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0) throw new IOException("Negative string length: " + len);
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
