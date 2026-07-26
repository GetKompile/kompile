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

package ai.kompile.core.graphrag.partition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where partitions live between runs.
 *
 * <p>Durability is the whole point of the abstraction: a partition that vanishes with the process
 * cannot answer "what changed since last time?", cannot be re-opened when a source moves, and
 * cannot support a coverage claim that outlives the run that made it.</p>
 */
public interface PartitionStore {

    Optional<EntityPartition> load(String partitionId);

    void save(EntityPartition partition);

    void delete(String partitionId);

    /** Every partition recorded under a given discovery policy version. */
    List<EntityPartition> findByPolicy(String policyVersion);

    /**
     * Every partition holding a member drawn from {@code documentId}. This is the reverse index
     * selective invalidation needs: a changed source finds its partitions without a scan of the
     * whole corpus.
     */
    List<EntityPartition> findByDocument(String documentId);

    /**
     * Every partition recorded, in no particular order.
     *
     * <p>A durable claim nobody can enumerate is a claim nobody can read. Neither the fact sheet a
     * partition was discovered against nor any other pin is part of {@link PartitionKey}, so the
     * only way to answer "what has been covered here" is to look at what is there.</p>
     */
    List<EntityPartition> findAll();

    /**
     * Every partition carrying {@code value} under the pin {@code name}.
     *
     * <p>Pins are how a partition records the scope it was discovered under — the fact sheet, the
     * schema, the model — none of which are part of its identity. This is the lookup that scope
     * needs, and it is generic on purpose: the store has no business knowing which pins exist.</p>
     */
    default List<EntityPartition> findByPin(String name, String value) {
        List<EntityPartition> matched = new ArrayList<>();
        if (name == null || name.isBlank() || value == null) {
            return matched;
        }
        for (EntityPartition partition : findAll()) {
            if (value.equals(partition.pins().get(name))) {
                matched.add(partition);
            }
        }
        return matched;
    }

    default EntityPartition loadOrOpen(PartitionKey key) {
        if (key == null) {
            throw new IllegalArgumentException("cannot open a partition without a key");
        }
        return load(key.id()).orElseGet(() -> EntityPartition.open(key));
    }

    /** In-memory store; the default for tests, previews and single-run crawls. */
    static PartitionStore inMemory() {
        return new InMemoryPartitionStore();
    }

    /**
     * Thread-safe in-memory implementation. Concurrent crawl workers commit batches against the
     * same partition, so the map must tolerate that even though each stored value is immutable.
     */
    final class InMemoryPartitionStore implements PartitionStore {

        private final Map<String, EntityPartition> partitions = new ConcurrentHashMap<>();

        @Override
        public Optional<EntityPartition> load(String partitionId) {
            return partitionId == null ? Optional.empty()
                    : Optional.ofNullable(partitions.get(partitionId));
        }

        @Override
        public void save(EntityPartition partition) {
            if (partition != null) {
                partitions.put(partition.id(), partition);
            }
        }

        @Override
        public void delete(String partitionId) {
            if (partitionId != null) {
                partitions.remove(partitionId);
            }
        }

        @Override
        public List<EntityPartition> findByPolicy(String policyVersion) {
            List<EntityPartition> matched = new ArrayList<>();
            for (EntityPartition partition : partitions.values()) {
                if (policyVersion == null
                        ? partition.key().policyVersion() == null
                        : policyVersion.equals(partition.key().policyVersion())) {
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
            for (EntityPartition partition : partitions.values()) {
                for (PartitionMember member : partition.memberList()) {
                    if (documentId.equals(member.documentId())) {
                        matched.add(partition);
                        break;
                    }
                }
            }
            return matched;
        }

        @Override
        public List<EntityPartition> findAll() {
            return new ArrayList<>(partitions.values());
        }

        public int size() {
            return partitions.size();
        }

        public void clear() {
            partitions.clear();
        }
    }
}
