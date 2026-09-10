/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.knowledgegraph.generation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Cross-process durable state for one replacement generation per logical graph.
 * Implementations must serialize {@link #update} across JVMs and make each update durable before
 * returning. The journal pointer, not the vector-store mirror, is the production CAS authority.
 */
public interface GraphGenerationJournal {

    int SCHEMA_VERSION = 1;

    enum State {
        BUILDING,
        SEALING,
        VALIDATED,
        ACTIVE,
        ABORTED
    }

    record Entry(
            int schemaVersion,
            GraphGeneration.Ref generation,
            GraphGeneration.Pointer pointer,
            State state,
            String ownerJobId,
            int activeWriters,
            GraphGeneration.Validation validation,
            GraphGeneration.Activation activation,
            Instant createdAt,
            Instant updatedAt,
            Instant leaseExpiresAt,
            String lastError,
            String lastOperationId) {
        public Entry {
            if (schemaVersion != SCHEMA_VERSION) {
                throw new IllegalArgumentException("Unsupported graph generation journal schema: " + schemaVersion);
            }
            if (generation == null) throw new IllegalArgumentException("generation is required");
            if (pointer == null) throw new IllegalArgumentException("pointer is required");
            if (state == null) throw new IllegalArgumentException("state is required");
            if (activeWriters < 0) throw new IllegalArgumentException("activeWriters must be non-negative");
            if (createdAt == null || updatedAt == null || leaseExpiresAt == null) {
                throw new IllegalArgumentException("journal timestamps are required");
            }
            if (generation.factSheetId() != pointer.factSheetId()
                    || !generation.logicalGraphId().equals(pointer.logicalGraphId())) {
                throw new IllegalArgumentException("generation and pointer scopes do not match");
            }
        }

        public boolean owns(GraphGeneration.Ref ref) {
            return ref != null
                    && generation.factSheetId() == ref.factSheetId()
                    && generation.logicalGraphId().equals(ref.logicalGraphId())
                    && generation.generationId().equals(ref.generationId())
                    && generation.physicalGraphId().equals(ref.physicalGraphId());
        }
    }

    Optional<Entry> get(String logicalGraphId);

    List<Entry> list();

    /**
     * Atomically read-modify-write one logical graph entry. The callback executes while the
     * implementation's cross-process lock is held and must not return {@code null}.
     */
    Entry update(String logicalGraphId,
                 Function<Optional<Entry>, Entry> updateFunction);

    default Optional<GraphGeneration.Pointer> pointer(String logicalGraphId) {
        return get(logicalGraphId).map(Entry::pointer);
    }
}
