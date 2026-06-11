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

import ai.kompile.app.services.DocumentIngestService;
import ai.kompile.core.crawler.CrawlConfig;
import ai.kompile.core.crawler.CrawlJob;
import ai.kompile.core.crawler.CrawlProgress;
import ai.kompile.core.crawler.pipeline.IngestPipelineDefinition;
import ai.kompile.crawler.CrawlerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link CrawlerController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CrawlerControllerTest {

    @Mock
    private CrawlerService crawlerService;

    @Mock
    private DocumentIngestService documentIngestService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private CrawlerController controller;

    @BeforeEach
    void setUp() {
        controller = new CrawlerController(crawlerService, documentIngestService, messagingTemplate);
    }

    @Test
    void listCrawlers_returnsOk() {
        List<Map<String, Object>> crawlers = List.of(Map.of("id", "web", "name", "Web Crawler"));
        when(crawlerService.listCrawlers()).thenReturn(crawlers);

        ResponseEntity<List<Map<String, Object>>> response = controller.listCrawlers();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(crawlers, response.getBody());
    }

    @Test
    void startCrawl_success_returnsOk() {
        CrawlConfig config = mock(CrawlConfig.class);
        CrawlJob job = mock(CrawlJob.class);
        CrawlProgress progress = mock(CrawlProgress.class);
        when(config.getCrawlerId()).thenReturn("web");
        when(config.getSeed()).thenReturn("https://example.com");
        when(config.getPipelines()).thenReturn(null);
        when(config.getRouteRules()).thenReturn(null);
        when(crawlerService.startCrawl(eq(config), any())).thenReturn(job);
        when(job.getJobId()).thenReturn("job-1");
        when(job.getStatus()).thenReturn(ai.kompile.core.crawler.CrawlStatus.RUNNING);

        ResponseEntity<?> response = controller.startCrawl(config);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void startCrawl_illegalArgument_returnsBadRequest() {
        CrawlConfig config = mock(CrawlConfig.class);
        when(crawlerService.startCrawl(eq(config), any()))
                .thenThrow(new IllegalArgumentException("invalid seed"));

        ResponseEntity<?> response = controller.startCrawl(config);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void validateConfig_validConfig_returnsValid() {
        CrawlConfig config = mock(CrawlConfig.class);
        when(config.getSeed()).thenReturn("https://example.com");
        when(config.getMaxDepth()).thenReturn(2);
        when(config.getRouteRules()).thenReturn(null);
        when(config.getPipelines()).thenReturn(null);

        ResponseEntity<?> response = controller.validateConfig(config);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(true, body.get("valid"));
    }

    @Test
    void validateConfig_missingSeed_returnsInvalid() {
        CrawlConfig config = mock(CrawlConfig.class);
        when(config.getSeed()).thenReturn(null);
        when(config.getMaxDepth()).thenReturn(2);
        when(config.getRouteRules()).thenReturn(null);
        when(config.getPipelines()).thenReturn(null);

        ResponseEntity<?> response = controller.validateConfig(config);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(false, body.get("valid"));
    }

    @Test
    void listJobs_returnsOk() {
        when(crawlerService.getAllJobs()).thenReturn(List.of());

        ResponseEntity<List<Map<String, Object>>> response = controller.listJobs();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void listActiveJobs_returnsOk() {
        when(crawlerService.getActiveJobs()).thenReturn(List.of());

        ResponseEntity<List<Map<String, Object>>> response = controller.listActiveJobs();

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void getJob_notFound_returnsNotFound() {
        when(crawlerService.getJob("missing")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getJob("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void pauseJob_success_returnsOk() {
        when(crawlerService.pauseJob("job-1")).thenReturn(true);

        ResponseEntity<?> response = controller.pauseJob("job-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void pauseJob_notFound_returnsBadRequest() {
        when(crawlerService.pauseJob("missing")).thenReturn(false);

        ResponseEntity<?> response = controller.pauseJob("missing");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void resumeJob_success_returnsOk() {
        when(crawlerService.resumeJob("job-1")).thenReturn(true);

        ResponseEntity<?> response = controller.resumeJob("job-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void cancelJob_success_returnsOk() {
        when(crawlerService.cancelJob("job-1")).thenReturn(true);

        ResponseEntity<?> response = controller.cancelJob("job-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void cleanupJobs_returnsOk() {
        when(crawlerService.cleanupJobs()).thenReturn(3);

        ResponseEntity<?> response = controller.cleanupJobs();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(3, body.get("removed"));
    }
}
