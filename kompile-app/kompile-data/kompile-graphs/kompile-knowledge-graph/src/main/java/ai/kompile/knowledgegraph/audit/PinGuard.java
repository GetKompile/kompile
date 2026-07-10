/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight collaborator injected into
 * {@link ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator} to block
 * re-derivation of human-pinned atoms during cascade re-grounds.
 *
 * <p>Holds one {@link FileBackedPinStore} per fact sheet (lazy, same pattern as
 * {@link ai.kompile.knowledgegraph.grounding.KbGroundingService}'s state map).
 * Null {@code dataDir} → in-memory-only pin stores (test contexts).</p>
 */
@Slf4j
@Component
public class PinGuard {

    @Nullable
    private final String dataDir;
    private final Map<Long, FileBackedPinStore> storeMap = new ConcurrentHashMap<>();

    /**
     * Spring constructor: {@code kompile.data.dir} is injected by Spring when available.
     * In tests that instantiate PinGuard directly, pass null to use in-memory pin stores.
     */
    public PinGuard(@Nullable @Value("${kompile.data.dir:#{null}}") String dataDir) {
        this.dataDir = dataDir;
    }

    /** No-arg constructor for test contexts (in-memory pin stores). */
    public PinGuard() {
        this(null);
    }

    /**
     * Return true if the atom key is pinned for this fact sheet and the cascade should
     * skip writing a new InferredFact version.
     */
    public boolean isPinned(long factSheetId, String atomKey) {
        return getStore(factSheetId).isPinned(atomKey);
    }

    /**
     * Save a new or updated PinRecord for the given fact sheet.
     */
    public void save(long factSheetId, PinRecord record) {
        getStore(factSheetId).save(record);
        log.debug("PinGuard: saved pin for factSheet={} atomKey={} pinned={}",
                factSheetId, record.atomKey(), record.pinned());
    }

    /**
     * Retrieve the PinRecord for a specific atom key, if any.
     */
    public Optional<PinRecord> get(long factSheetId, String atomKey) {
        return getStore(factSheetId).get(atomKey);
    }

    /**
     * All currently active (pinned=true) records for a fact sheet.
     */
    public List<PinRecord> activePins(long factSheetId) {
        return getStore(factSheetId).activePins();
    }

    /** Return the pin store for a fact sheet, creating it lazily. */
    public FileBackedPinStore getStore(long factSheetId) {
        return storeMap.computeIfAbsent(factSheetId, id -> {
            if (dataDir != null && !dataDir.isBlank()) {
                return new FileBackedPinStore(java.nio.file.Path.of(dataDir), id);
            }
            return new FileBackedPinStore(id);
        });
    }
}
