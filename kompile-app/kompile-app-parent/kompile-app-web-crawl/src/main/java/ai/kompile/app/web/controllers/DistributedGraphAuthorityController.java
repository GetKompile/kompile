/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.app.web.controllers;

import ai.kompile.app.config.ResourceSchedulerConfig;
import ai.kompile.app.services.crawl.DistributedCrawlCoordinator;
import ai.kompile.app.services.crawl.DistributedCrawlSession;
import ai.kompile.app.services.subprocess.GraphMatrixSubprocessLauncher;
import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** Authenticated, fixed-target gateway from distributed crawl workers to the crawl manager graph child. */
@RestController
@ConditionalOnBean(DistributedCrawlCoordinator.class)
@RequestMapping("/api/internal/distributed-graph")
public class DistributedGraphAuthorityController {

    public static final String LEASE_HEADER = "X-Kompile-Graph-Lease";
    public static final String SESSION_HEADER = "X-Kompile-Session-Id";
    public static final String PARTITION_HEADER = "X-Kompile-Partition-Id";
    public static final String ATTEMPT_HEADER = "X-Kompile-Attempt";
    private static final int MAX_REQUEST_BYTES = 8 * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private static final Set<String> LIFECYCLE_METHODS = Set.of(
            "supportsGraphGenerations", "beginFactSheetGeneration",
            "validateFactSheetGeneration", "activateFactSheetGeneration",
            "abortFactSheetGeneration", "rollbackFactSheetGeneration",
            "getFactSheetGenerationStatus");
    private static final Set<String> READ_ONLY_METHODS = Set.of(
            "getNode", "getNodeByExternalId", "getNodesByIds", "getNodesByType",
            "getNodesByTypeInFactSheet", "getNodesInFactSheet", "getAllNodes", "getAllSources",
            "getChildren", "getNeighbors", "getEdges", "getEdgesInFactSheet", "edgeExists",
            "countEntityNodesInFactSheet", "countNodesByTypeInFactSheet", "searchNodes",
            "getGraphStatistics", "loadGraph", "listGraphs", "listGraphsByFactSheet",
            "answerQuery", "queryMebnFromKg", "queryAllPosteriors", "queryPosterior");

