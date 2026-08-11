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
 *  limitations under the License.
 */

package ai.kompile.crawl.graph;

import ai.kompile.core.graphbuilder.GraphBuildCompletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DocumentHashStore}.
 *
 * <p>Uses a temp directory as the "kompile data dir" root so tests are isolated
 * from any real ~/.kompile installation.  The store is pointed at the temp dir
 * via {@code kompile.data.dir} system property.
 */
class DocumentHashStoreTest {

    @TempDir
    Path tempDir;

    /** The store under test, redirected to tempDir. */
    private DocumentHashStore store;

    @BeforeEach
    void setUp() throws Exception {
        // Point KompileHome.resolvedHomeDirectory() at tempDir for this test.
        System.setProperty("kompile.data.dir", tempDir.toString());
        store = new DocumentHashStore();
    }

    // ── sha256Hex ────────────────────────────────────────────────────────────

    @Test
    void sha256Hex_nullBytes_returnsNull() {
        assertNull(DocumentHashStore.sha256Hex((byte[]) null));
    }

    @Test
    void sha256Hex_nullString_returnsNull() {
        assertNull(DocumentHashStore.sha256Hex((String) null));
    }

    @Test
    void sha256Hex_emptyBytes_returnsKnownHash() {
        // SHA-256 of empty byte array is a known constant.
        String hash = DocumentHashStore.sha256Hex(new byte[0]);
        assertNotNull(hash);
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash);
        assertEquals(64, hash.length(), "SHA-256 hex must be 64 characters");
    }

    @Test
    void sha256Hex_string_matchesByteVersion() {
        String content = "hello world";
        String viaString = DocumentHashStore.sha256Hex(content);
        String viaBytes  = DocumentHashStore.sha256Hex(content.getBytes(StandardCharsets.UTF_8));
        assertEquals(viaBytes, viaString);
    }

    @Test
    void sha256Hex_differentContent_differentHash() {
        String h1 = DocumentHashStore.sha256Hex("foo");
        String h2 = DocumentHashStore.sha256Hex("bar");
        assertNotEquals(h1, h2);
    }

    @Test
    void sha256Hex_sameContent_sameHash() {
        String content = "deterministic";
        assertEquals(DocumentHashStore.sha256Hex(content), DocumentHashStore.sha256Hex(content));
    }

    // ── lookup / recordHash / isUnchanged ────────────────────────────────────

    @Test
    void lookup_noEntry_returnsNull() {
        assertNull(store.lookup(1L, "/some/path/file.txt"));
    }

    @Test
    void recordHash_then_lookup_returnsEntry() {
        String hash = DocumentHashStore.sha256Hex("content");
        store.recordHash(1L, "/doc/a.txt", hash, "job-001");

        DocumentHashStore.HashEntry entry = store.lookup(1L, "/doc/a.txt");
        assertNotNull(entry);
        assertEquals(hash, entry.getContentHash());
        assertEquals("job-001", entry.getLastCrawlRunId());
        assertNotNull(entry.getLastProcessedAt());
    }

    @Test
    void isUnchanged_noEntry_returnsFalse() {
        assertFalse(store.isUnchanged(1L, "/doc/a.txt", DocumentHashStore.sha256Hex("x")));
    }

    @Test
    void isUnchanged_matchingHash_returnsTrue() {
        String hash = DocumentHashStore.sha256Hex("unchanged content");
        store.recordHash(2L, "/doc/b.txt", hash, "job-abc");

        assertTrue(store.isUnchanged(2L, "/doc/b.txt", hash));
    }

    @Test
    void isUnchanged_differentHash_returnsFalse() {
        String oldHash = DocumentHashStore.sha256Hex("old content");
        String newHash = DocumentHashStore.sha256Hex("new content");
        store.recordHash(3L, "/doc/c.txt", oldHash, "job-x");

        assertFalse(store.isUnchanged(3L, "/doc/c.txt", newHash));
    }

    @Test
    void isUnchanged_nullHash_returnsFalse() {
        store.recordHash(4L, "/doc/d.txt", "somehash", "job-y");
        assertFalse(store.isUnchanged(4L, "/doc/d.txt", null));
    }

    @Test
    void isUnchanged_nullDocumentId_returnsFalse() {
        assertFalse(store.isUnchanged(4L, null, "somehash"));
    }

    // ── Persistence / atomic write ───────────────────────────────────────────

    @Test
    void persistedAcrossInstances() throws Exception {
        // Record in one instance.
        String hash = DocumentHashStore.sha256Hex("data");
        store.recordHash(5L, "/x/y.pdf", hash, "job-persist");

        // Create a fresh instance pointing at the same tempDir.
        DocumentHashStore store2 = new DocumentHashStore();
        // store2 also uses the same system property (kompile.data.dir=tempDir).
        DocumentHashStore.HashEntry entry = store2.lookup(5L, "/x/y.pdf");
        assertNotNull(entry, "Entry should survive across store instances");
        assertEquals(hash, entry.getContentHash());
    }

    @Test
    void hashFileIsWrittenUnderDataGraphSubdir() throws Exception {
        store.recordHash(7L, "/some/file.txt", DocumentHashStore.sha256Hex("x"), "job-1");
        Path expected = tempDir.resolve("data/graph/7/document-hashes.json");
        assertTrue(Files.exists(expected),
                "Hash file should be at data/graph/<factSheetId>/document-hashes.json");
    }

    @Test
    void globalScopeUsedWhenFactSheetIdIsNull() throws Exception {
        store.recordHash(null, "/g/doc.txt", DocumentHashStore.sha256Hex("g"), "job-g");
        Path expected = tempDir.resolve("data/graph/global/document-hashes.json");
        assertTrue(Files.exists(expected),
                "Null factSheetId should use 'global' subdirectory");
        DocumentHashStore.HashEntry entry = store.lookup(null, "/g/doc.txt");
        assertNotNull(entry);
    }

    @Test
    void multipleEntriesSameFactSheet_allPersisted() {
        String h1 = DocumentHashStore.sha256Hex("one");
        String h2 = DocumentHashStore.sha256Hex("two");
        store.recordHash(8L, "/a.txt", h1, "j1");
        store.recordHash(8L, "/b.txt", h2, "j2");

        assertTrue(store.isUnchanged(8L, "/a.txt", h1));
        assertTrue(store.isUnchanged(8L, "/b.txt", h2));
        assertFalse(store.isUnchanged(8L, "/a.txt", h2), "Cross-file hash must not match");
    }

    @Test
    void updateEntry_overwritesPreviousHash() {
        String old = DocumentHashStore.sha256Hex("v1");
        String updated = DocumentHashStore.sha256Hex("v2");
        store.recordHash(9L, "/evolving.txt", old, "j1");
        // Update with a new hash for the same path.
        store.recordHash(9L, "/evolving.txt", updated, "j2");

        assertFalse(store.isUnchanged(9L, "/evolving.txt", old), "Old hash must no longer match");
        assertTrue(store.isUnchanged(9L, "/evolving.txt", updated), "New hash must match");
    }

    @Test
    void stagedHashIsInvisibleUntilGraphBuildCompletes() {
        String hash = DocumentHashStore.sha256Hex("candidate graph content");
        store.stageHash(10L, "/docs/candidate.md", hash, "crawl-10", "DIRECTORY:/docs");

        assertNull(store.lookup(10L, "/docs/candidate.md"),
                "A load must not publish the manifest before graph persistence succeeds");

        store.onGraphBuildCompleted(new GraphBuildCompletedEvent(
                this, "crawl-10", 3, 2, 10L, Map.of()));

        DocumentHashStore.HashEntry committed = store.lookup(10L, "/docs/candidate.md");
        assertNotNull(committed);
        assertEquals(hash, committed.getContentHash());
        assertEquals("DIRECTORY:/docs", committed.getSourceScopeId());
    }

    @Test
    void discardedRunDoesNotChangeDurableManifest() {
        store.stageHash(11L, "/docs/failed.md", DocumentHashStore.sha256Hex("partial"),
                "crawl-failed", "DIRECTORY:/docs");

        store.discardStaged("crawl-failed");
        DocumentHashStore.CommitSummary summary = store.commitStaged("crawl-failed");

        assertEquals(0, summary.upserted());
        assertEquals(0, summary.deleted());
        assertNull(store.lookup(11L, "/docs/failed.md"));
    }

    @Test
    void sourceScopedReconciliationDeletesOnlyMissingMembersAfterCommit() {
        String scope = "DIRECTORY:/docs";
        store.recordHash(12L, "/docs/live.md", DocumentHashStore.sha256Hex("live"), "old", scope);
        store.recordHash(12L, "/docs/deleted.md", DocumentHashStore.sha256Hex("gone"), "old", scope);
        store.recordHash(12L, "/other/kept.md", DocumentHashStore.sha256Hex("other"), "old",
                "DIRECTORY:/other");

        List<String> missing = store.findMissingSources(12L, scope, Set.of("/docs/live.md"));
        assertEquals(List.of("/docs/deleted.md"), missing);

        store.stageDeletion(12L, "/docs/deleted.md", "crawl-12");
        store.onGraphBuildCompleted(new GraphBuildCompletedEvent(
                this, "crawl-12", 0, 0, 12L, Map.of()));

        assertNull(store.lookup(12L, "/docs/deleted.md"));
        assertNotNull(store.lookup(12L, "/docs/live.md"));
        assertNotNull(store.lookup(12L, "/other/kept.md"));
    }

    // ── CrawlRuntimeConfig incremental flags ─────────────────────────────────

    @Test
    void crawlRuntimeConfigDefaults_incrementalEnabledForceFullOff() throws Exception {
        // Exercise via the ConfigManager defaults — no file on disk.
        CrawlRuntimeConfigManager mgr = new CrawlRuntimeConfigManager(
                tempDir.resolve("no-such-config.json"));
        CrawlRuntimeConfigManager.CrawlRuntimeConfig cfg = mgr.refreshRuntimeConfig();
        assertTrue(cfg.crawlIncrementalByContentHash, "incremental should default to true");
        assertFalse(cfg.crawlForceFullRecrawl, "forceFullRecrawl should default to false");
    }

    @Test
    void crawlRuntimeConfig_readsFlagsFromJson() throws Exception {
        Path cfgFile = tempDir.resolve("cfg.json");
        Files.writeString(cfgFile,
                "{\"crawlIncrementalByContentHash\":false,\"crawlForceFullRecrawl\":true}");
        CrawlRuntimeConfigManager mgr = new CrawlRuntimeConfigManager(cfgFile);
        CrawlRuntimeConfigManager.CrawlRuntimeConfig cfg = mgr.refreshRuntimeConfig();
        assertFalse(cfg.crawlIncrementalByContentHash);
        assertTrue(cfg.crawlForceFullRecrawl);
    }

    @Test
    void crawlRuntimeConfig_toMap_containsIncrementalKeys() throws Exception {
        CrawlRuntimeConfigManager mgr = new CrawlRuntimeConfigManager(
                tempDir.resolve("no-such-config.json"));
        Map<String, Object> m = mgr.currentCrawlRuntimeConfig();
        assertTrue(m.containsKey("crawlIncrementalByContentHash"));
        assertTrue(m.containsKey("crawlForceFullRecrawl"));
    }
}
