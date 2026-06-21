package ai.kompile.project.server.xet;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the Xet xorb serialization format to recover per-chunk <em>serialized</em> byte lengths.
 *
 * <p>A xorb is a sequence of {@code [8-byte chunk header][compressed data]}. The header is
 * {@code version(1) | compressed_size(3, little-endian) | compression_type(1) | uncompressed_size(3, LE)};
 * a chunk's serialized length is therefore {@code 8 + compressed_size}.
 *
 * <p>These serialized offsets — NOT the raw/uncompressed offsets recorded in a shard's CAS-info
 * section — are what reconstruction {@code url_range} byte ranges must use, because chunks are
 * compressed on disk. (Confirmed against the Xet TCK reference files.)
 *
 * @see <a href="https://huggingface.co/docs/xet/xorb">Xet Xorb Format</a>
 */
public final class XorbCodec {

    public static final int CHUNK_HEADER_SIZE = 8;

    private XorbCodec() {
    }

    /** Per-chunk serialized byte lengths ({@code 8 + compressed_size}), in chunk order. */
    public static long[] serializedChunkLengths(byte[] xorb) {
        List<Long> lengths = new ArrayList<>();
        int pos = 0;
        int n = xorb.length;
        while (pos + CHUNK_HEADER_SIZE <= n) {
            int compressedSize = (xorb[pos + 1] & 0xFF)
                    | ((xorb[pos + 2] & 0xFF) << 8)
                    | ((xorb[pos + 3] & 0xFF) << 16);
            int serialized = CHUNK_HEADER_SIZE + compressedSize;
            if (pos + serialized > n) {
                break; // truncated / not a clean chunk boundary; stop scanning
            }
            lengths.add((long) serialized);
            pos += serialized;
        }
        long[] out = new long[lengths.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = lengths.get(i);
        }
        return out;
    }

    public static long totalSerializedLength(long[] lengths) {
        long total = 0;
        for (long l : lengths) {
            total += l;
        }
        return total;
    }
}
