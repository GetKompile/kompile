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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.app.facts.service;

import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.crawl.graph.VectorIndexConfig;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FactSheetCrawlScopeResolverTest {

    @Test
    void explicitNameResolvesExactSheetAndItsVectorPath() {
        FactSheetService service = mock(FactSheetService.class);
        FactSheet sheet = FactSheet.builder()
                .id(17L)
                .name("FP&A")
                .vectorStorePath("/indices/fpna")
                .build();
        when(service.getSheetByName("FP&A")).thenReturn(Optional.of(sheet));

        UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                .factSheetName("FP&A")
                .vectorIndex(VectorIndexConfig.builder().enabled(true).build())
                .build();

        new FactSheetCrawlScopeResolver(service).resolveScope(request);

        assertEquals(17L, request.getFactSheetId());
        assertEquals("/indices/fpna", request.getVectorIndex().getCollectionName());
        verify(service, never()).getActiveSheet();
    }

    @Test
    void unknownExplicitNameFailsWithoutFallingBackToActiveSheet() {
        FactSheetService service = mock(FactSheetService.class);
        when(service.getSheetByName("FP&A typo")).thenReturn(Optional.empty());

        UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                .factSheetName("FP&A typo")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new FactSheetCrawlScopeResolver(service).resolveScope(request));

        assertEquals("Fact sheet 'FP&A typo' does not exist", error.getMessage());
        verify(service, never()).getActiveSheet();
    }

    @Test
    void omittedScopeUsesActiveSheetAndDeterministicVectorFallback() {
        FactSheetService service = mock(FactSheetService.class);
        when(service.getActiveSheet()).thenReturn(FactSheet.builder()
                .id(23L)
                .name("Active")
                .build());

        UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                .vectorIndex(VectorIndexConfig.builder().enabled(true).build())
                .build();

        new FactSheetCrawlScopeResolver(service).resolveScope(request);

        assertEquals(23L, request.getFactSheetId());
        assertEquals("fact-sheet-23", request.getVectorIndex().getCollectionName());
    }

    @Test
    void explicitCollectionIsNeverOverridden() {
        FactSheetService service = mock(FactSheetService.class);
        when(service.getSheetById(31L)).thenReturn(Optional.of(FactSheet.builder()
                .id(31L)
                .name("Sheet")
                .vectorStorePath("/indices/sheet")
                .build()));

        UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                .factSheetId(31L)
                .vectorIndex(VectorIndexConfig.builder()
                        .enabled(true)
                        .collectionName("/indices/custom")
                        .build())
                .build();

        new FactSheetCrawlScopeResolver(service).resolveScope(request);

        assertEquals(31L, request.getFactSheetId());
        assertEquals("/indices/custom", request.getVectorIndex().getCollectionName());
    }
}
