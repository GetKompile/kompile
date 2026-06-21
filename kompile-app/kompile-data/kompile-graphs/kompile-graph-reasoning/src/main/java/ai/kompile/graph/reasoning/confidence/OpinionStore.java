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

/**
 * Companion store for Opinion objects keyed by atomKey.
 * Lifecycle: parallel to InferredFactStore — one OpinionStore per factSheet.
 * Default impl: InMemoryOpinionStore (lib-level, no Spring).
 * File-backed impl is a CLIENT concern (kompile-knowledge-graph).
 */
public interface OpinionStore {
    void put(String atomKey, Opinion opinion);
    /** Returns vacuous() if absent. */
    Opinion get(String atomKey);
    boolean has(String atomKey);
    void clear();
    Collection<Map.Entry<String, Opinion>> entries();
    int size();
}
