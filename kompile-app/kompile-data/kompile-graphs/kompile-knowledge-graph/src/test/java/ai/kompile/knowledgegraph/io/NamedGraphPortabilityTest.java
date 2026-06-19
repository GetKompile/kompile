/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.io;

import ai.kompile.knowledgegraph.domain.NamedGraph;
import ai.kompile.knowledgegraph.repository.NamedGraphRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Round-trip tests for {@link NamedGraphPortability} — registry export and re-import
 * (graphId preserved, parent hierarchy relinked, idempotent skip of existing rows).
 */
class NamedGraphPortabilityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void exportThenImport_preservesIdentityAndHierarchy() {
        NamedGraphRepository srcRepo = mock(NamedGraphRepository.class);
        NamedGraph root = NamedGraph.builder()
                .graphId("g-root").name("Root").description("top").ontologyType("domain_ontology")
                .schemaJson("{\"entityTypes\":[]}").factSheetId(7L).build();
        NamedGraph child = NamedGraph.builder()
                .graphId("g-child").name("Child").factSheetId(7L).build();
        child.setParentGraph(root);
        when(srcRepo.findAll()).thenReturn(List.of(root, child));

        byte[] data = new NamedGraphPortability(srcRepo, mapper).export();
        assertNotNull(data);

        // Import side — empty target repo.
        NamedGraphRepository dstRepo = mock(NamedGraphRepository.class);
        when(dstRepo.existsByGraphId(anyString())).thenReturn(false);
        NamedGraph rootDst = NamedGraph.builder().graphId("g-root").name("Root").build();
        NamedGraph childDst = NamedGraph.builder().graphId("g-child").name("Child").build();
        when(dstRepo.findByGraphId("g-root")).thenReturn(Optional.of(rootDst));
        when(dstRepo.findByGraphId("g-child")).thenReturn(Optional.of(childDst));

        int created = new NamedGraphPortability(dstRepo, mapper).importGraphs(data);

        assertEquals(2, created);
        // Both rows saved, child relinked to root.
        verify(dstRepo, atLeast(2)).save(any(NamedGraph.class));
        assertSame(rootDst, childDst.getParentGraph());
    }

    @Test
    void importGraphs_skipsExistingRows() {
        NamedGraphRepository repo = mock(NamedGraphRepository.class);
        NamedGraph g = NamedGraph.builder().graphId("g1").name("Existing").build();
        when(repo.findAll()).thenReturn(List.of(g));
        byte[] data = new NamedGraphPortability(repo, mapper).export();

        NamedGraphRepository repo2 = mock(NamedGraphRepository.class);
        when(repo2.existsByGraphId("g1")).thenReturn(true);

        assertEquals(0, new NamedGraphPortability(repo2, mapper).importGraphs(data));
        verify(repo2, never()).save(any());
    }

    @Test
    void binding_survivesRoundTrip() {
        NamedGraphRepository srcRepo = mock(NamedGraphRepository.class);
        NamedGraph g = NamedGraph.builder()
                .graphId("g-bound").name("Bound").factSheetId(9L)
                .ontologySchemaId("ont-7").ontologyVersion(3).build();
        when(srcRepo.findAll()).thenReturn(List.of(g));

        byte[] data = new NamedGraphPortability(srcRepo, mapper).export();
        assertNotNull(data);

        NamedGraphRepository dstRepo = mock(NamedGraphRepository.class);
        when(dstRepo.existsByGraphId("g-bound")).thenReturn(false);

        int created = new NamedGraphPortability(dstRepo, mapper).importGraphs(data);
        assertEquals(1, created);

        // The recreated row must carry the ontology binding — otherwise a clone loses governance.
        ArgumentCaptor<NamedGraph> captor = ArgumentCaptor.forClass(NamedGraph.class);
        verify(dstRepo).save(captor.capture());
        NamedGraph imported = captor.getValue();
        assertEquals("ont-7", imported.getOntologySchemaId());
        assertEquals(3, imported.getOntologyVersion());
    }

    @Test
    void export_empty_returnsNull() {
        NamedGraphRepository repo = mock(NamedGraphRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        assertNull(new NamedGraphPortability(repo, mapper).export());
    }
}
