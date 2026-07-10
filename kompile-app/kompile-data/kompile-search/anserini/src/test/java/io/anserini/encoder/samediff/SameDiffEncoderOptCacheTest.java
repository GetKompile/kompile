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

package io.anserini.encoder.samediff;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * Unit tests for the graph-optimization-cache helpers in {@link SameDiffEncoder}.
 *
 * <p>These tests cover only the cache path/fingerprint logic and require
 * <em>no</em> ND4J native libraries — they operate entirely on temp files.
 *
 * <p>Background: {@code SameDiffEncoder.loadSameDiffModel()} calls
 * {@code GraphOptimizer.optimize()} after loading a model from its {@code .sdz}
 * source file.  On every embedding-subprocess restart that optimization runs again
 * (several seconds for a large transformer).  The opt-cache lets subsequent starts
 * skip that cost by loading the already-fused graph from a {@code model.opt.sdz}
      * file, validated via a {@code .fp} fingerprint containing source file identity and
      * optimizer/backend runtime settings.
 */
public class SameDiffEncoderOptCacheTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Before
    public void setBackendFingerprintOverride() {
        System.setProperty("kompile.embedding.samediff.cacheBackend", "test-backend");
    }

    @After
    public void clearBackendFingerprintOverride() {
        System.clearProperty("kompile.embedding.samediff.cacheBackend");
        System.clearProperty("nd4j.optimizer.fp16");
    }

    // ------------------------------------------------------------------
    // computeOptCachePath
    // ------------------------------------------------------------------

    @Test
    public void computeOptCachePath_sdzExtension_insertsOpt() {
        Path source = Path.of("/some/dir/model.sdz");
        Path cache  = SameDiffEncoder.computeOptCachePath(source);
        assertEquals("model.opt.sdz", cache.getFileName().toString());
        assertEquals(source.getParent(), cache.getParent());
    }

    @Test
    public void computeOptCachePath_otherExtension_insertsOpt() {
        Path source = Path.of("/some/dir/weights.bin");
        Path cache  = SameDiffEncoder.computeOptCachePath(source);
        assertEquals("weights.opt.bin", cache.getFileName().toString());
    }

    @Test
    public void computeOptCachePath_noExtension_appendsOpt() {
        Path source = Path.of("/some/dir/modelfile");
        Path cache  = SameDiffEncoder.computeOptCachePath(source);
        assertEquals("modelfile.opt", cache.getFileName().toString());
    }

    @Test
    public void computeOptCachePath_preservesParentDirectory() {
        Path source = Path.of("/root/models/encoders/bge-base-en-v1.5/model.sdz");
        Path cache  = SameDiffEncoder.computeOptCachePath(source);
        assertEquals(source.getParent(), cache.getParent());
        assertEquals("model.opt.sdz", cache.getFileName().toString());
    }

    // ------------------------------------------------------------------
    // computeSourceFingerprint
    // ------------------------------------------------------------------

    @Test
    public void computeSourceFingerprint_containsSizeMtimeAndRuntimeSettings() throws IOException {
        File f = tmp.newFile("source.sdz");
        Files.writeString(f.toPath(), "dummy content 12345");
        String fp = SameDiffEncoder.computeSourceFingerprint(f.toPath());
        String[] parts = fp.split(",", 3);
        assertEquals("fingerprint has source size, source mtime, and runtime settings", 3, parts.length);
        assertEquals("first part is file size",
                Files.size(f.toPath()), Long.parseLong(parts[0]));
        assertTrue("second part is a positive millis timestamp",
                Long.parseLong(parts[1]) > 0);
        assertTrue("runtime settings include backend identity", parts[2].contains("backend=test-backend"));
        assertTrue("runtime settings include fp16 optimizer setting", parts[2].contains("optimizer.fp16="));
    }

    @Test
    public void computeSourceFingerprint_changesWhenContentChanges() throws IOException {
        File f = tmp.newFile("source.sdz");
        Files.writeString(f.toPath(), "version1");
        String fp1 = SameDiffEncoder.computeSourceFingerprint(f.toPath());

        Files.writeString(f.toPath(), "version2_much_longer");
        String fp2 = SameDiffEncoder.computeSourceFingerprint(f.toPath());

        assertNotEquals("fingerprint must differ when file size changes", fp1, fp2);
    }

    @Test
    public void computeSourceFingerprint_changesWhenRuntimeSettingsChange() throws IOException {
        File f = tmp.newFile("source.sdz");
        Files.writeString(f.toPath(), "model bytes");
        System.setProperty("nd4j.optimizer.fp16", "true");
        String fp16 = SameDiffEncoder.computeSourceFingerprint(f.toPath());

        System.setProperty("nd4j.optimizer.fp16", "false");
        String fp32 = SameDiffEncoder.computeSourceFingerprint(f.toPath());

        assertNotEquals("fingerprint must differ when optimizer fp16 setting changes", fp16, fp32);
    }

    // ------------------------------------------------------------------
    // isOptCacheValid — cache absent
    // ------------------------------------------------------------------

    @Test
    public void isOptCacheValid_noCacheFile_returnsFalse() throws IOException {
        File source = tmp.newFile("model.sdz");
        Files.writeString(source.toPath(), "model bytes");
        Path cache = SameDiffEncoder.computeOptCachePath(source.toPath());
        // Neither cache file nor .fp file exists
        assertFalse("No cache file → must return false",
                SameDiffEncoder.isOptCacheValid(source.toPath(), cache));
    }

    @Test
    public void isOptCacheValid_cacheExistsButNoFingerprintFile_returnsFalse() throws IOException {
        File source = tmp.newFile("model.sdz");
        Files.writeString(source.toPath(), "model bytes");
        Path cache = SameDiffEncoder.computeOptCachePath(source.toPath());
        // Create cache file but omit .fp fingerprint file
        Files.writeString(cache, "optimized bytes");
        assertFalse("Cache without .fp → must return false",
                SameDiffEncoder.isOptCacheValid(source.toPath(), cache));
    }

    // ------------------------------------------------------------------
    // isOptCacheValid — fingerprint mismatch
    // ------------------------------------------------------------------

    @Test
    public void isOptCacheValid_fingerprintMismatch_returnsFalse() throws IOException {
        File source = tmp.newFile("model.sdz");
        Files.writeString(source.toPath(), "model bytes");
        Path cache  = SameDiffEncoder.computeOptCachePath(source.toPath());
        Files.writeString(cache, "optimized bytes");
        Path fpPath = cache.resolveSibling(cache.getFileName() + ".fp");
        Files.writeString(fpPath, "9999,1234567890,backend=wrong");  // wrong source/runtime fingerprint
        assertFalse("Mismatched fingerprint → must return false",
                SameDiffEncoder.isOptCacheValid(source.toPath(), cache));
    }

    @Test
    public void isOptCacheValid_malformedFingerprintFile_returnsFalse() throws IOException {
        File source = tmp.newFile("model.sdz");
        Files.writeString(source.toPath(), "model bytes");
        Path cache  = SameDiffEncoder.computeOptCachePath(source.toPath());
        Files.writeString(cache, "optimized bytes");
        Path fpPath = cache.resolveSibling(cache.getFileName() + ".fp");
        Files.writeString(fpPath, "not-a-valid-fingerprint");
        assertFalse("Malformed .fp → must return false",
                SameDiffEncoder.isOptCacheValid(source.toPath(), cache));
    }

    // ------------------------------------------------------------------
    // isOptCacheValid — cache valid
    // ------------------------------------------------------------------

    @Test
    public void isOptCacheValid_validCache_returnsTrue() throws IOException {
        File source = tmp.newFile("model.sdz");
        Files.writeString(source.toPath(), "model bytes");
        Path cache  = SameDiffEncoder.computeOptCachePath(source.toPath());
        Files.writeString(cache, "optimized bytes");
        // Write the correct fingerprint
        String fp = SameDiffEncoder.computeSourceFingerprint(source.toPath());
        Path fpPath = cache.resolveSibling(cache.getFileName() + ".fp");
        Files.writeString(fpPath, fp);
        assertTrue("Matching fingerprint → must return true",
                SameDiffEncoder.isOptCacheValid(source.toPath(), cache));
    }

    @Test
    public void isOptCacheValid_fingerprintWithTrailingWhitespace_stillValid() throws IOException {
        File source = tmp.newFile("model.sdz");
        Files.writeString(source.toPath(), "model bytes");
        Path cache  = SameDiffEncoder.computeOptCachePath(source.toPath());
        Files.writeString(cache, "optimized bytes");
        String fp = SameDiffEncoder.computeSourceFingerprint(source.toPath());
        Path fpPath = cache.resolveSibling(cache.getFileName() + ".fp");
        // Write fingerprint with trailing newline (as Files.writeString does on some systems)
        Files.writeString(fpPath, fp + "\n");
        assertTrue("Fingerprint with trailing whitespace should still be valid",
                SameDiffEncoder.isOptCacheValid(source.toPath(), cache));
    }

    // ------------------------------------------------------------------
    // isOptCacheValid — source changed after caching
    // ------------------------------------------------------------------

    @Test
    public void isOptCacheValid_sourceChangedAfterCaching_returnsFalse() throws IOException {
        File source = tmp.newFile("model.sdz");
        Files.writeString(source.toPath(), "model bytes");
        Path cache  = SameDiffEncoder.computeOptCachePath(source.toPath());
        Files.writeString(cache, "optimized bytes");
        // Write fingerprint based on original source
        String fp = SameDiffEncoder.computeSourceFingerprint(source.toPath());
        Path fpPath = cache.resolveSibling(cache.getFileName() + ".fp");
        Files.writeString(fpPath, fp);
        assertTrue("Setup: cache should be valid initially",
                SameDiffEncoder.isOptCacheValid(source.toPath(), cache));

        // Simulate model re-download (different file size)
        Files.writeString(source.toPath(), "new model bytes with different length");
        assertFalse("Cache must be invalid after source file changes",
                SameDiffEncoder.isOptCacheValid(source.toPath(), cache));
    }
}
