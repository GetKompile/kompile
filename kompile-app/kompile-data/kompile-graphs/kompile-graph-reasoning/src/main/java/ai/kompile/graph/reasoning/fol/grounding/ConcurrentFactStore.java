/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.fol.grounding;

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe wrapper over {@link FactStore} with optimistic MVCC for concurrent agents.
 *
 * <p>This is the <em>P0-3 concurrent assert primitive</em> from the agent-grounding
 * infrastructure design. Multiple agents can safely assert facts from concurrent threads.
 * A monotonic global {@link #version()} counter tracks the write sequence; callers that
 * need optimistic conflict detection can use {@link #assertFact(Fact, long)} with a
 * {@code expectedVersion} — the assert is rejected (returns {@code false}) when the store
 * has been modified since the caller last read it.</p>
 *
 * <h3>MVCC semantics (optimistic locking)</h3>
 * <ol>
 *   <li>Call {@link #version()} to snapshot the current store version before reading.</li>
 *   <li>Read facts and compute the new fact to assert.</li>
 *   <li>Call {@link #assertFact(Fact, long)} with the saved version as
 *       {@code expectedVersion}.</li>
 *   <li>If the store version has advanced (another agent wrote first), the method returns
 *       {@code false} — a <em>write-write conflict</em> is detected and the caller decides
 *       whether to retry or abort.</li>
 *   <li>If no conflict, the fact is asserted and the version is bumped atomically.</li>
 * </ol>
 *
 * <h3>Consistent-snapshot reads</h3>
 * <p>{@link #snapshot()} returns a point-in-time unmodifiable copy of all current facts
 * tagged with the store version at read time, so callers can reason over a consistent
 * snapshot without locking.</p>
 *
 * <h3>Thread safety</h3>
 * <p>Uses {@link ConcurrentHashMap} for the fact store and {@link AtomicLong} for the
 * version counter. No new external dependencies are required.</p>
 */
public final class ConcurrentFactStore {

    /** A point-in-time snapshot of the fact store. */
    public record Snapshot(List<Fact> facts, long version) {
        public Snapshot {
            Objects.requireNonNull(facts, "facts must not be null");
            facts = List.copyOf(facts);
        }
    }

    /** Marker returned by {@link #assertFact(Fact, long)} on a write-write conflict. */
    public static final long CONFLICT = -1L;

    private final FactStore store = new FactStore();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private long versionCounter;

    // ─── Simple (unconditional) assert ──────────────────────────────────────────

    /**
     * Assert a fact unconditionally (last-write-wins).
     *
     * <p>The store version is bumped atomically. Safe to call from multiple threads.</p>
     *
     * @param fact the fact to assert (must not be null)
     * @return the new store version after this write
     */
    public long assertFact(Fact fact) {
        Objects.requireNonNull(fact, "fact must not be null");
        lock.writeLock().lock();
        try {
            store.assertFact(fact);
            return ++versionCounter;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ─── Optimistic (versioned) assert ──────────────────────────────────────────

    /**
     * Assert a fact using optimistic MVCC: succeeds only if the store has not been
     * modified since {@code expectedVersion}.
     *
     * <p>If the current version equals {@code expectedVersion}, the fact is asserted and
     * a new version (current + 1) is returned. If the current version differs (another
     * agent wrote concurrently), {@link #CONFLICT} is returned and the fact is NOT
     * asserted.</p>
     *
     * @param fact            the fact to assert
     * @param expectedVersion the version the caller observed before computing this fact
     * @return the new store version on success, or {@link #CONFLICT} on write-write conflict
     */
    public long assertFact(Fact fact, long expectedVersion) {
        Objects.requireNonNull(fact, "fact must not be null");
        lock.writeLock().lock();
        try {
            if (versionCounter != expectedVersion) return CONFLICT;
            store.assertFact(fact);
            return ++versionCounter;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ─── Reads ───────────────────────────────────────────────────────────────────

    /**
     * The current store version. Callers snapshot this before reading facts, then use it
     * in {@link #assertFact(Fact, long)} to detect concurrent writes.
     *
     * @return the current version counter value
     */
    public long version() {
        lock.readLock().lock();
        try {
            return versionCounter;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Advance the shared KB revision for a mutation applied through another observed-fact view. */
    public long markMutation() {
        lock.writeLock().lock();
        try {
            return ++versionCounter;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Look up a fact by atom key.
     *
     * @param atomKey the atom key
     * @return the fact, or empty if absent
     */
    public Optional<Fact> factFor(String atomKey) {
        lock.readLock().lock();
        try {
            return store.factFor(atomKey);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Return all facts as an unmodifiable view (consistent-enough-for-one-thread;
     * use {@link #snapshot()} for a fully consistent point-in-time copy).
     *
     * @return unmodifiable collection of all current facts
     */
    public Collection<Fact> allFacts() {
        lock.readLock().lock();
        try {
            return List.copyOf(store.allFacts());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Return a consistent point-in-time snapshot: all facts plus the version at read time.
     *
     * <p>Because {@link ConcurrentHashMap} provides weakly-consistent iteration, this
     * method takes the version AFTER collecting facts to bound the snapshot. Callers that
     * need the exact version-before-read should use {@link #version()} + {@link #allFacts()}
     * under external synchronization; {@link #snapshot()} is sufficient for the common
     * read-compute-write MVCC pattern.</p>
     *
     * @return an immutable {@link Snapshot} of the current state
     */
    public Snapshot snapshot() {
        lock.readLock().lock();
        try {
            return new Snapshot(new ArrayList<>(store.allFacts()), versionCounter);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Retract a fact by atom key.
     *
     * @param atomKey the atom key to retract
     * @return the removed fact, or empty if absent; bumps the version if a fact was removed
     */
    public Optional<Fact> retract(String atomKey) {
        lock.writeLock().lock();
        try {
            Optional<Fact> removed = store.retract(atomKey);
            if (removed.isPresent()) versionCounter++;
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Retract one provenance source from all atoms, preserving remaining sources. */
    public int retractBySource(String sourceId) {
        lock.writeLock().lock();
        try {
            int removed = store.retractBySource(sourceId);
            if (removed > 0) versionCounter++;
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Return the number of facts in the store.
     *
     * @return fact count
     */
    public int size() {
        lock.readLock().lock();
        try {
            return store.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Apply all facts in this concurrent store to a (non-concurrent) {@link FactStore}.
     *
     * <p>Useful for feeding observed facts into PSL inference.</p>
     *
     * @param target the target fact store
     */
    public void applyTo(FactStore target) {
        Objects.requireNonNull(target, "target must not be null");
        lock.readLock().lock();
        try {
            for (Fact fact : store.allSourceFacts()) target.assertFact(fact);
        } finally {
            lock.readLock().unlock();
        }
    }
}
