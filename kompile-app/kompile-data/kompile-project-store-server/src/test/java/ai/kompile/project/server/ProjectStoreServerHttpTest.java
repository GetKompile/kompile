package ai.kompile.project.server;

import ai.kompile.project.server.xet.ShardCodec;
import ai.kompile.project.server.xet.XetHash;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end HTTP test of the ported server: create a project, mint a CAS token, upload a xorb +
 * shard, reconstruct (asserting serialized byte ranges), download a xorb byte range, and run a global
 * dedupe query — all over real HTTP against the running Spring Boot app.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProjectStoreServerHttpTest {

    @Autowired
    TestRestTemplate rest;

    @LocalServerPort
    int port;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void gitPushAndCloneRoundTrip() throws Exception {
        String slug = "e2e-git-" + Long.toHexString(System.nanoTime());
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", "alice");
        body.put("slug", slug);
        body.put("visibility", "public");
        assertEquals(HttpStatus.CREATED,
                rest.postForEntity("/api/projects", new HttpEntity<>(body, json), String.class).getStatusCode());

        String gitUrl = "http://localhost:" + port + "/git/alice/" + slug + ".git";

        Path work = Files.createTempDirectory("pss-work");
        try (Git local = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
            Files.write(work.resolve("README.md"), "hello kompile".getBytes(StandardCharsets.UTF_8));
            local.add().addFilepattern("README.md").call();
            local.commit().setMessage("init").setAuthor("alice", "alice@example.com").call();
            local.push().setRemote(gitUrl).setRefSpecs(new RefSpec("HEAD:refs/heads/main")).call();
        }

        Path cloneDir = Files.createTempDirectory("pss-clone");
        try (Git cloned = Git.cloneRepository().setURI(gitUrl).setDirectory(cloneDir.toFile()).call()) {
            File readme = cloneDir.resolve("README.md").toFile();
            assertTrue(readme.exists(), "cloned README.md should exist");
            assertEquals("hello kompile",
                    new String(Files.readAllBytes(readme.toPath()), StandardCharsets.UTF_8));
        }
    }

    @Test
    void browseTreeAndDownloadArchive() throws Exception {
        String slug = "e2e-archive-" + Long.toHexString(System.nanoTime());
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", "alice");
        body.put("slug", slug);
        body.put("visibility", "public");
        assertEquals(HttpStatus.CREATED,
                rest.postForEntity("/api/projects", new HttpEntity<>(body, json), String.class).getStatusCode());

        String gitUrl = "http://localhost:" + port + "/git/alice/" + slug + ".git";

        // Push a commit with a root file and a nested file so we can exercise tree browsing + archive.
        Path work = Files.createTempDirectory("pss-arch");
        try (Git local = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
            Files.write(work.resolve("README.md"), "hello kompile".getBytes(StandardCharsets.UTF_8));
            Files.createDirectories(work.resolve("docs"));
            Files.write(work.resolve("docs/guide.md"), "the guide".getBytes(StandardCharsets.UTF_8));
            local.add().addFilepattern(".").call();
            local.commit().setMessage("init").setAuthor("alice", "alice@example.com").call();
            local.push().setRemote(gitUrl).setRefSpecs(new RefSpec("HEAD:refs/heads/main")).call();
        }

        // Browse the root tree: a README.md blob and a docs subtree.
        JsonNode root = mapper.readTree(
                rest.getForEntity("/api/projects/alice/" + slug + "/tree/main", String.class).getBody());
        Map<String, String> rootTypes = new LinkedHashMap<>();
        root.forEach(e -> rootTypes.put(e.get("name").asText(), e.get("type").asText()));
        assertEquals("blob", rootTypes.get("README.md"));
        assertEquals("tree", rootTypes.get("docs"));

        // Drill into docs/.
        JsonNode docs = mapper.readTree(
                rest.getForEntity("/api/projects/alice/" + slug + "/tree/main?path=docs", String.class).getBody());
        assertEquals("docs/guide.md", docs.get(0).get("path").asText());

        // Download a single blob.
        ResponseEntity<byte[]> blob = rest.getForEntity(
                "/api/projects/alice/" + slug + "/blob/main?path=README.md", byte[].class);
        assertEquals(HttpStatus.OK, blob.getStatusCode());
        assertEquals("hello kompile", new String(blob.getBody(), StandardCharsets.UTF_8));

        // Download the whole project as a ZIP and verify every file round-trips.
        ResponseEntity<byte[]> zip = rest.getForEntity(
                "/api/projects/alice/" + slug + "/archive/main", byte[].class);
        assertEquals(HttpStatus.OK, zip.getStatusCode());
        assertEquals("attachment; filename=\"" + slug + "-main.zip\"",
                zip.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        Map<String, String> archived = unzip(zip.getBody());
        assertEquals("hello kompile", archived.get("README.md"));
        assertEquals("the guide", archived.get("docs/guide.md"));
    }

    @Test
    void rejectsUnsafeProjectNamesAndDefaultBranches() {
        String[] invalid = {"..", ".hidden", "../escape", "/absolute", "a/b", "a\\b",
                "é", "name\nInjected", "a".repeat(65)};
        for (String value : invalid) {
            assertEquals(HttpStatus.BAD_REQUEST, createProject(value, "valid", "main").getStatusCode(), value);
            assertEquals(HttpStatus.BAD_REQUEST, createProject("valid", value, "main").getStatusCode(), value);
        }
        for (String ref : new String[]{"../main", "refs/heads/main", "main\r\nX-Test: injected",
                "é", "topic.lock", "a".repeat(129)}) {
            assertEquals(HttpStatus.BAD_REQUEST,
                    createProject("valid", "ref-" + Long.toHexString(System.nanoTime()), ref).getStatusCode(), ref);
        }
    }

    @Test
    void archiveReturnsNotFoundBeforeStreamingForEmptyOrUnresolvedRefs() {
        String slug = "empty-" + Long.toHexString(System.nanoTime());
        assertEquals(HttpStatus.CREATED, createProject("alice", slug, "main").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                rest.getForEntity("/api/projects/alice/" + slug + "/archive/main", byte[].class).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                rest.getForEntity("/api/projects/alice/" + slug + "/archive/deadbeef", byte[].class).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                rest.getForEntity("/api/projects/alice/" + slug + "/archive/bad..ref", byte[].class).getStatusCode());
    }

    private ResponseEntity<String> createProject(String namespace, String slug, String defaultBranch) {
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", namespace);
        body.put("slug", slug);
        body.put("defaultBranch", defaultBranch);
        return rest.postForEntity("/api/projects", new HttpEntity<>(body, json), String.class);
    }

    private static Map<String, String> unzip(byte[] data) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int n;
                while ((n = zis.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                }
                out.put(entry.getName(), bos.toString(StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    @Test
    void xetRoundTripAndProjectCreate() throws Exception {
        String suffix = Long.toHexString(System.nanoTime());
        String slug = "e2e-" + suffix;

        // 1. Create a project.
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", "alice");
        body.put("slug", slug);
        body.put("visibility", "public");
        ResponseEntity<String> created = rest.postForEntity("/api/projects", new HttpEntity<>(body, json), String.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());

        // 2. Mint a write CAS token.
        ResponseEntity<String> tokenResp = rest.getForEntity(
                "/api/models/alice/" + slug + "/xet-write-token/main", String.class);
        assertEquals(HttpStatus.OK, tokenResp.getStatusCode());
        JsonNode tok = mapper.readTree(tokenResp.getBody());
        String casToken = tok.get("accessToken").asText();
        assertNotNull(casToken);

        long uniq = System.nanoTime();
        byte[] xorbHashBytes = seeded((byte) 0x11, uniq);
        String xorbHash = ShardCodec.hashToHex(xorbHashBytes);
        byte[] fileHashBytes = seeded((byte) 0x00, uniq);
        String fileId = ShardCodec.hashToHex(fileHashBytes);
        byte[] c0 = seeded((byte) 0x21, uniq);
        byte[] c1 = seeded((byte) 0x22, uniq);

        HttpHeaders octet = new HttpHeaders();
        octet.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        octet.setBearerAuth(casToken);

        // 3. Upload a valid 2-chunk xorb (each chunk 8-byte header + 292 bytes = 300; total 600).
        ResponseEntity<String> xorbUp = rest.exchange("/xet/cas/v1/xorbs/default/" + xorbHash,
                HttpMethod.POST, new HttpEntity<>(buildXorb(2, 292), octet), String.class);
        assertEquals(HttpStatus.OK, xorbUp.getStatusCode());

        // 4. Upload a shard registering a file made of chunks [0,2) of that xorb.
        ResponseEntity<String> shardUp = rest.exchange("/xet/cas/v1/shards",
                HttpMethod.POST, new HttpEntity<>(buildShard(fileHashBytes, xorbHashBytes, c0, c1), octet), String.class);
        assertEquals(HttpStatus.OK, shardUp.getStatusCode());

        // 5. Reconstruct and assert terms + fetch info (serialized byte range derived from the xorb).
        JsonNode recon = mapper.readTree(rest.getForEntity("/xet/cas/v1/reconstructions/" + fileId, String.class).getBody());
        assertEquals(xorbHash, recon.get("terms").get(0).get("hash").asText());
        JsonNode fetch = recon.get("fetch_info").get(xorbHash).get(0);
        assertTrue(fetch.get("url").asText().contains(xorbHash));
        assertEquals(0L, fetch.get("url_range").get("start").asLong());
        assertEquals(599L, fetch.get("url_range").get("end").asLong());

        // 6. Download a byte range of the xorb (server serves 206 Partial Content).
        HttpHeaders rangeReq = new HttpHeaders();
        rangeReq.set(HttpHeaders.RANGE, "bytes=0-299");
        ResponseEntity<byte[]> ranged = rest.exchange("/xet/cas/v1/xorbs/default/" + xorbHash,
                HttpMethod.GET, new HttpEntity<>(rangeReq), byte[].class);
        assertEquals(HttpStatus.PARTIAL_CONTENT, ranged.getStatusCode());
        assertEquals(300, ranged.getBody().length);

        // 7. Global dedupe: a known chunk hash returns a shard containing its xorb.
        ResponseEntity<byte[]> dedupe = rest.getForEntity(
                "/xet/cas/v1/chunks/default-merkledb/" + ShardCodec.hashToHex(c0), byte[].class);
        assertEquals(HttpStatus.OK, dedupe.getStatusCode());
        ShardCodec.ParsedShard parsedDedupe = ShardCodec.parse(dedupe.getBody());
        assertTrue(parsedDedupe.files.isEmpty());
        assertEquals(xorbHash, parsedDedupe.xorbs.get(0).xorbHash);
    }

    @Test
    void tamperedShardVerificationIsRejected() {
        String slug = "e2e-verify-" + Long.toHexString(System.nanoTime());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", "alice");
        body.put("slug", slug);
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        rest.postForEntity("/api/projects", new HttpEntity<>(body, json), String.class);

        String casToken = rest.getForObject("/api/models/alice/" + slug + "/xet-write-token/main", JsonNode.class)
                .get("accessToken").asText();

        long uniq = System.nanoTime();
        byte[] xorbHashBytes = seeded((byte) 0x31, uniq);
        byte[] c0 = seeded((byte) 0x41, uniq);
        byte[] c1 = seeded((byte) 0x42, uniq);

        HttpHeaders octet = new HttpHeaders();
        octet.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        octet.setBearerAuth(casToken);

        // Correct verification hash -> accepted.
        byte[] correct = XetHash.termVerificationHashRaw(Arrays.asList(c0, c1));
        assertEquals(HttpStatus.OK, rest.exchange("/xet/cas/v1/shards", HttpMethod.POST,
                new HttpEntity<>(buildVerificationShard(seeded((byte) 0x30, uniq), xorbHashBytes, c0, c1, correct), octet),
                String.class).getStatusCode());

        // Tampered verification hash -> 400.
        assertEquals(HttpStatus.BAD_REQUEST, rest.exchange("/xet/cas/v1/shards", HttpMethod.POST,
                new HttpEntity<>(buildVerificationShard(seeded((byte) 0x33, uniq), xorbHashBytes, c0, c1, new byte[32]), octet),
                String.class).getStatusCode());
    }

    // ---- shard / xorb builders ----

    private static byte[] buildShard(byte[] fileHash, byte[] xorbHash, byte[] chunk0Hash, byte[] chunk1Hash) {
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
        b.put(chunk0Hash);
        b.putInt(0);
        b.putInt(500);
        b.put(new byte[8]);
        b.put(chunk1Hash);
        b.putInt(300);
        b.putInt(500);
        b.put(new byte[8]);
        b.put(fill(0xFF, 32));
        b.put(new byte[16]);
        return Arrays.copyOf(b.array(), b.position());
    }

    private static byte[] buildVerificationShard(byte[] fileHash, byte[] xorbHash,
                                                 byte[] chunk0Hash, byte[] chunk1Hash, byte[] rangeHash) {
        ByteBuffer b = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN);
        b.put(fill(0xAB, 32));
        b.putLong(2);
        b.putLong(0);
        b.put(fileHash);
        b.putInt(0x80000000); // WITH_VERIFICATION
        b.putInt(1);
        b.put(new byte[8]);
        b.put(xorbHash);
        b.putInt(0);
        b.putInt(1000);
        b.putInt(0);
        b.putInt(2);
        b.put(rangeHash);
        b.put(new byte[16]);
        b.put(fill(0xFF, 32));
        b.put(new byte[16]);
        b.put(xorbHash);
        b.putInt(0);
        b.putInt(2);
        b.putInt(1000);
        b.putInt(600);
        b.put(chunk0Hash);
        b.putInt(0);
        b.putInt(500);
        b.put(new byte[8]);
        b.put(chunk1Hash);
        b.putInt(300);
        b.putInt(500);
        b.put(new byte[8]);
        b.put(fill(0xFF, 32));
        b.put(new byte[16]);
        return Arrays.copyOf(b.array(), b.position());
    }

    private static byte[] buildXorb(int numChunks, int dataLen) {
        ByteBuffer b = ByteBuffer.allocate(numChunks * (8 + dataLen)).order(ByteOrder.LITTLE_ENDIAN);
        for (int c = 0; c < numChunks; c++) {
            b.put((byte) 0);
            b.put((byte) (dataLen & 0xFF));
            b.put((byte) ((dataLen >> 8) & 0xFF));
            b.put((byte) ((dataLen >> 16) & 0xFF));
            b.put((byte) 0);
            b.put((byte) (dataLen & 0xFF));
            b.put((byte) ((dataLen >> 8) & 0xFF));
            b.put((byte) ((dataLen >> 16) & 0xFF));
            b.put(new byte[dataLen]);
        }
        return b.array();
    }

    private static byte[] seeded(byte base, long seed) {
        byte[] a = new byte[32];
        Arrays.fill(a, base);
        for (int i = 0; i < 8; i++) {
            a[i] = (byte) ((seed >> (i * 8)) & 0xFF);
        }
        return a;
    }

    private static byte[] fill(int v, int len) {
        byte[] a = new byte[len];
        Arrays.fill(a, (byte) v);
        return a;
    }
}
