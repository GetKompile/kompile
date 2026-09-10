/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.knowledgegraph.unified;

import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UnifiedGraphIOControllerTest {

    @Mock
    private UnifiedGraphBridge bridge;

    private MockMvc mockMvc;
    private UnifiedGraphIOController controller;

    @BeforeEach
    void setUp() {
        controller = new UnifiedGraphIOController(bridge);
        lenient().when(bridge.hasDurableImportJournal()).thenReturn(true);
        ReflectionTestUtils.setField(controller, "importProfile", "MANAGED");
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void exportRouteReturnsPortableBytesForRequestedFactSheet() throws Exception {
        byte[] payload = new byte[]{0x4b, 0x47, 0x52, 0x46};
        when(bridge.exportBytes(42L)).thenReturn(payload);

        mockMvc.perform(get(UnifiedGraphIOController.BASE_PATH + "/export")
                        .param("factSheetId", "42"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"kompile-graph-42.kgraph\""))
                .andExpect(content().bytes(payload));

        verify(bridge).exportBytes(42L);
    }

    @Test
    void importRouteReturnsStableSummaryShape() throws Exception {
        byte[] payload = new byte[]{0x4b, 0x47, 0x52, 0x46};
        when(bridge.importBytes(any(byte[].class), isNull()))
                .thenReturn(new UnifiedGraphBridge.ImportSummary(3, 2, 7, 4, true));
        MockMultipartFile file = new MockMultipartFile(
                "file", "sample.kgraph", MediaType.APPLICATION_OCTET_STREAM_VALUE, payload);

        mockMvc.perform(multipart(UnifiedGraphIOController.BASE_PATH + "/import").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes").value(3))
                .andExpect(jsonPath("$.edges").value(2))
                .andExpect(jsonPath("$.embeddings").value(7))
                .andExpect(jsonPath("$.atoms").value(4))
                .andExpect(jsonPath("$.graphBuildEventPublished").value(true))
                .andExpect(jsonPath("$.profile").value("MANAGED"))
                .andExpect(jsonPath("$.durability").value("COMPENSATING"));

        verify(bridge).importBytes(any(byte[].class), isNull());
    }

    @Test
    void importCapabilitiesExposeConfiguredBoundary() throws Exception {
        mockMvc.perform(get(UnifiedGraphIOController.BASE_PATH + "/import-capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").value("MANAGED"))
                .andExpect(jsonPath("$.managedCompleteness").value(true))
                .andExpect(jsonPath("$.durability").value("COMPENSATING"))
                .andExpect(jsonPath("$.projectBatchImport").value(false));
    }

    @Test
    void managedRequirementFailsClosedOnEphemeralService() throws Exception {
        ReflectionTestUtils.setField(controller, "importProfile", "EPHEMERAL");
        byte[] payload = new byte[]{0x4b, 0x47, 0x52, 0x46};
        MockMultipartFile file = new MockMultipartFile(
                "file", "sample.kgraph", MediaType.APPLICATION_OCTET_STREAM_VALUE, payload);

        mockMvc.perform(multipart(UnifiedGraphIOController.BASE_PATH + "/import")
                        .file(file)
                        .param("requireManaged", "true"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.profile").value("EPHEMERAL"));

        verify(bridge, never()).importBytes(any(byte[].class), any());
    }

    @Test
    void managedProfileDowngradesWhenRecoveryJournalIsUnavailable() throws Exception {
        when(bridge.hasDurableImportJournal()).thenReturn(false);

        mockMvc.perform(get(UnifiedGraphIOController.BASE_PATH + "/import-capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").value("COMPATIBILITY"))
                .andExpect(jsonPath("$.managedCompleteness").value(false));
    }

    @Test
    void asciiExportUsesSharedDebugRenderer() throws Exception {
        UnifiedGraph graph = new UnifiedGraph()
                .graphId("factsheet_42")
                .addEntity("node-1", "Person", "Alice")
                .meta("schema", "observed");
        when(bridge.export(42L)).thenReturn(graph);

        mockMvc.perform(get(UnifiedGraphIOController.BASE_PATH + "/export")
                        .param("factSheetId", "42")
                        .param("format", "ascii"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/plain;charset=US-ASCII"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"kompile-graph-42.txt\""))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("node-1")));

        verify(bridge).export(42L);
    }

    @Test
    void pngExportReturnsNativePngWhenBundlingDisabled() throws Exception {
        when(bridge.export(42L)).thenReturn(new UnifiedGraph().addEntity("node-1", "Person", "Alice"));

        var response = mockMvc.perform(get(UnifiedGraphIOController.BASE_PATH + "/export")
                        .param("factSheetId", "42")
                        .param("format", "png")
                        .param("bundle", "false"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"kompile-graph-42.png\""))
                .andReturn()
                .getResponse();

        byte[] png = response.getContentAsByteArray();
        assertTrue(png.length > 8);
        assertEquals((byte) 0x89, png[0]);
        assertEquals((byte) 'P', png[1]);
        assertEquals((byte) 'N', png[2]);
        assertEquals((byte) 'G', png[3]);
        verify(bridge).export(42L);
    }

    @Test
    void importRouteRejectsEmptyPayloadWithoutMutatingGraph() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.kgraph", MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[0]);

        mockMvc.perform(multipart(UnifiedGraphIOController.BASE_PATH + "/import").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("A non-empty .kgraph file is required"));

        verify(bridge, never()).importBytes(any(byte[].class), anyLong());
        verify(bridge, never()).importBytes(any(byte[].class), isNull());
    }
}
