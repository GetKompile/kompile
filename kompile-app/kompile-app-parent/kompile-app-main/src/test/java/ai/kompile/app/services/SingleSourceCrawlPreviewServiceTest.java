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

import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig;
import ai.kompile.crawl.graph.GraphExtractionPreviewService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SingleSourceCrawlPreviewServiceTest {

    @Test
    void textPreviewPropagatesExactModelAndProviderToGraphExtraction() throws Exception {
        GraphExtractionPreviewService graphPreview = mock(GraphExtractionPreviewService.class);
        when(graphPreview.preview(
                anyList(),
                any(GraphExtractionConfig.class),
                nullable(ProcessingRouteConfig.class),
                anyInt(),
                anyInt()))
                .thenReturn(GraphExtractionPreviewService.PreviewResponse.skipped("captured"));

        SingleSourceCrawlPreviewService service = new SingleSourceCrawlPreviewService(
                null, List.of(), List.of(), null, graphPreview, null, null);

        service.preview(new SingleSourceCrawlPreviewService.SingleSourcePreviewRequest(
                "text",
                "FP&A forecast email",
                null,
                "Maya Chen asked Jordan Lee to reconcile the Q3 forecast workbook.",
                null,
                null,
                null,
                1,
                "en",
                Map.of(
                        "graphPreviewEnabled", true,
                        "graphPreviewMaxDocuments", 1,
                        "graphPreviewMaxCharsPerDocument", 8_000,
                        "graphPreviewModelName", "  lfm2.5-1.2b-instruct  ",
                        "graphPreviewLlmProvider", "  serving  ")));

        ArgumentCaptor<GraphExtractionConfig> configCaptor =
                ArgumentCaptor.forClass(GraphExtractionConfig.class);
        verify(graphPreview).preview(
                anyList(),
                configCaptor.capture(),
                nullable(ProcessingRouteConfig.class),
                eq(1),
                eq(8_000));

        GraphExtractionConfig captured = configCaptor.getValue();
        assertEquals("lfm2.5-1.2b-instruct", captured.getModelName());
        assertEquals("serving", captured.getLlmProvider());
    }
}
