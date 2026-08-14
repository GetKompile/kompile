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

/**
 * Unit tests for {@link AskGraphRetractTool}.
 *
 * <p>Mirrors the patterns in {@link AskGraphToolsTest} — HTTP calls are intercepted
 * via Spring's {@code MockRestServiceServer}, no real server runs.</p>
 */
@DisplayName("AskGraphRetractTool")
class AskGraphRetractToolTest {

    @TempDir
    Path tempDir;

    private ObjectMapper om;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        om = new ObjectMapper();
        AgentConfig agent = AgentConfig.builder("coder").enabledTools(Set.of("*")).build();
        PermissionService perms = new PermissionService();
        perms.setUserOverride("ask_graph_retract", PermissionService.PermissionLevel.ALLOW);
        ToolRegistry registry = new ToolRegistry(om);
        ctx = new ToolContext("test-session", agent, perms, tempDir, registry);
    }

    // ── Metadata ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("metadata")
    class Metadata {

        @Test
        @DisplayName("id is ask_graph_retract")
        void idIsCorrect() {
            AskGraphRetractTool tool = new AskGraphRetractTool((String) null, om);
            assertEquals("ask_graph_retract", tool.id());
        }

        @Test
        @DisplayName("permissionKey matches id")
        void permissionKeyMatchesId() {
            AskGraphRetractTool tool = new AskGraphRetractTool((String) null, om);
            assertEquals("ask_graph_retract", tool.permissionKey());
        }

        @Test
        @DisplayName("annotated WRITE (retraction is a mutation)")
        void annotationsAreWrite() {
            AskGraphRetractTool tool = new AskGraphRetractTool((String) null, om);
            assertEquals(McpToolAnnotations.WRITE, tool.mcpAnnotations());
        }

        @Test
        @DisplayName("parameterSchema has only atomKey as required (factSheetId is now optional)")
        void schemaHasRequiredFields() {
            AskGraphRetractTool tool = new AskGraphRetractTool((String) null, om);
            String required = tool.parameterSchema().path("required").toString();
            assertTrue(required.contains("atomKey"), "atomKey must be required");
            assertFalse(required.contains("factSheetId"),
                    "factSheetId must NOT be required — it is optional; omit to use the active sheet");
        }

        @Test
        @DisplayName("parameterSchema exposes mode field")
        void schemaExposesMode() {
            AskGraphRetractTool tool = new AskGraphRetractTool((String) null, om);
            assertFalse(tool.parameterSchema().path("properties").path("mode").isMissingNode(),
                    "mode param must be in schema");
        }

        @Test
        @DisplayName("description mentions TMS and cascade")
        void descriptionMentionsTmsAndCascade() {
            AskGraphRetractTool tool = new AskGraphRetractTool((String) null, om);
            String desc = tool.description().toLowerCase();
            assertTrue(desc.contains("tms") || desc.contains("retract"),
                    "description should mention TMS or retract");
            assertTrue(desc.contains("cascade"),
                    "description should mention cascade");
        }
    }

    // ── Guard clauses ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("input guards")
    class Guards {

        private AskGraphRetractTool tool;

        @BeforeEach
        void setUp() {
            tool = new AskGraphRetractTool((String) null, om); // null baseUrl → unavailable
        }

        @Test
        @DisplayName("missing atomKey returns error mentioning atomKey")
        void missingAtomKey_returnsError() throws Exception {
            ObjectNode params = om.createObjectNode();
            params.put("factSheetId", 42);
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError(), "Expected error when atomKey missing");
            assertTrue(result.getOutput().contains("atomKey"));
        }

        @Test
        @DisplayName("omitting factSheetId is not an error (resolves active sheet server-side)")
        void missingFactSheetId_notAnError() throws Exception {
            // factSheetId is now optional — the backend resolves the active sheet when omitted.
            // With null baseUrl the tool is unavailable, so the error is about kompile-app, not factSheetId.
            ObjectNode params = om.createObjectNode();
            params.put("atomKey", "foo(X)");
            ToolResult result = tool.execute(params, ctx);
            // The error (if any) must be about backend unavailability, NOT about missing factSheetId.
            if (result.isError()) {
                assertFalse(result.getOutput().contains("factSheetId"),
                        "factSheetId is optional — error must not blame it being absent");
            }
        }

        @Test
        @DisplayName("missing project-local graph returns descriptive error")
        void missingProjectLocalGraph_returnsError() throws Exception {
            ObjectNode params = om.createObjectNode();
            params.put("atomKey", "trusts(A, B)");
            params.put("factSheetId", 5);
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError());
            assertTrue(result.getOutput().contains("project-local"), result.getOutput());
            assertFalse(result.getOutput().contains("kompile-app"));
        }
    }

    // ── Live mock round-trip ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("backend round-trip via MockRestServiceServer")
    class RoundTrip {

        @Test
        @DisplayName("RETRACTED response populates metadata correctly")
        void retractedResponse_populatesMetadata() throws Exception {
            RestTemplate rt = new RestTemplate();
            MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
            GroundingBackendClient client = new GroundingBackendClient("http://localhost", rt);
            AskGraphRetractTool tool = new AskGraphRetractTool(client, om);

            String responseJson = """
                    {
                      "status": "RETRACTED",
                      "atomKey": "trusts(Alice, Bob)",
                      "mode": "retract",
                      "dependentAtomsUnsupported": ["derived(Alice)"],
                      "dependentAtomsWeakened": [],
                      "cascadeTriggered": true,
                      "meta": {"stale": true, "factSheetId": 42}
                    }
                    """;

            mockServer.expect(requestTo("http://localhost/api/kb-grounding/retract"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

            ObjectNode params = om.createObjectNode();
            params.put("atomKey", "trusts(Alice, Bob)");
            params.put("factSheetId", 42);

            ToolResult result = tool.execute(params, ctx);

            assertFalse(result.isError(), result.getOutput());
            assertEquals("RETRACTED", result.getMetadata().get("status"));
            assertEquals("retract", result.getMetadata().get("mode"));
            assertEquals(1, result.getMetadata().get("unsupportedCount"));
            assertEquals(0, result.getMetadata().get("weakenedCount"));
            assertTrue((Boolean) result.getMetadata().get("cascadeTriggered"));
            assertTrue(result.getOutput().contains("trusts(Alice, Bob)"),
                    "output must mention the retracted atom");
            assertTrue(result.getOutput().contains("derived(Alice)"),
                    "output must list unsupported dependents");

            mockServer.verify();
        }

        @Test
        @DisplayName("NOT_FOUND response is reported without error")
        void notFoundResponse_reportedWithoutError() throws Exception {
            RestTemplate rt = new RestTemplate();
            MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
            GroundingBackendClient client = new GroundingBackendClient("http://localhost", rt);
            AskGraphRetractTool tool = new AskGraphRetractTool(client, om);

            String responseJson = """
                    {
                      "status": "NOT_FOUND",
                      "atomKey": "absent(X)",
                      "mode": "retract",
                      "dependentAtomsUnsupported": [],
                      "dependentAtomsWeakened": [],
                      "cascadeTriggered": true,
                      "meta": {}
                    }
                    """;

            mockServer.expect(requestTo("http://localhost/api/kb-grounding/retract"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

            ObjectNode params = om.createObjectNode();
            params.put("atomKey", "absent(X)");
            params.put("factSheetId", 1);

            ToolResult result = tool.execute(params, ctx);

            assertFalse(result.isError(), result.getOutput());
            assertEquals("NOT_FOUND", result.getMetadata().get("status"));
            assertEquals(0, result.getMetadata().get("unsupportedCount"));

            mockServer.verify();
        }

        @Test
        @DisplayName("mode=revise is forwarded in the request body")
        void reviseMode_forwardedInBody() throws Exception {
            RestTemplate rt = new RestTemplate();
            MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
            GroundingBackendClient client = new GroundingBackendClient("http://localhost", rt);
            AskGraphRetractTool tool = new AskGraphRetractTool(client, om);

            String[] capturedBody = {null};
            String responseJson = """
                    {"status":"RETRACTED","atomKey":"foo(X)","mode":"revise",
                     "dependentAtomsUnsupported":[],"dependentAtomsWeakened":[],
                     "cascadeTriggered":true,"meta":{}}
                    """;

            mockServer.expect(requestTo("http://localhost/api/kb-grounding/retract"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(request -> capturedBody[0] =
                            ((org.springframework.mock.http.client.MockClientHttpRequest) request)
                                    .getBodyAsString())
                    .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

            ObjectNode params = om.createObjectNode();
            params.put("atomKey", "foo(X)");
            params.put("factSheetId", 7);
            params.put("mode", "revise");

            ToolResult result = tool.execute(params, ctx);

            assertFalse(result.isError(), result.getOutput());
            assertTrue(capturedBody[0].contains("\"mode\":\"revise\""),
                    "request body must include mode=revise: " + capturedBody[0]);
            mockServer.verify();
        }
    }
}
