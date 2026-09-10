/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.knowledgegraph.unified;

import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Durable phase journal and recovery payloads for compensating managed graph imports. */
@Service
public class UnifiedGraphImportJournal {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final String JOURNAL_DIR = "graph-import-transactions";
    private static final String MANIFEST = "manifest.json";

    public enum Phase {
        PREPARED,
        APPLYING,
        RECOVERY_REQUIRED,
        COMMITTED,
        ROLLED_BACK
    }

    public record ScopeArchive(long factSheetId, UnifiedGraph incoming, UnifiedGraph backup) { }

    public record Manifest(
            String transactionId,
            Phase phase,
            List<Long> factSheetIds,
            Instant updatedAt,
            String failure) {
        public Manifest {
            factSheetIds = factSheetIds == null ? List.of() : List.copyOf(factSheetIds);
        }
    }

    @Value("${kompile.data.dir:}")
    private String dataDir;

    private final Map<String, Manifest> active = new LinkedHashMap<>();
    private final Set<Long> blockedFactSheets = new LinkedHashSet<>();
    private final Set<String> recoveringTransactions = new LinkedHashSet<>();

    public boolean isEnabled() {
        return dataDir != null && !dataDir.isBlank();
    }

    /** Recover interrupted journals conservatively; no live state is guessed or overwritten. */
    @PostConstruct
    public synchronized void recoverInterrupted() {
        if (!isEnabled()) return;
        Path root = root();
        if (!Files.isDirectory(root)) return;
        try (var directories = Files.list(root)) {
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                Path manifestPath = directory.resolve(MANIFEST);
                if (!Files.isRegularFile(manifestPath)) {
                    deleteTree(directory);
                    continue;
                }
                Manifest manifest = MAPPER.readValue(manifestPath.toFile(), Manifest.class);
                validateTransactionId(manifest.transactionId());
                if (manifest.phase() == Phase.COMMITTED || manifest.phase() == Phase.ROLLED_BACK) {
                    deleteTree(directory);
                    continue;
                }
                if (manifest.phase() == Phase.PREPARED || manifest.phase() == Phase.APPLYING) {
                    manifest = new Manifest(manifest.transactionId(), Phase.RECOVERY_REQUIRED,
                            manifest.factSheetIds(), Instant.now(), "Import interrupted before a terminal phase");
                    writeManifest(directory, manifest);
                }
                active.put(manifest.transactionId(), manifest);
                blockedFactSheets.addAll(manifest.factSheetIds());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not recover graph import journals", e);
        }
    }

    public synchronized void assertAvailable(Collection<Long> factSheetIds) {
        if (!isEnabled() || factSheetIds == null) return;
        for (Long factSheetId : factSheetIds) {
            if (factSheetId != null && blockedFactSheets.contains(factSheetId)) {
                throw new IllegalStateException(
                        "Graph import recovery is required before replacing fact sheet " + factSheetId);
            }
        }
    }

    /** Persist incoming and compensating graphs before any live mutation. */
    public synchronized void start(String transactionId, List<ScopeArchive> scopes) {
        if (!isEnabled()) return;
        validateTransactionId(transactionId);
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("journal scopes are required");
        }
        Path directory = root().resolve(transactionId);
        if (Files.exists(directory)) {
            throw new IllegalStateException("Graph import journal already exists: " + transactionId);
        }
        try {
            Files.createDirectories(directory);
            List<Long> ids = new ArrayList<>(scopes.size());
            for (ScopeArchive scope : scopes) {
                if (scope == null || scope.incoming() == null || scope.backup() == null) {
                    throw new IllegalArgumentException("journal scope graphs are required");
                }
                ids.add(scope.factSheetId());
                scope.incoming().save(directory.resolve("factsheet-" + scope.factSheetId() + "-incoming.kgraph"));
                scope.backup().save(directory.resolve("factsheet-" + scope.factSheetId() + "-backup.kgraph"));
            }
            Manifest manifest = new Manifest(transactionId, Phase.PREPARED, ids, Instant.now(), null);
            writeManifest(directory, manifest);
            active.put(transactionId, manifest);
            recomputeBlockedFactSheets();
        } catch (IOException | RuntimeException failure) {
            try {
                deleteTree(directory);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException("Could not create graph import journal", failure);
        }
    }

