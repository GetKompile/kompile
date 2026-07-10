/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.knowledgegraph.unified;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
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

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new UnifiedGraphIOController(bridge)).build();
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
                .andExpect(jsonPath("$.graphBuildEventPublished").value(true));

        verify(bridge).importBytes(any(byte[].class), isNull());
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
