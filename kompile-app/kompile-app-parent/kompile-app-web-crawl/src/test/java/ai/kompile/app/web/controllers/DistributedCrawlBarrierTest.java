/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.app.web.controllers;

import ai.kompile.app.config.ResourceSchedulerConfig;
import ai.kompile.app.ingest.service.JobLogService;
import ai.kompile.app.services.crawl.DistributedCrawlAggregator;
import ai.kompile.app.services.crawl.DistributedCrawlCoordinator;
import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import ai.kompile.core.crawl.graph.DistributedCrawlPartitionBarrier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DistributedCrawlBarrierTest {

    private DistributedCrawlCoordinator coordinator;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        coordinator = mock(DistributedCrawlCoordinator.class);
        ResourceSchedulerConfig config = new ResourceSchedulerConfig();
        config.setExternalAuthToken("secret");
        ResourceSchedulerConfigService configService = mock(ResourceSchedulerConfigService.class);
        when(configService.getConfiguration()).thenReturn(config);
        DistributedCrawlController controller = new DistributedCrawlController(
                coordinator, mock(DistributedCrawlAggregator.class), configService, mock(JobLogService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void barrierWaitsThenReturnsFinalizerDecision() throws Exception {
        when(coordinator.validateWriterLease("s1", "p1", 1, "lease", false))
                .thenReturn(DistributedCrawlCoordinator.WriterLeaseVerdict.VALID);
        when(coordinator.handlePartitionBarrier("s1", "p1", 1, null))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(DistributedCrawlPartitionBarrier.Decision.RUN_CORPUS_FINALIZATION));
        String body = "{\"sessionId\":\"s1\",\"workerId\":\"p1\",\"attempt\":1}";

        mvc.perform(post("/api/distributed-crawl/barrier")
                        .header("Authorization", "Bearer secret")
                        .header(DistributedGraphAuthorityController.LEASE_HEADER, "lease")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.decision").value("WAIT"));
        mvc.perform(post("/api/distributed-crawl/barrier")
                        .header("Authorization", "Bearer secret")
                        .header(DistributedGraphAuthorityController.LEASE_HEADER, "lease")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("RUN_CORPUS_FINALIZATION"));
    }

    @Test
    void staleAttemptIsFenced() throws Exception {
        when(coordinator.validateWriterLease("s1", "p1", 1, "lease", false))
                .thenReturn(DistributedCrawlCoordinator.WriterLeaseVerdict.STALE_ATTEMPT);
        mvc.perform(post("/api/distributed-crawl/barrier")
                        .header("Authorization", "Bearer secret")
                        .header(DistributedGraphAuthorityController.LEASE_HEADER, "lease")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"s1\",\"workerId\":\"p1\",\"attempt\":1}"))
                .andExpect(status().isConflict());
    }
}
