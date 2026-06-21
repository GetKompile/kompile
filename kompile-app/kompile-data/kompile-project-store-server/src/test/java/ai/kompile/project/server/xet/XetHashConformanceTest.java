package ai.kompile.project.server.xet;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the Xet keyed-BLAKE3 hashing primitives in {@link XetHash} against the official TCK
 * reference vectors: the sample chunk files, the xorb range hash, and the verification shard.
 */
public class XetHashConformanceTest {

    private static final String RANGE_HASH = "d81c11b1fc9bc2a25587108c675bbfe65ca2e5d350b0cd92c58329fcc8444178";
    private static final String[] CHUNK_HASHES = {
            "b10aa1dc71c61661de92280c41a188aabc47981739b785724a099945d8dc5ce4",
            "26255591fa803b6baf25d88c315b8a6f5153d5bcfdf18ec5ef526264e0ccc907",
            "099cb228194fe640e36a6c7d274ee5ed3a714ccd557a0951d9b6b43a7292b5d1"
    };

    @Test
    public void chunkHashesMatchReferenceFiles() {
        for (String expected : CHUNK_HASHES) {
            byte[] data = load("/xet-tck/" + expected + ".chunk");
            assertEquals(expected, XetHash.chunkHashHex(data), "chunk hash for " + expected);
        }
    }

    @Test
    public void termVerificationHashMatchesReferenceRangeHash() throws IOException {
        List<byte[]> raws = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                XetHashConformanceTest.class.getResourceAsStream("/xet-tck/xorb.chunks"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                raws.add(ShardCodec.hexToHash(line.split("\\s+")[0]));
            }
        }
        assertEquals(796, raws.size());
        assertEquals(RANGE_HASH, ShardCodec.hashToHex(XetHash.termVerificationHashRaw(raws)));
    }

    @Test
    public void verificationShardTermHashesAreReproducible() {
        ShardCodec.ParsedShard shard = ShardCodec.parse(load("/xet-tck/conformance.shard.verification"));
        Map<String, ShardCodec.XorbInfo> byHash = new HashMap<>();
        for (ShardCodec.XorbInfo xi : shard.xorbs) {
            byHash.put(xi.xorbHash, xi);
        }
        int checked = 0;
        for (ShardCodec.FileInfo fi : shard.files) {
            for (ShardCodec.Term t : fi.terms) {
                assertNotNull(t.rangeVerificationHex);
                ShardCodec.XorbInfo xi = byHash.get(t.xorbHash);
                assertNotNull(xi);
                List<byte[]> raws = new ArrayList<>();
                for (int i = t.chunkIndexStart; i < t.chunkIndexEnd; i++) {
                    raws.add(ShardCodec.hexToHash(xi.chunks.get(i).chunkHash));
                }
                assertEquals(t.rangeVerificationHex, ShardCodec.hashToHex(XetHash.termVerificationHashRaw(raws)));
                checked++;
            }
        }
        assertTrue(checked > 0);
    }

    private static byte[] load(String resource) {
        try (InputStream in = XetHashConformanceTest.class.getResourceAsStream(resource)) {
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
