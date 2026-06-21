package ai.kompile.project.server.xet;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conformance tests for {@link ShardCodec} against the official Xet protocol reference files (the
 * "TCK"), from the {@code xet-team/xet-spec-reference-files} dataset.
 */
public class ShardCodecConformanceTest {

    private static final String FILE_HASH = "118a53328412787fee04011dcf82fdc4acf3a4a1eddec341c910d30a306aaf97";
    private static final String XORB_HASH = "eea25d6ee393ccae385820daed127b96ef0ea034dfb7cf6da3a950ce334b7632";
    private static final String HMAC_KEY = "1234567812345678123456781234567812345678123456781234567812345678";

    @Test
    public void baseShardReconstructsFileWithoutVerification() {
        ShardCodec.ParsedShard shard = ShardCodec.parse(load("/xet-tck/conformance.shard"));

        assertEquals(1, shard.files.size());
        ShardCodec.FileInfo fi = shard.files.get(0);
        assertEquals(FILE_HASH, fi.fileId);
        assertFalse(fi.terms.isEmpty());
        for (ShardCodec.Term t : fi.terms) {
            assertEquals(XORB_HASH, t.xorbHash);
            assertNull(t.rangeVerificationHex);
        }

        assertEquals(1, shard.xorbs.size());
        ShardCodec.XorbInfo xi = shard.xorbs.get(0);
        assertEquals(XORB_HASH, xi.xorbHash);
        assertTrue(xi.chunks.size() > 100, "real CSV xorb has many chunks (got " + xi.chunks.size() + ")");
        int lastEnd = fi.terms.get(fi.terms.size() - 1).chunkIndexEnd;
        assertEquals(xi.chunks.size(), lastEnd);

        // The shard CAS-info chunk_byte_range_start values are RAW (uncompressed) offsets: each delta
        // equals the previous chunk's unpacked size and they sum to num_bytes_in_cas, exceeding the
        // (compressed) num_bytes_on_disk. SERIALIZED offsets for reconstruction are derived from the xorb.
        long[] offsets = xi.chunkByteOffsets();
        assertEquals(0L, offsets[0]);
        for (int i = 1; i < offsets.length; i++) {
            assertEquals(xi.chunks.get(i - 1).unpackedSegmentBytes, offsets[i] - offsets[i - 1]);
        }
        long lastStart = offsets[offsets.length - 1];
        long lastUnpacked = xi.chunks.get(xi.chunks.size() - 1).unpackedSegmentBytes;
        assertEquals(xi.numBytesInCas, lastStart + lastUnpacked);
        assertTrue(xi.numBytesOnDisk < xi.numBytesInCas, "serialized xorb is smaller than raw (compressed)");
    }

    @Test
    public void verificationShardHasVerificationHashesAndFooter() {
        ShardCodec.ParsedShard shard = ShardCodec.parse(load("/xet-tck/conformance.shard.verification"));
        assertEquals(1, shard.files.size());
        ShardCodec.FileInfo fi = shard.files.get(0);
        assertEquals(FILE_HASH, fi.fileId);
        for (ShardCodec.Term t : fi.terms) {
            assertNotNull(t.rangeVerificationHex);
        }
        assertEquals(XORB_HASH, shard.xorbs.get(0).xorbHash);
        assertTrue(shard.hasFooter);
    }

    @Test
    public void noFooterShardIsTheUploadFormWithVerification() {
        ShardCodec.ParsedShard shard = ShardCodec.parse(load("/xet-tck/conformance.shard.verification-no-footer"));
        assertEquals(1, shard.files.size());
        assertEquals(FILE_HASH, shard.files.get(0).fileId);
        assertNotNull(shard.files.get(0).terms.get(0).rangeVerificationHex);
        assertFalse(shard.hasFooter);
    }

    @Test
    public void dedupeShardHasNoFilesAndCarriesHmacFooter() {
        ShardCodec.ParsedShard shard = ShardCodec.parse(load("/xet-tck/conformance.shard.dedupe"));
        assertTrue(shard.files.isEmpty());
        assertEquals(1, shard.xorbs.size());
        assertEquals(XORB_HASH, shard.xorbs.get(0).xorbHash);
        assertTrue(shard.hasFooter);
        assertNotNull(shard.chunkHashHmacKey);
        assertEquals(HMAC_KEY, ShardCodec.hashToHex(shard.chunkHashHmacKey));
    }

    @Test
    public void globalDedupeShardAssemblesFromCapturedCasInfo() {
        ShardCodec.ParsedShard base = ShardCodec.parse(load("/xet-tck/conformance.shard"));
        ShardCodec.XorbInfo xi = base.xorbs.get(0);
        assertNotNull(xi.casInfoBlockBytes);

        byte[] dedupe = ShardCodec.serializeGlobalDedupe(xi.casInfoBlockBytes, 0L, 0L);
        ShardCodec.ParsedShard parsed = ShardCodec.parse(dedupe);

        assertTrue(parsed.files.isEmpty());
        assertEquals(1, parsed.xorbs.size());
        assertEquals(XORB_HASH, parsed.xorbs.get(0).xorbHash);
        assertEquals(xi.chunks.size(), parsed.xorbs.get(0).chunks.size());
        assertEquals(xi.chunks.get(0).chunkHash, parsed.xorbs.get(0).chunks.get(0).chunkHash);
        assertTrue(parsed.hasFooter);
    }

    private static byte[] load(String resource) {
        try (InputStream in = ShardCodecConformanceTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing test resource: " + resource);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