    private final DistributedCrawlCoordinator coordinator;
    private final GraphMatrixSubprocessLauncher graphLauncher;
    private final ResourceSchedulerConfigService configService;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    @Autowired
    public DistributedGraphAuthorityController(
            DistributedCrawlCoordinator coordinator,
            GraphMatrixSubprocessLauncher graphLauncher,
            ResourceSchedulerConfigService configService,
            ObjectMapper mapper) {
        this(coordinator, graphLauncher, configService, mapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    DistributedGraphAuthorityController(
            DistributedCrawlCoordinator coordinator,
            GraphMatrixSubprocessLauncher graphLauncher,
            ResourceSchedulerConfigService configService,
            ObjectMapper mapper,
            HttpClient httpClient) {
        this.coordinator = coordinator;
        this.graphLauncher = graphLauncher;
        this.configService = configService;
        this.mapper = mapper;
        this.httpClient = httpClient;
    }

    @GetMapping("/capabilities")
    public ResponseEntity<?> capabilities(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        ResponseEntity<?> rejection = authenticate(authorization);
        if (rejection != null) return rejection;
        return ResponseEntity.ok(Map.of(
                "protocolVersion", 2,
                "distributedWriterProtocolVersion", 1,
                "writerFencing", true,
                "generationAuthority", true));
    }

    @PostMapping(value = "/invoke", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> invoke(
            @RequestBody byte[] body,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = LEASE_HEADER, required = false) String lease,
            @RequestHeader(value = SESSION_HEADER, required = false) String sessionId,
            @RequestHeader(value = PARTITION_HEADER, required = false) String partitionId,
            @RequestHeader(value = ATTEMPT_HEADER, required = false) String attemptHeader) {
        ResponseEntity<?> rejection = authenticate(authorization);
        if (rejection != null) return rejection;
        if (body == null || body.length > MAX_REQUEST_BYTES) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "REQUEST_TOO_LARGE", "graph request exceeds byte limit");
        }
        int attempt;
        try {
            attempt = Integer.parseInt(attemptHeader);
        } catch (Exception invalid) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_ATTEMPT", "positive attempt header is required");
        }
        JsonNode request;
        try {
            request = mapper.readTree(body);
        } catch (IOException invalidJson) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_JSON", "invalid graph RPC JSON");
        }
        String method = request.path("method").asText("");
        if (LIFECYCLE_METHODS.contains(method)) {
            return error(HttpStatus.FORBIDDEN, "REMOTE_LIFECYCLE_FORBIDDEN",
                    "distributed workers cannot invoke graph generation lifecycle methods");
        }
        DistributedCrawlSession session = coordinator.getSession(sessionId).orElse(null);
        UnifiedCrawlJob.GraphGenerationSnapshot expected = session != null
                ? session.getGraphGeneration() : null;
        JsonNode generation = request.path("generation");
        if (expected == null || generation.isMissingNode() || generation.isNull()
                || !expected.logicalGraphId().equals(generation.path("logicalGraphId").asText())
                || !expected.physicalGraphId().equals(generation.path("physicalGraphId").asText())
                || !expected.generationId().equals(generation.path("generationId").asText())
                || expected.factSheetId() != generation.path("factSheetId").asLong(-1L)
                || !("distributed:" + sessionId).equals(
                request.path("generationOwnerJobId").asText())) {
            return error(HttpStatus.FORBIDDEN, "GENERATION_MISMATCH",
                    "writer lease is not bound to the supplied generation");
        }
        DistributedCrawlCoordinator.WriterLeaseVerdict verdict =
                coordinator.validateWriterLease(sessionId, partitionId, attempt, lease,
                        !READ_ONLY_METHODS.contains(method));
        if (verdict != DistributedCrawlCoordinator.WriterLeaseVerdict.VALID) {
            return leaseError(verdict);
        }
        try {
            HttpRequest childRequest = HttpRequest.newBuilder(
                            URI.create(graphLauncher.baseUrl() + "/invoke"))
                    .timeout(Duration.ofSeconds(120))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<InputStream> child = httpClient.send(
                    childRequest, HttpResponse.BodyHandlers.ofInputStream());
            byte[] response;
            try (InputStream input = child.body()) {
                response = readBounded(input, MAX_RESPONSE_BYTES);
            }
            return ResponseEntity.status(child.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return error(HttpStatus.SERVICE_UNAVAILABLE, "AUTHORITY_INTERRUPTED", "graph authority interrupted");
        } catch (Exception failure) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "AUTHORITY_UNAVAILABLE", failure.getMessage());
        }
    }

    private ResponseEntity<?> authenticate(String authorization) {
        ResourceSchedulerConfig config = configService != null ? configService.getConfiguration() : null;
        String token = config != null ? config.getExternalAuthToken() : null;
        if (token == null || token.isBlank()) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "GATEWAY_DISABLED",
                    "distributed graph gateway requires a nonblank cluster token");
        }
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        byte[] supplied = authorization == null ? new byte[0] : authorization.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, supplied) ? null
                : error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "invalid cluster bearer token");
    }

    private static ResponseEntity<?> leaseError(DistributedCrawlCoordinator.WriterLeaseVerdict verdict) {
        return switch (verdict) {
            case EXPIRED -> error(HttpStatus.GONE, "LEASE_EXPIRED", "partition writer lease expired");
            case STALE_ATTEMPT, REVOKED -> error(HttpStatus.CONFLICT, "WRITER_FENCED", verdict.name());
            case UNKNOWN_SESSION, UNKNOWN_PARTITION -> error(HttpStatus.NOT_FOUND, verdict.name(), verdict.name());
            case INVALID_TOKEN -> error(HttpStatus.FORBIDDEN, "INVALID_LEASE", "invalid partition writer lease");
            case VALID -> throw new IllegalStateException("valid lease cannot be an error");
        };
    }

    private static ResponseEntity<Map<String, Object>> error(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "ok", false, "code", code,
                "error", message == null ? code : message));
    }

    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            total += count;
            if (total > limit) throw new IOException("graph response exceeds byte limit");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
