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

import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.core.retrievers.NoOpDocumentRetrieverImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RetrieverControllerTest {

    @Mock
    private DocumentRetriever documentRetriever;

    private RetrieverController controller;

    @BeforeEach
    void setUp() {
        controller = new RetrieverController(List.of(documentRetriever));
    }

    // ── searchDocuments ───────────────────────────────────────────────────

    @Test
    void searchDocuments_emptyQuery_returns400() {
        ResponseEntity<?> resp = controller.searchDocuments("", 5);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void searchDocuments_blankQuery_returns400() {
        ResponseEntity<?> resp = controller.searchDocuments("   ", 5);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void searchDocuments_zeroMaxResults_returns400() {
        ResponseEntity<?> resp = controller.searchDocuments("test", 0);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void searchDocuments_tooHighMaxResults_returns400() {
        ResponseEntity<?> resp = controller.searchDocuments("test", 51);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void searchDocuments_success_returns200WithHits() {
        List<String> hits = List.of("result 1", "result 2");
        when(documentRetriever.retrieve("java streams", 5)).thenReturn(hits);

        ResponseEntity<?> resp = controller.searchDocuments("java streams", 5);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals("java streams", body.get("query"));
        assertEquals(5, body.get("maxResults"));
        List<?> returnedHits = (List<?>) body.get("hits");
        assertEquals(2, returnedHits.size());
    }

    @Test
    void searchDocuments_nullResult_returns500() {
        when(documentRetriever.retrieve(any(), anyInt())).thenReturn(null);

        ResponseEntity<?> resp = controller.searchDocuments("query", 5);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
    }

    @Test
    void searchDocuments_errorPrefixResult_returns500() {
        when(documentRetriever.retrieve(any(), anyInt())).thenReturn(List.of("Error: index unavailable"));

        ResponseEntity<?> resp = controller.searchDocuments("query", 5);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
    }

    @Test
    void searchDocuments_exception_returns500() {
        when(documentRetriever.retrieve(any(), anyInt())).thenThrow(new RuntimeException("fail"));

        ResponseEntity<?> resp = controller.searchDocuments("query", 5);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
    }

    @Test
    void searchDocuments_emptyResults_returns200() {
        when(documentRetriever.retrieve(any(), anyInt())).thenReturn(List.of());

        ResponseEntity<?> resp = controller.searchDocuments("query", 5);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertTrue(((List<?>) body.get("hits")).isEmpty());
    }

    @Test
    void constructor_withMultipleRetrievers_selectsNonNoOp() {
        NoOpDocumentRetrieverImpl noOp = new NoOpDocumentRetrieverImpl();
        DocumentRetriever real = mock(DocumentRetriever.class);
        when(real.retrieve(any(), anyInt())).thenReturn(List.of("doc1"));

        RetrieverController multiController = new RetrieverController(List.of(noOp, real));

        ResponseEntity<?> resp = multiController.searchDocuments("test", 5);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(real).retrieve("test", 5);
    }
}
