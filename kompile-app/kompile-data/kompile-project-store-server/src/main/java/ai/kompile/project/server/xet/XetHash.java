package ai.kompile.project.server.xet;

import org.apache.commons.codec.digest.Blake3;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Xet protocol hashing primitives (keyed BLAKE3). Used to validate uploaded shards against the data
 * the client claims to hold.
 *
 * <ul>
 *   <li><b>Chunk hash</b> = {@code blake3_keyed(DATA_KEY, chunk_data)}.</li>
 *   <li><b>Term verification hash</b> = {@code blake3_keyed(VERIFICATION_KEY, concat(raw chunk hashes))}
 *       over the chunks in a reconstruction term's range. Every term in an uploaded shard carries one
 *       (the {@code FileVerificationEntry.range_hash}); the server recomputes and compares.</li>
 * </ul>
 *
 * <p>(The xorb/file MerkleTree hashes are intentionally not implemented here — their tree arity is not
 * fully specified in prose — they are not needed for upload verification.)
 *
 * @see <a href="https://huggingface.co/docs/xet/hashing">Xet Hashing</a>
 */
public final class XetHash {

    /** Keyed-BLAKE3 key for chunk-data hashing (DATA_KEY). */
    private static final byte[] DATA_KEY = key(
            102, 151, 245, 119, 91, 149, 80, 222, 49, 53, 203, 172, 165, 151, 24, 28,
            157, 228, 33, 16, 155, 235, 43, 88, 180, 208, 176, 75, 147, 173, 242, 41);

    /** Keyed-BLAKE3 key for term verification hashing (VERIFICATION_KEY). */
    private static final byte[] VERIFICATION_KEY = key(
            127, 24, 87, 214, 206, 86, 237, 102, 18, 127, 249, 19, 231, 165, 195, 243,
            164, 205, 38, 213, 181, 219, 73, 230, 65, 36, 152, 127, 40, 251, 148, 195);

    private XetHash() {
    }

    /** Raw 32-byte chunk hash of the (uncompressed) chunk data. */
    public static byte[] chunkHashRaw(byte[] data) {
        return Blake3.keyedHash(DATA_KEY, data);
    }

    /** Chunk hash in canonical 64-char hex string form. */
    public static String chunkHashHex(byte[] data) {
        return ShardCodec.hashToHex(chunkHashRaw(data));
    }

    /** Raw 32-byte verification hash over a sequence of raw 32-byte chunk hashes (in order). */
    public static byte[] termVerificationHashRaw(List<byte[]> rawChunkHashes) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(rawChunkHashes.size() * 32);
        for (byte[] h : rawChunkHashes) {
            buffer.write(h, 0, h.length);
        }
        return Blake3.keyedHash(VERIFICATION_KEY, buffer.toByteArray());
    }

    /** Term verification hash in canonical 64-char hex string form. */
    public static String termVerificationHashHex(List<byte[]> rawChunkHashes) {
        return ShardCodec.hashToHex(termVerificationHashRaw(rawChunkHashes));
    }

    private static byte[] key(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }
}
