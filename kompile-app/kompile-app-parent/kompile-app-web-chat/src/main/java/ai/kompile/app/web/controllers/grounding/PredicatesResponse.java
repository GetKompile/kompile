/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.web.controllers.grounding;

import java.util.List;

/**
 * Response body for GET /api/kb-grounding/predicates (P2 predicate discovery endpoint).
 *
 * <p>Wire shape:
 * <pre>{"predicates":[{"name":"isEmployedBy","count":14,"inferred":false}, ...]}</pre>
 *
 * @param predicates distinct predicate names with occurrence counts, sorted by count descending
 */
public record PredicatesResponse(List<PredicateEntry> predicates) {

    /**
     * A single predicate entry.
     *
     * @param name     predicate name as it appears in atom keys (e.g. {@code "isEmployedBy"})
     * @param count    number of atoms in the fact sheet carrying this predicate
     * @param inferred true when the predicate appears only in the inferred store
     *                 (no directly-observed base fact exists with this predicate name)
     */
    public record PredicateEntry(String name, int count, boolean inferred) {}
}
