/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.cli.main.chat.tools.grounding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * An HTTP error status from the grounding backend is a value the tools can report, not an
 * exception that hides the server's explanation. Only transport failures throw.
 */
class GroundingBackendClientTest {

    private MockRestServiceServer server;
    private GroundingBackendClient client;

    @BeforeEach
    void setUp() {
        RestTemplate rt = new RestTemplate();
        server = MockRestServiceServer.createServer(rt);
        client = new GroundingBackendClient("http://localhost", rt);
    }

    @Test
    void postErrorStatusComesBackWithItsUtf8Body() {
        // application/json without a charset: Spring's own fallback would read ISO-8859-1.
        String body = "{\"message\":\"Unbekanntes Prädikat „arbeitetFür“ — ∀x\"}";
        server.expect(requestTo("http://localhost/api/kb-grounding/verify"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body(body)
                        .contentType(MediaType.APPLICATION_JSON));

        GroundingBackendClient.GroundingResponse resp = client.post("/api/kb-grounding/verify", "{}");

        assertEquals(400, resp.statusCode());
        assertEquals(body, resp.body());
        server.verify();
    }

    @Test
    void timedPostServerErrorComesBackAsAValue() {
        server.expect(requestTo("http://localhost/api/unified-crawl/single-source"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError()
                        .body("{\"error\":\"queue full\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        GroundingBackendClient.GroundingResponse resp =
                client.post("/api/unified-crawl/single-source", "{}", Duration.ofSeconds(5));

        assertEquals(500, resp.statusCode());
        assertEquals("{\"error\":\"queue full\"}", resp.body());
        server.verify();
    }

    @Test
    void getAndDeleteErrorStatusesComeBackAsValues() {
        server.expect(requestTo("http://localhost/api/unified-crawl/jobs/j-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://localhost/api/unified-crawl/jobs/j-2"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .body("{\"message\":\"job is still running\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        GroundingBackendClient.GroundingResponse missing = client.get("/api/unified-crawl/jobs/j-1");
        GroundingBackendClient.GroundingResponse busy = client.delete("/api/unified-crawl/jobs/j-2");

        assertEquals(404, missing.statusCode());
        assertEquals("", missing.body());
        assertEquals(409, busy.statusCode());
        assertEquals("{\"message\":\"job is still running\"}", busy.body());
        server.verify();
    }

    @Test
    void binaryDownloadTellsContentFromAnErrorBody() {
        byte[] graph = new byte[]{1, 2, 3};
        server.expect(requestTo("http://localhost/api/graph/unified/export?format=kgraph"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(graph, MediaType.APPLICATION_OCTET_STREAM));
        server.expect(requestTo("http://localhost/api/graph/unified/export?format=kgraph"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError()
                        .body("{\"message\":\"graph store unavailable\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        GroundingBackendClient.BinaryResponse ok = client.getBytes("/api/graph/unified/export?format=kgraph");
        GroundingBackendClient.BinaryResponse failed = client.getBytes("/api/graph/unified/export?format=kgraph");

        assertTrue(ok.successful());
        assertArrayEquals(graph, ok.body());
        assertFalse(failed.successful());
        assertEquals(500, failed.statusCode());
        assertEquals("{\"message\":\"graph store unavailable\"}", failed.bodyText());
        server.verify();
    }

    @Test
    void transportFailureStillThrows() {
        server.expect(requestTo("http://localhost/api/kb-grounding/verify"))
                .andRespond(withException(new IOException("Connection refused")));

        assertThrows(ResourceAccessException.class, () -> client.post("/api/kb-grounding/verify", "{}"));
    }

    @Test
    void errorMessagePrefersMessageThenErrorThenTheBody() {
        assertEquals("Unknown step X",
                GroundingBackendClient.errorMessage("{\"error\":\"Bad request\",\"message\":\"Unknown step X\"}"));
        assertEquals("queue full", GroundingBackendClient.errorMessage("{\"error\":\"queue full\"}"));
        assertEquals("fallback",
                GroundingBackendClient.errorMessage("{\"message\":null,\"error\":\"fallback\"}"));
        assertEquals("fallback",
                GroundingBackendClient.errorMessage("{\"message\":\"  \",\"error\":\"fallback\"}"));
        assertEquals("{\"status\":500}", GroundingBackendClient.errorMessage("{\"status\":500}"));
        assertEquals("export worker crashed", GroundingBackendClient.errorMessage("export worker crashed"));
        assertEquals("x".repeat(200) + "...", GroundingBackendClient.errorMessage("x".repeat(250)));
        assertEquals("", GroundingBackendClient.errorMessage(null));
        assertEquals("", GroundingBackendClient.errorMessage("   "));
    }
}
