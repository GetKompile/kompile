/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.fol;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * SPI for persisting and querying {@link InferredFact} objects.
 *
 * <p>An implementation may be backed by an in-memory map ({@link InMemoryInferredFactStore}),
 * a graph-node metadata JSON store, or any other durable backend. The interface is
 * intentionally simple so that it can be implemented without infrastructure dependencies.</p>
 *
 * <h3>Version semantics</h3>
 * <p>Each {@link InferredFact} carries a {@link InferredFact#version()} counter. Implementations
 * MUST ensure that {@link #store(InferredFact)} assigns a version that is strictly greater than
 * any existing version for the same {@link InferredFact#atomKey()}, and that {@link #latest(String)}
 * returns the highest-versioned fact for a given key.</p>
 */
public interface InferredFactStore {

    /**
     * Persist an inferred fact.
     *
     * <p>If the store already contains a fact for {@link InferredFact#atomKey()}, the new fact
     * MUST have a strictly higher version; the old version is retained in history.</p>
     *
     * @param fact the inferred fact to store; must not be null
     */
    void store(InferredFact fact);

    /**
     * Persist a batch of inferred facts in a single operation.
     *
     * <p>The default implementation calls {@link #store(InferredFact)} for each fact;
     * implementations that support bulk writes (e.g. JPA {@code saveAll}) should override
     * this to materialize all facts in a single transaction, which is dramatically faster
     * for large batches (tens of thousands of atoms).</p>
     *
     * <p>When the interrupt flag is set on the calling thread, implementations MUST
     * clear it before initiating any I/O and re-set it on exit so the caller's
     * interrupt-handling is preserved without breaking the write.</p>
     *
     * @param facts the facts to persist; must not be null; may be empty (no-op)
     */
    default void storeAll(Collection<InferredFact> facts) {
        // Clear interrupt before bulk write so NIO channels are not closed mid-batch;
        // re-interrupt the thread after the write completes so callers can observe it.
        boolean interrupted = Thread.interrupted();
        try {
            for (InferredFact fact : facts) {
                store(fact);
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Retrieve the latest (highest-versioned) inferred fact for the given atom key.
     *
     * @param atomKey the canonical atom key
     * @return the latest inferred fact, or empty if not present
     */
    Optional<InferredFact> latest(String atomKey);

    /**
     * Retrieve all versions of the inferred fact for the given atom key, in ascending
     * version order.
     *
     * @param atomKey the canonical atom key
     * @return all historical versions, oldest first; empty if not present
     */
    List<InferredFact> history(String atomKey);

    /**
     * Retrieve all inferred facts produced in the given inference run.
     *
     * @param runId the inference run identifier
     * @return all facts from that run (order unspecified); empty if none
     */
    Collection<InferredFact> byRun(String runId);

    /**
     * Return all latest (highest-versioned) inferred facts across all atom keys.
     *
     * @return all latest inferred facts; empty if the store is empty
     */
    Collection<InferredFact> allLatest();

    /**
     * Remove all versions of the inferred fact for the given atom key from the store.
     *
     * @param atomKey the atom key to purge
     */
    void purge(String atomKey);

    /**
     * Return the number of distinct atom keys in the store (counting only one entry per key,
     * regardless of version count).
     *
     * @return number of distinct atom keys
     */
    int size();

    /**
     * Return true if the store contains no inferred facts.
     *
     * @return true if empty
     */
    boolean isEmpty();
}
