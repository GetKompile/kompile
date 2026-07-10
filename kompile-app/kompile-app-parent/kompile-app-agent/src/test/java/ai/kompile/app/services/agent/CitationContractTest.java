/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.services.agent;

import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure pieces of the citation contract (grounded-RAG rec 4): inline [n] marker extraction and the
 * numbered Sources block (which never uses a raw id/path as the primary label — Part X).
 */
class CitationContractTest {

    @Test
    void extractCitationIndices_distinctInOrder() {
        Set<Integer> used = CitationContract.extractCitationIndices(
                "Revenue rose [1] and headcount grew [3]; see also [1].");
        assertEquals(List.of(1, 3), List.copyOf(used)); // de-duplicated, insertion order
    }

    @Test
    void extractCitationIndices_noneOrNull_empty() {
        assertTrue(CitationContract.extractCitationIndices("no citations here").isEmpty());
        assertTrue(CitationContract.extractCitationIndices(null).isEmpty());
    }

    @Test
    void buildSourcesBlock_empty_returnsEmptyString() {
        assertEquals("", CitationContract.buildSourcesBlock(List.of()));
    }

    @Test
    void buildSourcesBlock_numbersSourcesWithNameAndPreview() {
        RetrievedDoc a = new RetrievedDoc("doc-1", "Q3 revenue was $5M.", Map.of("sourceName", "Q3 Report"), 0.9);
        RetrievedDoc b = new RetrievedDoc("doc-2", "Alice is the CFO.", Map.of(), 0.8);
        String block = CitationContract.buildSourcesBlock(List.of(a, b));

        assertTrue(block.contains("[1] Q3 Report"), block);
        assertTrue(block.contains("Q3 revenue was $5M."), block);
        assertTrue(block.contains("[2] Source 2"), block);         // fallback name, never a raw id
        assertTrue(block.contains("cite it inline as [n]"), block); // instruction present
        assertFalse(block.contains("doc-1"), "raw id must not be the primary label: " + block);
    }
}
