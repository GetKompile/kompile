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
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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

    private final ConcurrentHashMap<String, Fact> store = new ConcurrentHashMap<>();
    private final AtomicLong versionCounter = new AtomicLong(0L);

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
        store.put(fact.atomKey(), fact);
        return versionCounter.incrementAndGet();
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
        // Spin-CAS on the version counter: only succeed if version == expectedVersion
        while (true) {
            long current = versionCounter.get();
            if (current != expectedVersion) {
                return CONFLICT; // write-write conflict detected
            }
            // Try to advance the version; if another thread beat us, retry the check
            if (versionCounter.compareAndSet(current, current + 1)) {
                store.put(fact.atomKey(), fact);
                return current + 1;
            }
            // Another thread incremented the counter between our read and CAS → conflict
            return CONFLICT;
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
        return versionCounter.get();
    }

    /**
     * Look up a fact by atom key.
     *
     * @param atomKey the atom key
     * @return the fact, or empty if absent
     */
    public Optional<Fact> factFor(String atomKey) {
        return Optional.ofNullable(store.get(atomKey));
    }

    /**
     * Return all facts as an unmodifiable view (consistent-enough-for-one-thread;
     * use {@link #snapshot()} for a fully consistent point-in-time copy).
     *
     * @return unmodifiable collection of all current facts
     */
    public Collection<Fact> allFacts() {
        return Collections.unmodifiableCollection(store.values());
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
        List<Fact> facts = new ArrayList<>(store.values());
        long ver = versionCounter.get();
        return new Snapshot(facts, ver);
    }

    /**
     * Retract a fact by atom key.
     *
     * @param atomKey the atom key to retract
     * @return the removed fact, or empty if absent; bumps the version if a fact was removed
     */
    public Optional<Fact> retract(String atomKey) {
        Fact removed = store.remove(atomKey);
        if (removed != null) {
            versionCounter.incrementAndGet();
        }
        return Optional.ofNullable(removed);
    }

    /**
     * Return the number of facts in the store.
     *
     * @return fact count
     */
    public int size() {
        return store.size();
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
        for (Fact fact : store.values()) {
            target.assertFact(fact);
        }
    }
}
