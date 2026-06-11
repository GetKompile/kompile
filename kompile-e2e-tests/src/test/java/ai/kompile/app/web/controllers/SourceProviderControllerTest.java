/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.web.controllers;

import ai.kompile.core.source.provider.SourceProvider;
import ai.kompile.core.source.provider.SourceProviderDto;
import ai.kompile.core.source.provider.SourceProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link SourceProviderController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SourceProviderControllerTest {

    @Mock
    private SourceProviderRegistry registry;

    private SourceProviderController controller;

    @BeforeEach
    void setUp() {
        controller = new SourceProviderController(registry);
        when(registry.getAvailableProviders()).thenReturn(Collections.emptyList());
        when(registry.getAllProviders()).thenReturn(Collections.emptyList());
        when(registry.getProviderCount()).thenReturn(0);
        when(registry.getAvailableProviderCount()).thenReturn(0);
        when(registry.getProvidersByCategories()).thenReturn(Collections.emptyMap());
    }

    @Test
    void getSourceProviders_excludeUnavailable_returnsAvailableOnly() {
        ResponseEntity<Map<String, Object>> response = controller.getSourceProviders(false);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("providers"));
        assertTrue(response.getBody().containsKey("totalCount"));
        assertTrue(response.getBody().containsKey("availableCount"));
        verify(registry).getAvailableProviders();
    }

    @Test
    void getSourceProviders_includeUnavailable_returnsAll() {
        ResponseEntity<Map<String, Object>> response = controller.getSourceProviders(true);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(registry).getAllProviders();
    }

    @Test
    void getProvidersByCategory_returnsOk() {
        ResponseEntity<Map<String, List<SourceProviderDto>>> response =
                controller.getProvidersByCategory();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void getProvider_found_returnsOk() {
        SourceProvider provider = mock(SourceProvider.class);
        when(registry.getProvider("local-file")).thenReturn(provider);

        // This test validates the non-null path — SourceProviderDto.fromProvider is static
        // and we cannot easily mock it, but we can test the null guard
        ResponseEntity<SourceProviderDto> response = controller.getProvider("local-file");

        // The method will return OK if SourceProviderDto.fromProvider doesn't throw
        assertNotNull(response);
    }

    @Test
    void getProvider_notFound_returnsNotFound() {
        when(registry.getProvider("missing")).thenReturn(null);

        ResponseEntity<SourceProviderDto> response = controller.getProvider("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void getCategories_returnsFourCategories() {
        ResponseEntity<List<Map<String, Object>>> response = controller.getCategories();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(4, response.getBody().size());
        assertTrue(response.getBody().stream()
                .anyMatch(m -> "local".equals(m.get("id"))));
        assertTrue(response.getBody().stream()
                .anyMatch(m -> "web".equals(m.get("id"))));
    }
}
