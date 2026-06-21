/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.confidence;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory implementation of OpinionStore.
 */
public class InMemoryOpinionStore implements OpinionStore {

    private final ConcurrentHashMap<String, Opinion> store = new ConcurrentHashMap<>();

    @Override
    public void put(String atomKey, Opinion opinion) {
        if (atomKey == null) throw new IllegalArgumentException("atomKey must not be null");
        if (opinion == null) throw new IllegalArgumentException("opinion must not be null");
        store.put(atomKey, opinion);
    }

    @Override
    public Opinion get(String atomKey) {
        return store.getOrDefault(atomKey, Opinion.vacuous());
    }

    @Override
    public boolean has(String atomKey) { return store.containsKey(atomKey); }

    @Override
    public void clear() { store.clear(); }

    @Override
    public Collection<Map.Entry<String, Opinion>> entries() { return store.entrySet(); }

    @Override
    public int size() { return store.size(); }
}
