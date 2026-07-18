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

package ai.kompile.utils;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Minimal streaming reader for the GGUF key-value header — just enough to answer
 * "what is this model's context window?" without loading the file or depending on an
 * inference runtime.
 *
 * <p>The context window lives under the architecture-scoped key
 * {@code <arch>.context_length} (e.g. {@code llama.context_length},
 * {@code lfm2.context_length}), so matching is by the {@code .context_length} suffix.
 * Values are streamed and skipped (tokenizer vocab arrays run to megabytes); the scan
 * stops at the first match. Any structural surprise returns empty rather than throwing —
 * callers treat "unknown" as absence of metadata, never as an error.</p>
 */
public final class GgufMetadataReader {

    /** "GGUF" read as a little-endian u32. */
    private static final long GGUF_MAGIC = 0x46554747L;

    private static final String CONTEXT_LENGTH_KEY_SUFFIX = ".context_length";

    /** Sanity caps so a corrupt header cannot make the scan pathological. */
    private static final long MAX_KV_COUNT = 1_000_000L;
    private static final long MAX_KEY_BYTES = 1L << 16;

    // GGUF metadata value types.
    private static final int T_UINT8 = 0, T_INT8 = 1, T_UINT16 = 2, T_INT16 = 3,
            T_UINT32 = 4, T_INT32 = 5, T_FLOAT32 = 6, T_BOOL = 7,
            T_STRING = 8, T_ARRAY = 9, T_UINT64 = 10, T_INT64 = 11, T_FLOAT64 = 12;

    private GgufMetadataReader() {
    }

    /**
     * The model's declared context window from the GGUF header, or empty when the file is not
     * GGUF v2/v3, the key is absent, or the header is malformed.
     */
    public static Optional<Integer> readContextLength(Path ggufFile) {
        try (InputStream raw = Files.newInputStream(ggufFile);
             BufferedInputStream in = new BufferedInputStream(raw, 1 << 16)) {
            if (readU32(in) != GGUF_MAGIC) {
                return Optional.empty();
            }
            long version = readU32(in);
            if (version < 2 || version > 3) {
                // v1 used 32-bit counts and is long obsolete; anything newer is unknown layout.
                return Optional.empty();
            }
            readU64(in); // tensor_count — not needed
            long kvCount = readU64(in);
            if (kvCount < 0 || kvCount > MAX_KV_COUNT) {
                return Optional.empty();
            }
            for (long i = 0; i < kvCount; i++) {
                String key = readKeyString(in);
                int type = (int) readU32(in);
                if (key.endsWith(CONTEXT_LENGTH_KEY_SUFFIX)) {
                    Long value = readIntegralScalar(in, type);
                    if (value != null && value > 0 && value <= Integer.MAX_VALUE) {
                        return Optional.of(value.intValue());
                    }
                    return Optional.empty();
                }
                skipValue(in, type);
            }
        } catch (Exception e) {
            // Malformed/truncated/foreign file — the contract is "empty means unknown", and this
            // module is dependency-free, so no logging here; callers log the resolved outcome.
        }
        return Optional.empty();
    }

    /** Integral scalar value of the given GGUF type, or null for non-integral types. */
    private static Long readIntegralScalar(InputStream in, int type) throws IOException {
        return switch (type) {
            case T_UINT8, T_INT8 -> (long) readByteStrict(in);
            case T_UINT16, T_INT16 -> readLe(in, 2);
            case T_UINT32, T_INT32 -> readLe(in, 4);
            case T_UINT64, T_INT64 -> readLe(in, 8);
            default -> null;
        };
    }

    private static void skipValue(InputStream in, int type) throws IOException {
        switch (type) {
            case T_UINT8, T_INT8, T_BOOL -> skipStrict(in, 1);
            case T_UINT16, T_INT16 -> skipStrict(in, 2);
            case T_UINT32, T_INT32, T_FLOAT32 -> skipStrict(in, 4);
            case T_UINT64, T_INT64, T_FLOAT64 -> skipStrict(in, 8);
            case T_STRING -> skipStrict(in, readU64Bounded(in));
            case T_ARRAY -> {
                int elemType = (int) readU32(in);
                long count = readU64(in);
                if (count < 0) {
                    throw new IOException("negative GGUF array count");
                }
                switch (elemType) {
                    case T_UINT8, T_INT8, T_BOOL -> skipStrict(in, count);
                    case T_UINT16, T_INT16 -> skipStrict(in, Math.multiplyExact(count, 2L));
                    case T_UINT32, T_INT32, T_FLOAT32 -> skipStrict(in, Math.multiplyExact(count, 4L));
                    case T_UINT64, T_INT64, T_FLOAT64 -> skipStrict(in, Math.multiplyExact(count, 8L));
                    case T_STRING -> {
                        for (long i = 0; i < count; i++) {
                            skipStrict(in, readU64Bounded(in));
                        }
                    }
                    default -> throw new IOException("unsupported GGUF array element type " + elemType);
                }
            }
            default -> throw new IOException("unsupported GGUF value type " + type);
        }
    }

    private static String readKeyString(InputStream in) throws IOException {
        long len = readU64(in);
        if (len < 0 || len > MAX_KEY_BYTES) {
            throw new IOException("implausible GGUF key length " + len);
        }
        byte[] bytes = in.readNBytes((int) len);
        if (bytes.length != len) {
            throw new EOFException("truncated GGUF key");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static long readU32(InputStream in) throws IOException {
        return readLe(in, 4);
    }

    private static long readU64(InputStream in) throws IOException {
        return readLe(in, 8);
    }

    /** u64 used as a byte length — reject values that cannot be a sane in-file length. */
    private static long readU64Bounded(InputStream in) throws IOException {
        long v = readU64(in);
        if (v < 0) {
            throw new IOException("implausible GGUF length " + v);
        }
        return v;
    }

    private static long readLe(InputStream in, int bytes) throws IOException {
        long value = 0;
        for (int i = 0; i < bytes; i++) {
            value |= (long) readByteStrict(in) << (8 * i);
        }
        return value;
    }

    private static int readByteStrict(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException("truncated GGUF header");
        }
        return b;
    }

    private static void skipStrict(InputStream in, long n) throws IOException {
        if (n < 0) {
            throw new IOException("negative GGUF skip " + n);
        }
        in.skipNBytes(n);
    }
}
