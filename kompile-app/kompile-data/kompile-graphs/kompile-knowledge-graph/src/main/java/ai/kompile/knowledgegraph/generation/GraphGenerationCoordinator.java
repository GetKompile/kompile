/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.knowledgegraph.generation;

import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single authoritative production writer for graph-generation lifecycle state.
 * Cross-JVM serialization and CAS are supplied by {@link GraphGenerationJournal#update}; the
 * vector-store pointer remains a compatibility mirror only.
 */
@Service
@ConditionalOnProperty(name = "kompile.graph.generations.subprocess-authority", havingValue = "true")
public class GraphGenerationCoordinator {

    private final MatrixGraphStore graphStore;
    private final GraphGenerationJournal journal;
    private final Clock clock;
    private final Duration leaseDuration;

    @Autowired
    public GraphGenerationCoordinator(
            MatrixGraphStore graphStore,
            GraphGenerationJournal journal,
            @Value("${kompile.graph.generations.lease-seconds:1800}") long leaseSeconds) {
        this(graphStore, journal, Clock.systemUTC(), Duration.ofSeconds(Math.max(30L, leaseSeconds)));
    }

    /** Test/embedded constructor. */
    public GraphGenerationCoordinator(MatrixGraphStore graphStore,
                                      GraphGenerationJournal journal,
                                      Clock clock,
                                      Duration leaseDuration) {
        this.graphStore = Objects.requireNonNull(graphStore, "graphStore");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
    }

    public GraphGeneration.Ref begin(long factSheetId, String logicalGraphId,
                                     String generationId, String ownerJobId) {
        expireIfNeeded(logicalGraphId);
        GraphGeneration.Pointer initialPointer = graphStore
                .currentGenerationPointer(factSheetId, logicalGraphId)
                .orElseGet(() -> new GraphGeneration.Pointer(
                        factSheetId, logicalGraphId, logicalGraphId, null, 0L));
        AtomicBoolean newGeneration = new AtomicBoolean();
        GraphGenerationJournal.Entry entry = journal.update(logicalGraphId, current -> {
            Instant now = clock.instant();
            if (current.isPresent()) {
                GraphGenerationJournal.Entry existing = current.get();
                assertScope(existing, factSheetId, logicalGraphId);
                if (existing.generation().generationId().equals(generationId)) {
                    if (existing.state() == GraphGenerationJournal.State.BUILDING) {
                        return renew(existing, ownerJobId, now);
                    }
                    throw new IllegalStateException(
                            "Generation ID already reached non-writable state " + existing.state());
                }
                if (existing.state() == GraphGenerationJournal.State.BUILDING
                        || existing.state() == GraphGenerationJournal.State.SEALING
                        || existing.state() == GraphGenerationJournal.State.VALIDATED) {
                    throw new IllegalStateException("Another graph generation is already in progress");
                }
            }
            GraphGeneration.Pointer basePointer = current
                    .map(GraphGenerationJournal.Entry::pointer)
                    .orElse(initialPointer);
            GraphGeneration.Ref ref = new GraphGeneration.Ref(
                    factSheetId, logicalGraphId, logicalGraphId + "~gen~" + generationId,
                    generationId, basePointer.activePhysicalGraphId(), basePointer.revision());
            newGeneration.set(true);
            return new GraphGenerationJournal.Entry(
                    GraphGenerationJournal.SCHEMA_VERSION, ref, basePointer,
                    GraphGenerationJournal.State.BUILDING, normalizeOwner(ownerJobId),
                    0, null, null, now, now, now.plus(leaseDuration), null, null);
        });

        try {
            if (newGeneration.get()
                    || !graphStore.physicalGraphExists(entry.generation().physicalGraphId(), factSheetId)) {
                graphStore.createGraph(entry.generation().physicalGraphId(), factSheetId);
            }
            return entry.generation();
        } catch (RuntimeException failure) {
            markAborted(entry.generation(), failure);
            journal.get(entry.generation().logicalGraphId()).ifPresent(this::cleanupAborted);
            throw failure;
        }
    }

    public GraphGeneration.Validation validate(GraphGeneration.Ref generation) {
        requireOwned(generation);
        GraphGenerationJournal.Entry before = journal.get(generation.logicalGraphId()).orElseThrow();
        if ((before.state() == GraphGenerationJournal.State.VALIDATED
                || before.state() == GraphGenerationJournal.State.ACTIVE)
                && before.validation() != null) {
            return before.validation();
        }
        journal.update(generation.logicalGraphId(), current -> {
            GraphGenerationJournal.Entry existing = requireCurrent(current, generation);
            if (existing.state() == GraphGenerationJournal.State.ACTIVE) return existing;
            if (existing.state() != GraphGenerationJournal.State.BUILDING
                    && existing.state() != GraphGenerationJournal.State.SEALING) {
                throw new IllegalStateException("Graph generation cannot enter sealing from " + existing.state());
            }
            return copy(existing, GraphGenerationJournal.State.SEALING, existing.validation(),
                    existing.activation(), clock.instant(), null, existing.lastOperationId());
        });
        awaitNoActiveWriters(generation.logicalGraphId());
        GraphGeneration.Validation validation = graphStore.validateGeneration(generation);
        journal.update(generation.logicalGraphId(), current -> {
            GraphGenerationJournal.Entry existing = requireCurrent(current, generation);
            if (existing.state() == GraphGenerationJournal.State.ACTIVE) return existing;
            if (existing.state() != GraphGenerationJournal.State.SEALING
                    || existing.activeWriters() != 0) {
                throw new IllegalStateException("Graph generation sealing was not quiescent");
            }
            Instant now = clock.instant();
            GraphGenerationJournal.State state = validation.valid()
                    ? GraphGenerationJournal.State.VALIDATED
                    : GraphGenerationJournal.State.BUILDING;
            return copy(existing, state, validation, existing.activation(), now,
                    validation.valid() ? null : String.join("; ", validation.errors()),
                    existing.lastOperationId());
        });
        return validation;
    }

    public GraphGeneration.Activation activate(GraphGeneration.Ref generation, String operationId) {
        requireOwned(generation);
        String operation = normalizeOperation(operationId);
        GraphGenerationJournal.Entry before = journal.get(generation.logicalGraphId()).orElseThrow();
        if (before.state() == GraphGenerationJournal.State.ACTIVE
                && generation.physicalGraphId().equals(before.pointer().activePhysicalGraphId())
                && before.activation() != null) {
            return before.activation();
        }
        graphStore.flushGeneration(generation);
        AtomicBoolean transitioned = new AtomicBoolean();
        GraphGenerationJournal.Entry committed = journal.update(generation.logicalGraphId(), current -> {
            GraphGenerationJournal.Entry existing = requireCurrent(current, generation);
            if (existing.state() == GraphGenerationJournal.State.ACTIVE
                    && existing.pointer().activePhysicalGraphId().equals(generation.physicalGraphId())) {
                return existing;
            }
            if (existing.state() != GraphGenerationJournal.State.VALIDATED
                    || existing.activeWriters() != 0
                    || existing.validation() == null || !existing.validation().valid()) {
                throw new IllegalStateException("Graph generation must be validated before activation");
            }
            GraphGeneration.Pointer pointer = existing.pointer();
            if (!pointer.activePhysicalGraphId().equals(generation.expectedActivePhysicalGraphId())
                    || pointer.revision() != generation.expectedRevision()) {
                throw new IllegalStateException("Graph generation activation conflict");
            }
            Instant now = clock.instant();
            GraphGeneration.Pointer nextPointer = new GraphGeneration.Pointer(
                    generation.factSheetId(), generation.logicalGraphId(), generation.physicalGraphId(),
                    pointer.activePhysicalGraphId(), pointer.revision() + 1L);
            GraphGeneration.Activation activation = new GraphGeneration.Activation(
                    generation.logicalGraphId(), generation.physicalGraphId(),
                    pointer.activePhysicalGraphId(), nextPointer.revision(), now);
            transitioned.set(true);
            return new GraphGenerationJournal.Entry(
                    GraphGenerationJournal.SCHEMA_VERSION, generation, nextPointer,
                    GraphGenerationJournal.State.ACTIVE, existing.ownerJobId(), 0, existing.validation(),
                    activation, existing.createdAt(), now, now.plus(leaseDuration), null,
                    operation);
        });

        if (transitioned.get()) {
            try {
                graphStore.activateGeneration(generation);
            } catch (RuntimeException mirrorFailure) {
                recordMirrorFailure(generation.logicalGraphId(), mirrorFailure);
            }
        }
        return committed.activation();
    }

    public void abort(GraphGeneration.Ref generation, Throwable failure) {
        requireOwned(generation);
        journal.update(generation.logicalGraphId(), current -> {
            GraphGenerationJournal.Entry existing = requireCurrent(current, generation);
            if (existing.state() == GraphGenerationJournal.State.ABORTED) return existing;
            if (existing.state() == GraphGenerationJournal.State.ACTIVE
                    || generation.physicalGraphId().equals(existing.pointer().activePhysicalGraphId())
                    || generation.physicalGraphId().equals(existing.pointer().previousPhysicalGraphId())) {
                throw new IllegalStateException("Cannot abort an active or rollback-target generation");
            }
            return copy(existing, GraphGenerationJournal.State.SEALING, existing.validation(),
                    existing.activation(), clock.instant(), safeMessage(failure), existing.lastOperationId());
        });
        awaitNoActiveWriters(generation.logicalGraphId());
        GraphGenerationJournal.Entry aborted = journal.update(generation.logicalGraphId(), current -> {
            GraphGenerationJournal.Entry existing = requireCurrent(current, generation);
            if (existing.state() == GraphGenerationJournal.State.ABORTED) return existing;
            if (existing.state() == GraphGenerationJournal.State.ACTIVE
                    || generation.physicalGraphId().equals(existing.pointer().activePhysicalGraphId())
                    || generation.physicalGraphId().equals(existing.pointer().previousPhysicalGraphId())) {
                throw new IllegalStateException("Cannot abort an active or rollback-target generation");
            }
            Instant now = clock.instant();
            return copy(existing, GraphGenerationJournal.State.ABORTED, existing.validation(),
                    existing.activation(), now, safeMessage(failure), existing.lastOperationId());
        });
        cleanupAborted(aborted);
    }

    public GraphGeneration.Activation rollback(long factSheetId, String logicalGraphId,
                                               long expectedRevision, String operationId) {
        String operation = normalizeOperation(operationId);
        AtomicBoolean transitioned = new AtomicBoolean();
        GraphGenerationJournal.Entry committed = journal.update(logicalGraphId, current -> {
            GraphGenerationJournal.Entry existing = current.orElseThrow(
                    () -> new IllegalStateException("No graph generation journal exists"));
            assertScope(existing, factSheetId, logicalGraphId);
            if (existing.lastOperationId() != null
                    && existing.lastOperationId().equals(operation)
                    && existing.activation() != null) {
                return existing;
            }
            GraphGeneration.Pointer pointer = existing.pointer();
            if (pointer.previousPhysicalGraphId() == null || pointer.revision() != expectedRevision) {
                throw new IllegalStateException("Graph generation rollback conflict");
            }
            if (!graphStore.physicalGraphExists(pointer.previousPhysicalGraphId(), factSheetId)) {
                throw new IllegalStateException("Graph generation rollback target is unavailable");
            }
            Instant now = clock.instant();
            GraphGeneration.Pointer nextPointer = new GraphGeneration.Pointer(
                    factSheetId, logicalGraphId, pointer.previousPhysicalGraphId(),
                    pointer.activePhysicalGraphId(), pointer.revision() + 1L);
            GraphGeneration.Activation activation = new GraphGeneration.Activation(
                    logicalGraphId, nextPointer.activePhysicalGraphId(),
                    nextPointer.previousPhysicalGraphId(), nextPointer.revision(), now);
            transitioned.set(true);
            return new GraphGenerationJournal.Entry(
                    GraphGenerationJournal.SCHEMA_VERSION, existing.generation(), nextPointer,
                    GraphGenerationJournal.State.ACTIVE, existing.ownerJobId(), 0, existing.validation(),
                    activation, existing.createdAt(), now, now.plus(leaseDuration), null,
                    operation);
        });
        if (transitioned.get()) {
            try {
                graphStore.rollbackGeneration(factSheetId, logicalGraphId, expectedRevision);
            } catch (RuntimeException mirrorFailure) {
                recordMirrorFailure(logicalGraphId, mirrorFailure);
            }
        }
        return committed.activation();
    }

    public GraphGenerationJournal.Entry acquire(GraphGeneration.Target target, String ownerJobId) {
        return journal.update(target.logicalGraphId(), current -> {
            GraphGenerationJournal.Entry existing = current.orElseThrow(
                    () -> new IllegalStateException("Unknown graph generation target"));
            if (!existing.generation().target().equals(target)
                    || existing.state() != GraphGenerationJournal.State.BUILDING) {
                throw new IllegalStateException("Graph generation target is not writable");
            }
            GraphGenerationJournal.Entry renewed = renew(existing, ownerJobId, clock.instant());
            return withActiveWriters(renewed, renewed.activeWriters() + 1);
        });
    }

    public void release(GraphGeneration.Target target, String ownerJobId) {
        journal.update(target.logicalGraphId(), current -> {
            GraphGenerationJournal.Entry existing = current.orElseThrow(
                    () -> new IllegalStateException("Unknown graph generation target"));
            if (!existing.generation().target().equals(target)
                    || !existing.ownerJobId().equals(normalizeOwner(ownerJobId))) {
                throw new IllegalStateException("Graph generation target ownership mismatch");
            }
            if (existing.activeWriters() <= 0) {
                throw new IllegalStateException("Graph generation writer release is unbalanced");
            }
            return withActiveWriters(existing, existing.activeWriters() - 1);
        });
    }

    public Optional<GraphGenerationJournal.Entry> status(String logicalGraphId) {
        return journal.get(logicalGraphId);
    }

    @PostConstruct
    public void recover() {
        Instant now = clock.instant();
        for (GraphGenerationJournal.Entry entry : journal.list()) {
            if ((entry.state() == GraphGenerationJournal.State.BUILDING
                    || entry.state() == GraphGenerationJournal.State.SEALING
                    || entry.state() == GraphGenerationJournal.State.VALIDATED)
                    && entry.leaseExpiresAt().isBefore(now)) {
                try {
                    if (entry.activeWriters() > 0) {
                        journal.update(entry.generation().logicalGraphId(), current -> {
                            GraphGenerationJournal.Entry stale = current.orElseThrow();
                            return withActiveWriters(stale, 0);
                        });
                    }
                    abort(entry.generation(), new IllegalStateException("Graph generation lease expired"));
                } catch (RuntimeException ignored) {
                    // Durable non-terminal state remains visible for operator recovery.
                }
            } else if (entry.state() == GraphGenerationJournal.State.ABORTED) {
                cleanupAborted(entry);
            } else if (entry.state() == GraphGenerationJournal.State.ACTIVE) {
                try {
                    graphStore.repairGenerationPointer(entry.pointer());
                } catch (RuntimeException mirrorFailure) {
                    recordMirrorFailure(entry.generation().logicalGraphId(), mirrorFailure);
                }
            }
        }
    }

    private void cleanupAborted(GraphGenerationJournal.Entry entry) {
        try {
            graphStore.abortGeneration(entry.generation());
        } catch (RuntimeException cleanupFailure) {
            recordMirrorFailure(entry.generation().logicalGraphId(), cleanupFailure);
        }
    }

    private void expireIfNeeded(String logicalGraphId) {
        GraphGenerationJournal.Entry existing = journal.get(logicalGraphId).orElse(null);
        if (existing == null) return;
        boolean nonTerminal = existing.state() == GraphGenerationJournal.State.BUILDING
                || existing.state() == GraphGenerationJournal.State.SEALING
                || existing.state() == GraphGenerationJournal.State.VALIDATED;
        if (nonTerminal && existing.leaseExpiresAt().isBefore(clock.instant())) {
            if (existing.activeWriters() > 0) {
                journal.update(logicalGraphId, current -> withActiveWriters(current.orElseThrow(), 0));
            }
            abort(existing.generation(), new IllegalStateException("Graph generation lease expired"));
        }
    }

    private void awaitNoActiveWriters(String logicalGraphId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (true) {
            GraphGenerationJournal.Entry entry = journal.get(logicalGraphId).orElseThrow(
                    () -> new IllegalStateException("Graph generation journal disappeared while sealing"));
            if (entry.activeWriters() == 0) return;
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException(
                        "Timed out waiting for graph generation writers to quiesce: " + entry.activeWriters());
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while sealing graph generation", interrupted);
            }
        }
    }

    private void markAborted(GraphGeneration.Ref generation, Throwable failure) {
        journal.update(generation.logicalGraphId(), current -> {
            GraphGenerationJournal.Entry existing = requireCurrent(current, generation);
            Instant now = clock.instant();
            return copy(existing, GraphGenerationJournal.State.ABORTED, existing.validation(),
                    existing.activation(), now, safeMessage(failure), existing.lastOperationId());
        });
    }

    private void recordMirrorFailure(String logicalGraphId, Throwable failure) {
        journal.update(logicalGraphId, current -> {
            GraphGenerationJournal.Entry existing = current.orElseThrow();
            Instant now = clock.instant();
            return copy(existing, existing.state(), existing.validation(), existing.activation(),
                    now, "Compatibility pointer mirror failed: " + safeMessage(failure),
                    existing.lastOperationId());
        });
    }

    private GraphGenerationJournal.Entry renew(GraphGenerationJournal.Entry existing,
                                               String ownerJobId, Instant now) {
        String owner = normalizeOwner(ownerJobId);
        if (existing.ownerJobId() != null && !existing.ownerJobId().equals(owner)) {
            throw new IllegalStateException("Graph generation is owned by another job");
        }
        if (existing.leaseExpiresAt().isAfter(now.plus(leaseDuration.dividedBy(2)))) {
            return existing;
        }
        return new GraphGenerationJournal.Entry(
                existing.schemaVersion(), existing.generation(), existing.pointer(), existing.state(),
                owner, existing.activeWriters(), existing.validation(), existing.activation(), existing.createdAt(), now,
                now.plus(leaseDuration), existing.lastError(), existing.lastOperationId());
    }

    private GraphGenerationJournal.Entry copy(
            GraphGenerationJournal.Entry existing,
            GraphGenerationJournal.State state,
            GraphGeneration.Validation validation,
            GraphGeneration.Activation activation,
            Instant now,
            String error,
            String operationId) {
        return new GraphGenerationJournal.Entry(
                existing.schemaVersion(), existing.generation(), existing.pointer(), state,
                existing.ownerJobId(), existing.activeWriters(), validation, activation, existing.createdAt(), now,
                now.plus(leaseDuration),
                error, operationId);
    }

    private GraphGenerationJournal.Entry withActiveWriters(
            GraphGenerationJournal.Entry existing, int activeWriters) {
        Instant now = clock.instant();
        return new GraphGenerationJournal.Entry(
                existing.schemaVersion(), existing.generation(), existing.pointer(), existing.state(),
                existing.ownerJobId(), activeWriters, existing.validation(), existing.activation(),
                existing.createdAt(), now, now.plus(leaseDuration), existing.lastError(),
                existing.lastOperationId());
    }

    private GraphGenerationJournal.Entry requireCurrent(
            Optional<GraphGenerationJournal.Entry> current,
            GraphGeneration.Ref generation) {
        GraphGenerationJournal.Entry existing = current.orElseThrow(
                () -> new IllegalStateException("Unknown graph generation"));
        if (!existing.owns(generation)) {
            throw new IllegalStateException("Graph generation reference does not own the journal entry");
        }
        return existing;
    }

    private void requireOwned(GraphGeneration.Ref generation) {
        Objects.requireNonNull(generation, "generation");
        requireCurrent(journal.get(generation.logicalGraphId()), generation);
    }

    private static void assertScope(GraphGenerationJournal.Entry entry,
                                    long factSheetId, String logicalGraphId) {
        if (entry.generation().factSheetId() != factSheetId
                || !entry.generation().logicalGraphId().equals(logicalGraphId)) {
            throw new IllegalStateException("Graph generation journal scope mismatch");
        }
    }

    private static String normalizeOwner(String ownerJobId) {
        return ownerJobId == null || ownerJobId.isBlank() ? "unowned" : ownerJobId.trim();
    }

    private static String normalizeOperation(String operationId) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId is required for idempotence");
        }
        return operationId.trim();
    }

    private static String safeMessage(Throwable failure) {
        if (failure == null) return null;
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message;
    }
}
