package ai.kompile.project.server.xet;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the Xet shard binary parser. The hash-encoding test uses the exact vector from the
 * CAS API spec; the shard test round-trips a hand-built shard and asserts the extracted terms and
 * per-chunk byte offsets.
 */
public class ShardCodecTest {

    @Test
    public void hashToHexMatchesSpecVector() {
        byte[] h = seq(0, 32);
        String expected = "07060504030201000f0e0d0c0b0a090817161514131211101f1e1d1c1b1a1918";
        assertEquals(expected, ShardCodec.hashToHex(h));
        assertArrayEquals(h, ShardCodec.hexToHash(expected));
    }

    @Test
    public void parsesSyntheticShard() {
        byte[] fileHash = seq(0, 32);
        byte[] xorbHash = fill(0x11, 32);
        byte[] chunk0 = fill(0x21, 32);
        byte[] chunk1 = fill(0x22, 32);

        ByteBuffer b = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN);
        b.put(fill(0xAB, 32));
        b.putLong(2);
        b.putLong(0);
        b.put(fileHash);
        b.putInt(0);
        b.putInt(1);
        b.put(new byte[8]);
        b.put(xorbHash);
        b.putInt(0);
        b.putInt(1000);
        b.putInt(0);
        b.putInt(2);
        b.put(fill(0xFF, 32));
        b.put(new byte[16]);
        b.put(xorbHash);
        b.putInt(0);
        b.putInt(2);
        b.putInt(1000);
        b.putInt(600);
        b.put(chunk0);
        b.putInt(0);
        b.putInt(500);
        b.put(new byte[8]);
        b.put(chunk1);
        b.putInt(300);
        b.putInt(500);
        b.put(new byte[8]);
        b.put(fill(0xFF, 32));
        b.put(new byte[16]);

        byte[] data = Arrays.copyOf(b.array(), b.position());
        ShardCodec.ParsedShard shard = ShardCodec.parse(data);

        assertEquals(1, shard.files.size());
        ShardCodec.FileInfo fi = shard.files.get(0);
        assertEquals(ShardCodec.hashToHex(fileHash), fi.fileId);
        assertEquals(1, fi.terms.size());
        ShardCodec.Term t = fi.terms.get(0);
        assertEquals(ShardCodec.hashToHex(xorbHash), t.xorbHash);
        assertEquals(0, t.chunkIndexStart);
        assertEquals(2, t.chunkIndexEnd);
        assertEquals(1000, t.unpackedSegmentBytes);

        assertEquals(1, shard.xorbs.size());
        ShardCodec.XorbInfo xi = shard.xorbs.get(0);
        assertEquals(ShardCodec.hashToHex(xorbHash), xi.xorbHash);
        assertEquals(600, xi.numBytesOnDisk);
        assertArrayEquals(new long[]{0, 300}, xi.chunkByteOffsets());
        assertArrayEquals(new long[]{300, 300}, xi.chunkSerializedLengths());
        assertEquals(ShardCodec.hashToHex(chunk0), xi.chunks.get(0).chunkHash);
        assertEquals(ShardCodec.hashToHex(chunk1), xi.chunks.get(1).chunkHash);
    }

    private static byte[] seq(int from, int len) {
        byte[] a = new byte[len];
        for (int i = 0; i < len; i++) {
            a[i] = (byte) (from + i);
        }
        return a;
    }

    private static byte[] fill(int v, int len) {
        byte[] a = new byte[len];
        Arrays.fill(a, (byte) v);
        return a;
    }
}
