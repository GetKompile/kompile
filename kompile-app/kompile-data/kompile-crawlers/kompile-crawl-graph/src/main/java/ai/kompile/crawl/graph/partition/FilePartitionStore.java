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

package ai.kompile.crawl.graph.partition;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.graphrag.partition.EntityPartition;
import ai.kompile.core.graphrag.partition.PartitionMember;
import ai.kompile.core.graphrag.partition.PartitionStore;
import ai.kompile.utils.HashUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Partitions on disk, one JSON file each, under the project's graph data directory.
 *
 * <p>{@link PartitionStore} says durability is the whole point of the abstraction, and until this
 * class existed the only implementation was the in-memory one — so every coverage claim died with
 * the JVM that made it. A partition that does not outlive its run cannot answer "what changed since
 * last time?", cannot be re-opened when a source moves, and cannot support a claim anybody is
 * entitled to rely on later. This is the implementation that makes those sentences true.</p>
 *
 * <h3>Layout</h3>
 * <p>One file per partition, named for the SHA-256 of the partition id rather than the id itself:
 * {@link ai.kompile.core.graphrag.partition.PartitionKey#id()} is deliberately human-legible
 * ({@code e=acme|p=v1|s=42}) and contains characters no filesystem should be asked to carry. The id
 * is stored inside the document, so the directory is still self-describing to anyone who opens a
 * file; only the name is a digest.</p>
 *
 * <h3>Failure behaviour</h3>
 * <p>A file that cannot be read is treated as never written: the partition re-opens empty,
 * rediscovers, and pays for the work a second time. That is the safe direction — forgetting a claim
 * costs work, whereas half-reading one would produce a claim nobody made. A file that cannot be
 * <em>written</em> throws, because a claim that was not recorded must not be reported as recorded;
 * {@code EntityPartitionCrawlService.runAllStaged} already treats a store failure as that subject
 * failing, which is the honest outcome.</p>
 *
 * <p>Writes go through a temp file and an atomic move, so a crash never leaves a torn partition
 * behind. Concurrency has the same shape as the in-memory store: each save is atomic for its own
 * partition and the last writer wins, so callers that read-modify-write a single partition must
 * serialise that themselves — which a crawl does anyway, since one partition is one run.</p>
 */
@Slf4j
@Component
public class FilePartitionStore implements PartitionStore {

    /** Where partitions live under the resolved project directory. */
    public static final String PARTITIONS_SUBDIR = "data/graph/partitions";

    private static final String SUFFIX = ".json";
    private static final String TEMP_PREFIX = ".partition-";

    private final ObjectMapper mapper = JsonUtils.standardMapper();

    /** Explicit root, or {@code null} to resolve the project directory on every call. */
    private final Path fixedRoot;

    /** Store rooted at the current project — the bean the crawl gets. */
    public FilePartitionStore() {
        this(null);
    }

    /**
     * Store rooted at an explicit directory, for callers that own their own workspace and for
     * tests. Nothing is created until the first save.
     */
    public static FilePartitionStore at(Path root) {
        return new FilePartitionStore(root);
    }

    private FilePartitionStore(Path root) {
        this.fixedRoot = root;
    }

    @Override
    public Optional<EntityPartition> load(String partitionId) {
        if (partitionId == null || partitionId.isBlank()) {
            return Optional.empty();
        }
        Path file = fileFor(partitionId);
        return Files.isRegularFile(file) ? read(file) : Optional.empty();
    }

    @Override
    public void save(EntityPartition partition) {
        if (partition == null) {
            return;
        }
        Path file = fileFor(partition.id());
        try {
            writeAtomic(file, partition);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not record partition " + partition.id() + " at " + file, e);
        }
    }

    @Override
    public void delete(String partitionId) {
        if (partitionId == null || partitionId.isBlank()) {
            return;
        }
        Path file = fileFor(partitionId);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not delete partition " + partitionId + " at " + file, e);
        }
    }

    @Override
    public List<EntityPartition> findByPolicy(String policyVersion) {
        List<EntityPartition> matched = new ArrayList<>();
        for (EntityPartition partition : findAll()) {
            // Null matches null, exactly as the in-memory store does: "recorded under no policy"
            // is a real answer, not a wildcard.
            boolean hit = policyVersion == null
                    ? partition.key().policyVersion() == null
                    : policyVersion.equals(partition.key().policyVersion());
            if (hit) {
                matched.add(partition);
            }
        }
        return matched;
    }

    @Override
    public List<EntityPartition> findByDocument(String documentId) {
        List<EntityPartition> matched = new ArrayList<>();
        if (documentId == null || documentId.isBlank()) {
            return matched;
        }
        for (EntityPartition partition : findAll()) {
            for (PartitionMember member : partition.memberList()) {
                if (documentId.equals(member.documentId())) {
                    matched.add(partition);
                    break;
                }
            }
        }
        return matched;
    }

    /** Every partition currently on disk, in no particular order. Unreadable files are skipped. */
    @Override
    public List<EntityPartition> findAll() {
        Path dir = directory();
        List<EntityPartition> loaded = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return loaded;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(file -> file.getFileName().toString().endsWith(SUFFIX))
                    .filter(Files::isRegularFile)
                    .forEach(file -> read(file).ifPresent(loaded::add));
        } catch (IOException e) {
            log.warn("Could not list partitions in {}: {}", dir, e.toString());
        }
        return loaded;
    }

    /** How many partitions are on disk, counting only the ones that still read back. */
    public int size() {
        return findAll().size();
    }

    /** The directory partitions are written to; created on the first save, not before. */
    public Path directory() {
        return fixedRoot != null
                ? fixedRoot
                : KompileHome.resolvedProjectDirectory().toPath().resolve(PARTITIONS_SUBDIR);
    }

    private Path fileFor(String partitionId) {
        return directory().resolve(HashUtils.sha256Hex(partitionId) + SUFFIX);
    }

    private Optional<EntityPartition> read(Path file) {
        try {
            return Optional.ofNullable(mapper.readValue(file.toFile(), EntityPartition.class));
        } catch (Exception e) {
            log.warn("Could not read partition at {} ({}) — treating it as never recorded",
                    file, e.toString());
            return Optional.empty();
        }
    }

    private void writeAtomic(Path target, EntityPartition partition) throws IOException {
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("partition path has no directory: " + target);
        }
        Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, TEMP_PREFIX, ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), partition);
            Files.move(tmp, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }
}