    public synchronized void markApplying(String transactionId) {
        transition(transactionId, Phase.APPLYING, null);
    }

    public synchronized void markRecoveryRequired(String transactionId, Throwable failure) {
        transition(transactionId, Phase.RECOVERY_REQUIRED,
                failure == null ? "Compensation was incomplete" : safeMessage(failure));
    }

    public synchronized void complete(String transactionId, Phase terminalPhase) {
        if (terminalPhase != Phase.COMMITTED && terminalPhase != Phase.ROLLED_BACK) {
            throw new IllegalArgumentException("A terminal journal phase is required");
        }
        if (!isEnabled()) return;
        Manifest manifest = requireActive(transactionId);
        Manifest terminal = new Manifest(transactionId, terminalPhase, manifest.factSheetIds(), Instant.now(), null);
        Path directory = root().resolve(transactionId);
        try {
            writeManifest(directory, terminal);
            active.remove(transactionId);
            recoveringTransactions.remove(transactionId);
            recomputeBlockedFactSheets();
        } catch (IOException e) {
            throw new IllegalStateException("Could not complete graph import journal " + transactionId, e);
        }
        try {
            deleteTree(directory);
        } catch (IOException ignored) {
            // The terminal manifest remains safe to prune during the next startup recovery scan.
        }
    }

    private void transition(String transactionId, Phase phase, String failure) {
        if (!isEnabled()) return;
        Manifest current = requireActive(transactionId);
        Manifest updated = new Manifest(transactionId, phase, current.factSheetIds(), Instant.now(), failure);
        try {
            writeManifest(root().resolve(transactionId), updated);
            active.put(transactionId, updated);
        } catch (IOException e) {
            throw new IllegalStateException("Could not update graph import journal " + transactionId, e);
        }
    }

    /** Load durable backup graphs and temporarily release their scopes for a recovery import. */
    public synchronized List<ScopeArchive> beginRecovery(String transactionId) {
        if (!isEnabled()) {
            throw new IllegalStateException("Graph import journal is disabled");
        }
        Manifest manifest = requireActive(transactionId);
        if (manifest.phase() != Phase.RECOVERY_REQUIRED) {
            throw new IllegalStateException("Graph import is not awaiting recovery: " + transactionId);
        }
        Path directory = root().resolve(transactionId);
        List<ScopeArchive> scopes = new ArrayList<>(manifest.factSheetIds().size());
        try {
            for (Long factSheetId : manifest.factSheetIds()) {
                UnifiedGraph incoming = UnifiedGraph.load(
                        directory.resolve("factsheet-" + factSheetId + "-incoming.kgraph"));
                UnifiedGraph backup = UnifiedGraph.load(
                        directory.resolve("factsheet-" + factSheetId + "-backup.kgraph"));
                scopes.add(new ScopeArchive(factSheetId, incoming, backup));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not load graph import recovery payload", e);
        }
        recoveringTransactions.add(transactionId);
        recomputeBlockedFactSheets();
        return List.copyOf(scopes);
    }

    /** Re-block a recovery transaction when replay did not reach a committed state. */
    public synchronized void recoveryFailed(String transactionId) {
        requireActive(transactionId);
        recoveringTransactions.remove(transactionId);
        recomputeBlockedFactSheets();
    }

    private void recomputeBlockedFactSheets() {
        blockedFactSheets.clear();
        active.forEach((transactionId, manifest) -> {
            if (!recoveringTransactions.contains(transactionId)) {
                blockedFactSheets.addAll(manifest.factSheetIds());
            }
        });
    }

    private Manifest requireActive(String transactionId) {
        validateTransactionId(transactionId);
        Manifest manifest = active.get(transactionId);
        if (manifest == null) throw new IllegalStateException("Unknown graph import journal: " + transactionId);
        return manifest;
    }

    private Path root() {
        return Path.of(dataDir).toAbsolutePath().normalize().resolve(JOURNAL_DIR);
    }

    private static void writeManifest(Path directory, Manifest manifest) throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve(MANIFEST);
        Path temporary = Files.createTempFile(directory, "manifest-", ".tmp");
        try {
            MAPPER.writeValue(temporary.toFile(), manifest);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void validateTransactionId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}") || value.contains("..")) {
            throw new IllegalArgumentException("Invalid graph import transaction ID");
        }
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
