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

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import org.springframework.http.HttpStatus;

/**
 * Unit tests for {@link AskGraphSubscribeTool}.
 *
 * <p>Mirrors the patterns in {@link AskGraphRetractToolTest} — HTTP calls intercepted
 * via {@code MockRestServiceServer}, no real server.</p>
 */
@DisplayName("AskGraphSubscribeTool")
class AskGraphSubscribeToolTest {

    @TempDir
    Path tempDir;

    private ObjectMapper om;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        om = new ObjectMapper();
        AgentConfig agent = AgentConfig.builder("coder").enabledTools(Set.of("*")).build();
        PermissionService perms = new PermissionService();
        perms.setUserOverride("ask_graph_subscribe", PermissionService.PermissionLevel.ALLOW);
        ToolRegistry registry = new ToolRegistry(om);
        ctx = new ToolContext("test-session", agent, perms, tempDir, registry);
    }

    // ── Metadata ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("metadata")
    class Metadata {

        @Test
        @DisplayName("id is ask_graph_subscribe")
        void idIsCorrect() {
            AskGraphSubscribeTool tool = new AskGraphSubscribeTool((String) null, om);
            assertEquals("ask_graph_subscribe", tool.id());
        }

        @Test
        @DisplayName("annotated READ_ONLY")
        void annotationsAreReadOnly() {
            AskGraphSubscribeTool tool = new AskGraphSubscribeTool((String) null, om);
            assertEquals(McpToolAnnotations.READ_ONLY, tool.mcpAnnotations());
        }

        @Test
        @DisplayName("description mentions subscription and cursor")
        void descriptionMentionsKeyTerms() {
            AskGraphSubscribeTool tool = new AskGraphSubscribeTool((String) null, om);
            String desc = tool.description().toLowerCase();
            assertTrue(desc.contains("subscri"), "description should mention subscription");
            assertTrue(desc.contains("cursor"), "description should mention cursor");
        }

        @Test
        @DisplayName("compactHint mentions first call and next calls protocols")
        void compactHintCoversProtocol() {
            AskGraphSubscribeTool tool = new AskGraphSubscribeTool((String) null, om);
            String hint = tool.compactHint().toLowerCase();
            assertTrue(hint.contains("first"), "hint should describe first call");
            assertTrue(hint.contains("cursor"), "hint should mention cursor");
        }

        @Test
        @DisplayName("parameterSchema has subscriptionId and cursor fields")
        void schemaHasSubscriptionFields() {
            AskGraphSubscribeTool tool = new AskGraphSubscribeTool((String) null, om);
            var props = tool.parameterSchema().path("properties");
            assertFalse(props.path("subscriptionId").isMissingNode(), "subscriptionId must be in schema");
            assertFalse(props.path("cursor").isMissingNode(), "cursor must be in schema");
            assertFalse(props.path("waitMs").isMissingNode(), "waitMs must be in schema");
        }
    }

    // ── Guard clauses ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("guard clauses")
    class Guards {

        private AskGraphSubscribeTool tool;

        @BeforeEach
        void setUp() {
            tool = new AskGraphSubscribeTool((String) null, om);
        }

        @Test
        @DisplayName("missing project-local graph is bootstrapped")
        void missingProjectLocalGraph_returnsError() throws Exception {
            ObjectNode params = om.createObjectNode();
            params.putArray("predicates").add("worksFor");
            ToolResult result = tool.execute(params, ctx);
            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("project-local"), result.getOutput());
            assertFalse(result.getOutput().contains("kompile-app"));
        }
    }

    // ── First call: create subscription + snapshot ────────────────────────────────

    @Nested
    @DisplayName("first call (no subscriptionId)")
    class FirstCall {

        @Test
        @DisplayName("missing predicates on first call returns error")
        void missingPredicates_returnsError() throws Exception {
            RestTemplate rt = new RestTemplate();
            GroundingBackendClient client = new GroundingBackendClient("http://localhost", rt);
            AskGraphSubscribeTool tool = new AskGraphSubscribeTool(client, om);

            ObjectNode params = om.createObjectNode(); // no predicates, no subscriptionId
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError());
            assertTrue(result.getOutput().contains("predicates"));
        }

        @Test
        @DisplayName("successful first call returns subscriptionId, nextCursor=0, and snapshot")
        void successfulFirstCall() throws Exception {
            RestTemplate rt = new RestTemplate();
            MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
            GroundingBackendClient client = new GroundingBackendClient("http://localhost", rt);
            AskGraphSubscribeTool tool = new AskGraphSubscribeTool(client, om);

            // Expect POST /subscribe
            String subscribeResp = """
                    {"subscriptionId":"sub-abc-123","eventsUrl":"/api/kb-grounding/subscribe/sub-abc-123/events",
                     "pollUrl":"/api/kb-grounding/subscribe/sub-abc-123/poll",
                     "expiresAt":"2030-01-01T00:00:00Z","message":null}
                    """;
            mockServer.expect(requestTo("http://localhost/api/kb-grounding/subscribe"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess(subscribeResp, MediaType.APPLICATION_JSON));

            // Expect POST /query for the predicate snapshot
            String queryResp = """
                    {"bindings":[{"variables":{"s":"Alice","o":"Acme"},"confidence":0.9}],
                     "total":1,"truncated":false,"meta":{"stale":false}}
                    """;
            mockServer.expect(requestTo("http://localhost/api/kb-grounding/query"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess(queryResp, MediaType.APPLICATION_JSON));

            ObjectNode params = om.createObjectNode();
            params.putArray("predicates").add("worksFor");
            params.put("factSheetId", 42);

            ToolResult result = tool.execute(params, ctx);

            assertFalse(result.isError(), result.getOutput());
            assertEquals("sub-abc-123", result.getMetadata().get("subscriptionId"));
            assertEquals(0, result.getMetadata().get("nextCursor"));
            assertEquals(1, result.getMetadata().get("predicates"));
            assertEquals(1, result.getMetadata().get("totalMatches"));
            assertTrue(result.getOutput().contains("sub-abc-123"),
                    "output must contain subscriptionId");
            assertTrue(result.getOutput().contains("worksFor"),
                    "output must mention the predicate");
            assertTrue(result.getOutput().contains("Alice"),
                    "output must include sample bindings");

            mockServer.verify();
        }

        @Test
        @DisplayName("server error on /subscribe returns tool error")
        void serverErrorOnSubscribe_returnsError() throws Exception {
            RestTemplate rt = new RestTemplate();
            MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
            GroundingBackendClient client = new GroundingBackendClient("http://localhost", rt);
            AskGraphSubscribeTool tool = new AskGraphSubscribeTool(client, om);

            mockServer.expect(requestTo("http://localhost/api/kb-grounding/subscribe"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                            .body("{\"message\":\"service down\"}")
                            .contentType(MediaType.APPLICATION_JSON));

            ObjectNode params = om.createObjectNode();
            params.putArray("predicates").add("trusts");

            ToolResult result = tool.execute(params, ctx);

            assertTrue(result.isError());
            assertTrue(result.getOutput().contains("Failed to create subscription"));

            mockServer.verify();
        }
    }

    // ── Subsequent calls: long-poll drain ─────────────────────────────────────────

    @Nested
    @DisplayName("subsequent calls (with subscriptionId + cursor)")
    class PollCall {

        @Test
        @DisplayName("poll call hits /subscribe/{id}/poll and returns events + nextCursor")
        void pollCallDrainsEvents() throws Exception {
            RestTemplate rt = new RestTemplate();
            MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
            GroundingBackendClient client = new GroundingBackendClient("http://localhost", rt);
            AskGraphSubscribeTool tool = new AskGraphSubscribeTool(client, om);

            String pollResp = """
                    {
                      "events": [
                        {"seq":1,"ts":"2026-07-09T00:00:00Z","type":"asserted",
                         "factSheetId":42,"atomKey":"worksFor(Alice,Acme)","value":0.9,"source":"sess-1"},
                        {"seq":2,"ts":"2026-07-09T00:00:01Z","type":"retracted",
                         "factSheetId":42,"atomKey":"worksFor(Bob,Acme)","value":null,"source":null}
                      ],
                      "nextCursor": 2,
                      "expiresAt": "2030-01-01T00:00:00Z",
                      "overflow": false
                    }
                    """;
            mockServer.expect(requestTo(
                            "http://localhost/api/kb-grounding/subscribe/sub-abc-123/poll?cursor=0&waitMs=10000"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(pollResp, MediaType.APPLICATION_JSON));

            ObjectNode params = om.createObjectNode();
            params.put("subscriptionId", "sub-abc-123");
            params.put("cursor", 0);
            params.put("waitMs", 10000);

            ToolResult result = tool.execute(params, ctx);

            assertFalse(result.isError(), result.getOutput());
            assertEquals("sub-abc-123", result.getMetadata().get("subscriptionId"));
            assertEquals(2L, result.getMetadata().get("nextCursor"));
            assertEquals(2, result.getMetadata().get("eventCount"));
            assertFalse((Boolean) result.getMetadata().get("overflow"));
            assertTrue(result.getOutput().contains("asserted"), "output must mention event types");
            assertTrue(result.getOutput().contains("worksFor(Alice,Acme)"));

            mockServer.verify();
        }

        @Test
        @DisplayName("poll returning 404 reports subscription expired/not found")
        void pollReturns404_reportsExpired() throws Exception {
            RestTemplate rt = new RestTemplate();
            MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
            GroundingBackendClient client = new GroundingBackendClient("http://localhost", rt);
            AskGraphSubscribeTool tool = new AskGraphSubscribeTool(client, om);

            mockServer.expect(requestTo(
                            "http://localhost/api/kb-grounding/subscribe/expired-id/poll?cursor=-1&waitMs=10000"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND)
                            .body("{}")
                            .contentType(MediaType.APPLICATION_JSON));

            ObjectNode params = om.createObjectNode();
            params.put("subscriptionId", "expired-id");

            ToolResult result = tool.execute(params, ctx);

            assertTrue(result.isError());
            assertTrue(result.getOutput().contains("expired") || result.getOutput().contains("not found"),
                    "error must mention expiry or not-found: " + result.getOutput());

            mockServer.verify();
        }

        @Test
        @DisplayName("empty poll (timeout) reports no events and returns same nextCursor")
        void emptyPoll_reportsTimeout() throws Exception {
            RestTemplate rt = new RestTemplate();
            MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
            GroundingBackendClient client = new GroundingBackendClient("http://localhost", rt);
            AskGraphSubscribeTool tool = new AskGraphSubscribeTool(client, om);

            String pollResp = """
                    {"events":[],"nextCursor":5,"expiresAt":"2030-01-01T00:00:00Z","overflow":false}
                    """;
            mockServer.expect(requestTo(
                            "http://localhost/api/kb-grounding/subscribe/sub-xyz/poll?cursor=5&waitMs=500"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(pollResp, MediaType.APPLICATION_JSON));

            ObjectNode params = om.createObjectNode();
            params.put("subscriptionId", "sub-xyz");
            params.put("cursor", 5);
            params.put("waitMs", 500);

            ToolResult result = tool.execute(params, ctx);

            assertFalse(result.isError(), result.getOutput());
            assertEquals(0, result.getMetadata().get("eventCount"));
            assertEquals(5L, result.getMetadata().get("nextCursor"));
            assertTrue(result.getOutput().contains("No new events"));

            mockServer.verify();
        }

        @Test
        @DisplayName("overflow flag is surfaced in output and metadata")
        void overflowFlagSurfaced() throws Exception {
            RestTemplate rt = new RestTemplate();
            MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
            GroundingBackendClient client = new GroundingBackendClient("http://localhost", rt);
            AskGraphSubscribeTool tool = new AskGraphSubscribeTool(client, om);

            String pollResp = """
                    {"events":[{"seq":500,"ts":"2026-07-09T00:00:00Z","type":"changed",
                     "factSheetId":1,"atomKey":null,"value":null,"source":null}],
                     "nextCursor":500,"expiresAt":"2030-01-01T00:00:00Z","overflow":true}
                    """;
            mockServer.expect(requestTo(
                            "http://localhost/api/kb-grounding/subscribe/sub-ov/poll?cursor=-1&waitMs=10000"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(pollResp, MediaType.APPLICATION_JSON));

            ObjectNode params = om.createObjectNode();
            params.put("subscriptionId", "sub-ov");

            ToolResult result = tool.execute(params, ctx);

            assertFalse(result.isError(), result.getOutput());
            assertTrue((Boolean) result.getMetadata().get("overflow"));
            assertTrue(result.getOutput().contains("overflow") || result.getOutput().contains("WARNING"),
                    "output must warn about overflow: " + result.getOutput());

            mockServer.verify();
        }
    }
}
