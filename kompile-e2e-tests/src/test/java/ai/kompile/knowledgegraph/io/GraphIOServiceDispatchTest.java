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

import ai.kompile.knowledgegraph.io.model.ExportResult;
import ai.kompile.knowledgegraph.io.model.ImportResult;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphIOServiceDispatchTest {

    private KnowledgeGraphService kgs;
    private GraphIOService service;

    @BeforeEach
    void setUp() {
        kgs = Mockito.mock(KnowledgeGraphService.class);
        Mockito.when(kgs.searchNodes(Mockito.anyString(), Mockito.any(), Mockito.anyInt()))
                .thenReturn(Collections.emptyList());
        service = new GraphIOService(kgs, new ObjectMapper());
    }

    @Test
    void exportEmptyGraphInJsonFormat() throws Exception {
        ExportResult r = service.exportGraph("json", null);
        assertEquals("json", r.format());
        assertEquals(0, r.nodesExported());
        assertEquals(0, r.edgesExported());
        assertEquals("application/json", r.contentType());
        assertNotNull(r.data());
    }

    @Test
    void exportEmptyGraphInJsonLdFormat() throws Exception {
        ExportResult r = service.exportGraph("jsonld", null);
        assertEquals("jsonld", r.format());
        assertEquals("application/ld+json", r.contentType());
        assertTrue(r.suggestedFilename().endsWith(".jsonld"));
    }

    @Test
    void exportEmptyGraphInCsvFormat() throws Exception {
        ExportResult r = service.exportGraph("csv", null);
        assertEquals("csv", r.format());
        assertEquals("application/zip", r.contentType());
    }

    @Test
    void exportEmptyGraphInGraphMlFormat() throws Exception {
        ExportResult r = service.exportGraph("graphml", null);
        assertEquals("graphml", r.format());
        assertEquals("application/xml", r.contentType());
        String xml = new String(r.data());
        assertTrue(xml.contains("<graphml"), "Expected <graphml> root element");
    }

    @Test
    void exportEmptyGraphInCypherFormat() throws Exception {
        ExportResult r = service.exportGraph("cypher", null);
        assertEquals("cypher", r.format());
        assertEquals("text/plain", r.contentType());
    }

    @Test
    void exportRejectsUnknownFormat() {
        assertThrows(IllegalArgumentException.class, () -> service.exportGraph("xml", null));
    }

    @Test
    void importRejectsUnknownFormat() {
        assertThrows(IllegalArgumentException.class,
                () -> service.importGraph("xml", new byte[0], null));
    }

    @Test
    void jsonImportSurfacesNodeErrors() throws Exception {
        Mockito.when(kgs.createNode(Mockito.any(), Mockito.anyString(), Mockito.anyString(),
                        Mockito.any(), Mockito.any()))
                .thenThrow(new RuntimeException("simulated db failure"));
        String json = "{\"nodes\":[{\"externalId\":\"n1\",\"title\":\"T\",\"nodeType\":\"ENTITY\"}],\"edges\":[]}";
        ImportResult result = service.importGraph("json", json.getBytes(), null);
        assertEquals("json", result.format());
        assertEquals(1, result.errors());
        assertEquals(0, result.nodesCreated());
        assertTrue(result.errorMessages().get(0).contains("n1"));
    }
}
