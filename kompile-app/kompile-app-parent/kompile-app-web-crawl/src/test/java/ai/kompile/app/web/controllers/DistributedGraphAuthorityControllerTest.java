/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.app.web.controllers;

import ai.kompile.app.config.ResourceSchedulerConfig;
import ai.kompile.app.services.crawl.DistributedCrawlCoordinator;
import ai.kompile.app.services.crawl.DistributedCrawlSession;
import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import ai.kompile.app.services.subprocess.GraphMatrixSubprocessLauncher;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DistributedGraphAuthorityControllerTest {

    private HttpServer child;
    private DistributedCrawlCoordinator coordinator;
    private ResourceSchedulerConfig config;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        child = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        child.createContext("/invoke", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"ok\":true,\"result\":{\"nodeId\":\"n1\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        child.start();
        coordinator = mock(DistributedCrawlCoordinator.class);
        DistributedCrawlSession session = DistributedCrawlSession.builder()
                .sessionId("s1")
                .graphGeneration(new UnifiedCrawlJob.GraphGenerationSnapshot(
                        7L, "factsheet_7", "factsheet_7~gen~g1", "g1",
                        "factsheet_7", 0L, "BUILDING", null))
                .build();
        when(coordinator.getSession("s1")).thenReturn(Optional.of(session));
        GraphMatrixSubprocessLauncher launcher = mock(GraphMatrixSubprocessLauncher.class);
        when(launcher.baseUrl()).thenReturn("http://127.0.0.1:" + child.getAddress().getPort());
        ResourceSchedulerConfigService configService = mock(ResourceSchedulerConfigService.class);
        config = new ResourceSchedulerConfig();
        when(configService.getConfiguration()).thenReturn(config);
        DistributedGraphAuthorityController controller = new DistributedGraphAuthorityController(
                coordinator, launcher, configService, new ObjectMapper());
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() {
        if (child != null) child.stop(0);
    }

    @Test
    void blankTokenDisablesGatewayAndBadBearerIsRejected() throws Exception {
        mvc.perform(get("/api/internal/distributed-graph/capabilities"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GATEWAY_DISABLED"));
        config.setExternalAuthToken("secret");
        mvc.perform(get("/api/internal/distributed-graph/capabilities")
                        .header("Authorization", "Bearer wrong"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/internal/distributed-graph/capabilities")
                        .header("Authorization", "Bearer secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distributedWriterProtocolVersion").value(1));
    }

    @Test
    void validLeaseForwardsOnlyToFixedChild() throws Exception {
        config.setExternalAuthToken("secret");
        when(coordinator.validateWriterLease("s1", "p1", 2, "lease", false))
                .thenReturn(DistributedCrawlCoordinator.WriterLeaseVerdict.VALID);

        mvc.perform(post("/api/internal/distributed-graph/invoke")
                        .header("Authorization", "Bearer secret")
                        .header(DistributedGraphAuthorityController.SESSION_HEADER, "s1")
                        .header(DistributedGraphAuthorityController.PARTITION_HEADER, "p1")
                        .header(DistributedGraphAuthorityController.ATTEMPT_HEADER, "2")
                        .header(DistributedGraphAuthorityController.LEASE_HEADER, "lease")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("getNode")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.nodeId").value("n1"));
    }

    @Test
    void staleWriterAndRemoteLifecycleAreFenced() throws Exception {
        config.setExternalAuthToken("secret");
        when(coordinator.validateWriterLease(anyString(), anyString(), anyInt(), anyString(), anyBoolean()))
                .thenReturn(DistributedCrawlCoordinator.WriterLeaseVerdict.STALE_ATTEMPT);
        mvc.perform(invoke("getNode"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WRITER_FENCED"));

        when(coordinator.validateWriterLease(anyString(), anyString(), anyInt(), anyString(), anyBoolean()))
                .thenReturn(DistributedCrawlCoordinator.WriterLeaseVerdict.VALID);
        mvc.perform(invoke("activateFactSheetGeneration"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REMOTE_LIFECYCLE_FORBIDDEN"));
        verify(coordinator, never()).revokeWriterLeases(anyString());
    }

    @Test
    void leaseCannotBeUsedWithoutItsExactGenerationEnvelope() throws Exception {
        config.setExternalAuthToken("secret");
        mvc.perform(post("/api/internal/distributed-graph/invoke")
                        .header("Authorization", "Bearer secret")
                        .header(DistributedGraphAuthorityController.SESSION_HEADER, "s1")
                        .header(DistributedGraphAuthorityController.PARTITION_HEADER, "p1")
                        .header(DistributedGraphAuthorityController.ATTEMPT_HEADER, "1")
                        .header(DistributedGraphAuthorityController.LEASE_HEADER, "lease")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"service\":\"kg\",\"method\":\"getNode\",\"args\":[]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GENERATION_MISMATCH"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder invoke(String method) {
        return post("/api/internal/distributed-graph/invoke")
                .header("Authorization", "Bearer secret")
                .header(DistributedGraphAuthorityController.SESSION_HEADER, "s1")
                .header(DistributedGraphAuthorityController.PARTITION_HEADER, "p1")
                .header(DistributedGraphAuthorityController.ATTEMPT_HEADER, "1")
                .header(DistributedGraphAuthorityController.LEASE_HEADER, "lease")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(method));
    }

    private static String requestBody(String method) {
        return "{\"service\":\"kg\",\"method\":\"" + method
                + "\",\"generationOwnerJobId\":\"distributed:s1\","
                + "\"generation\":{\"factSheetId\":7,\"logicalGraphId\":\"factsheet_7\","
                + "\"physicalGraphId\":\"factsheet_7~gen~g1\",\"generationId\":\"g1\"},"
                + "\"args\":[]}";
    }
}
