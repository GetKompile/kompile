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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IndexingAliasControllerTest {

    @Mock
    private IndexerController indexerController;

    private IndexingAliasController controller;

    @BeforeEach
    void setUp() {
        controller = new IndexingAliasController(indexerController);
    }

    // ─── getIndexStatusAlias ──────────────────────────────────────────────────

    @Test
    void getIndexStatusAlias_delegatesToIndexerController() {
        ResponseEntity<?> expected = ResponseEntity.ok(Map.of("indexAvailable", true));
        doReturn(expected).when(indexerController).getIndexStatus();

        ResponseEntity<?> resp = controller.getIndexStatusAlias();

        assertThat(resp).isSameAs(expected);
        verify(indexerController).getIndexStatus();
    }

    // ─── rebuildAllSourcesAlias ───────────────────────────────────────────────

    @Test
    void rebuildAllSourcesAlias_delegatesToIndexerController() {
        ResponseEntity<?> expected = ResponseEntity.ok(Map.of("message", "started"));
        doReturn(expected).when(indexerController).rebuildAllSourcesIndex();

        ResponseEntity<?> resp = controller.rebuildAllSourcesAlias();

        assertThat(resp).isSameAs(expected);
        verify(indexerController).rebuildAllSourcesIndex();
    }

    // ─── getIndexingApiInfo ───────────────────────────────────────────────────

    @Test
    void getIndexingApiInfo_returnsOverview() {
        ResponseEntity<?> resp = controller.getIndexingApiInfo();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsKey("message");
    }
}
