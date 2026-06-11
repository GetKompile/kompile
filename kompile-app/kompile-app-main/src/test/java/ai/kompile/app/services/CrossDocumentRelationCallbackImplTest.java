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

package ai.kompile.app.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link CrossDocumentRelationCallbackImpl} — verifies it correctly
 * delegates to {@link CrossDocumentRelationExtractor}.
 */
@ExtendWith(MockitoExtension.class)
class CrossDocumentRelationCallbackImplTest {

    @Mock
    private CrossDocumentRelationExtractor extractor;

    private CrossDocumentRelationCallbackImpl callback;

    @BeforeEach
    void setUp() {
        callback = new CrossDocumentRelationCallbackImpl(extractor);
    }

    @Test
    void delegatesToExtractorWithFactSheetId() {
        when(extractor.extractRelationsFromGraphNodes(42L)).thenReturn(7);

        int result = callback.extractRelationsFromGraphNodes(42L);

        assertEquals(7, result);
        verify(extractor).extractRelationsFromGraphNodes(42L);
    }

    @Test
    void delegatesToExtractorWithNullFactSheetId() {
        when(extractor.extractRelationsFromGraphNodes(null)).thenReturn(3);

        int result = callback.extractRelationsFromGraphNodes(null);

        assertEquals(3, result);
        verify(extractor).extractRelationsFromGraphNodes(null);
    }

    @Test
    void returnsZeroWhenExtractorReturnsZero() {
        when(extractor.extractRelationsFromGraphNodes(any())).thenReturn(0);

        assertEquals(0, callback.extractRelationsFromGraphNodes(1L));
    }

    @Test
    void propagatesExtractorException() {
        when(extractor.extractRelationsFromGraphNodes(any()))
                .thenThrow(new RuntimeException("DB error"));

        assertThrows(RuntimeException.class,
                () -> callback.extractRelationsFromGraphNodes(1L));
    }
}
