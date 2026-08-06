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

package ai.kompile.app.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link StagingServingBridge#findCompleteShardedEntry(Path)} — the sharded-artifact
 * cache detection added after the 2026-07-05 incident where 5.2GB of valid model shards were
 * ignored while the bridge re-downloaded a nonexistent single-file artifact every poll.
 */
class StagingServingBridgeShardCacheTest {

    @TempDir
    Path dir;

    private Path shard(String name, int bytes) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, new byte[bytes]);
        return p;
    }

    @Test
    void completeShardSetWithManifest_returnsManifest() throws IOException {
        shard("model.shard0-of-3.sdnb", 10);
        shard("model.shard1-of-3.sdnb", 10);
        shard("model.shard2-of-3.sdnb", 10);
        Path manifest = shard("model.sdnb", 5);

        assertEquals(manifest, StagingServingBridge.findCompleteShardedEntry(dir));
    }

    @Test
    void completeShardSetWithEmptyManifest_returnsCanonicalBase() throws IOException {
        // The live failure mode: a botched download truncated the manifest to 0 bytes while
        // all shards remained valid. SameDiff must still enter through the canonical base name
        // so its serializer discovers every sibling shard instead of treating shard 0 as standalone.
        shard("model.shard0-of-2.sdnb", 10);
        shard("model.shard1-of-2.sdnb", 10);
        shard("model.sdnb", 0);

        assertEquals(dir.resolve("model.sdnb"), StagingServingBridge.findCompleteShardedEntry(dir));
    }

    @Test
    void incompleteShardSet_returnsNull() throws IOException {
        shard("model.shard0-of-3.sdnb", 10);
        shard("model.shard1-of-3.sdnb", 10);
        // shard2 missing

        assertNull(StagingServingBridge.findCompleteShardedEntry(dir));
    }

    @Test
    void zeroByteShard_disqualifiesSet() throws IOException {
        shard("model.shard0-of-2.sdnb", 10);
        shard("model.shard1-of-2.sdnb", 0);

        assertNull(StagingServingBridge.findCompleteShardedEntry(dir));
    }

    @Test
    void emptyOrMissingDir_returnsNull() {
        assertNull(StagingServingBridge.findCompleteShardedEntry(dir));
        assertNull(StagingServingBridge.findCompleteShardedEntry(dir.resolve("nope")));
        assertNull(StagingServingBridge.findCompleteShardedEntry(null));
    }
}
