/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.web.controllers;

import ai.kompile.agent.graph.AgentInstance;
import ai.kompile.agent.graph.AgentInstanceStore;
import ai.kompile.agent.graph.AgentPrincipal;
import ai.kompile.agent.graph.LocalOwnerIdentity;
import ai.kompile.app.services.AgentProvisioningService;
import ai.kompile.app.web.security.IntegrationControlCredentials;
import ai.kompile.app.web.security.IntegrationControlSecurityFilter;
import ai.kompile.channel.api.ChannelControlHeaders;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AgentProvisioningControllerTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final UUID LOCAL_OWNER =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    @TempDir
    Path tempDir;

    private AgentInstanceStore store;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        store = new AgentInstanceStore(tempDir.resolve("agents"));
        AgentProvisioningService service = new AgentProvisioningService(
                store, new LocalOwnerIdentity(LOCAL_OWNER));
        IntegrationControlCredentials credentials =
                new IntegrationControlCredentials(TOKEN, tempDir.resolve("security").toString());
        mvc = standaloneSetup(new AgentProvisioningController(service))
                .addFilters(new IntegrationControlSecurityFilter(credentials))
                .build();
    }

    @Test
    void createListAndGetReturnSecretFreeManifestAndCurrentGraphRevision() throws Exception {
        String createdBody = mvc.perform(authenticatedPost("{\"displayName\":\"Hermes\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(
                        "/api/kclaw/instances/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.displayName").value("Hermes"))
                .andExpect(jsonPath("$.manifestVersion").value(1))
                .andExpect(jsonPath("$.manifestRevision").value(1))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.graphRevision").value(matchesPattern("[0-9a-f]{64}")))
                .andExpect(content().string(not(containsString(LOCAL_OWNER.toString()))))
                .andExpect(content().string(not(containsString("ownerId"))))
                .andExpect(content().string(not(containsString("storagePath"))))
                .andExpect(content().string(not(containsString("secret"))))
                .andReturn().getResponse().getContentAsString();
        String agentId = JsonMapper.builder().build().readTree(createdBody).get("agentId").asText();
        String expectedRevision = store.open(
                new AgentPrincipal(LOCAL_OWNER, UUID.fromString(agentId)))
                .currentRevision().sha256();

        mvc.perform(get("/api/kclaw/instances").header(ChannelControlHeaders.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].agentId").value(agentId))
                .andExpect(jsonPath("$[0].graphRevision").value(expectedRevision));
        mvc.perform(get("/api/kclaw/instances/{agentId}", agentId)
                        .header(ChannelControlHeaders.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(agentId))
                .andExpect(jsonPath("$.graphRevision").value(expectedRevision));
    }

    @Test
    void ownerScopeAlwaysComesFromTheServerIdentity() throws Exception {
        UUID foreignOwner = UUID.fromString("22222222-2222-4222-8222-222222222222");
        AgentInstance foreign = store.provision(foreignOwner, "Foreign");
        String localBody = mvc.perform(authenticatedPost("{\"displayName\":\"Local\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String localId = JsonMapper.builder().build().readTree(localBody).get("agentId").asText();

        mvc.perform(get("/api/kclaw/instances")
                        .header(ChannelControlHeaders.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].agentId").value(localId))
                .andExpect(content().string(not(containsString(
                        foreign.principal().agentId().toString()))));
        mvc.perform(get("/api/kclaw/instances/{agentId}", foreign.principal().agentId())
                        .header(ChannelControlHeaders.TOKEN_HEADER, TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("agent_not_found"));
    }

    @Test
    void strictCreateDtoRejectsAllClientControlledScopeAndGraphSelectors() throws Exception {
        mvc.perform(authenticatedPost("""
                        {"displayName":"Forged",
                         "ownerId":"33333333-3333-4333-8333-333333333333",
                         "graphPath":"/tmp/foreign.kgraph",
                         "factSheetId":7,
                         "knowledgeBase":"foreign"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));

        mvc.perform(get("/api/kclaw/instances")
                        .header(ChannelControlHeaders.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void rejectsInvalidOrNonCanonicalAgentIdsAndReturns404ForUnknownCanonicalUuid()
            throws Exception {
        mvc.perform(get("/api/kclaw/instances/not-a-uuid")
                        .header(ChannelControlHeaders.TOKEN_HEADER, TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_agent_id"));
        mvc.perform(get("/api/kclaw/instances/ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF")
                        .header(ChannelControlHeaders.TOKEN_HEADER, TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_agent_id"));
        mvc.perform(get("/api/kclaw/instances/abcdefab-cdef-4abc-8def-abcdefabcdef")
                        .header(ChannelControlHeaders.TOKEN_HEADER, TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("agent_not_found"));
    }

    @Test
    void exactProvisioningPathRequiresBearerAndMutationProofBeforeControllerRuns()
            throws Exception {
        mvc.perform(get("/api/kclaw/instances"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/kclaw/instances")
                        .header(ChannelControlHeaders.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk());

        String body = "{\"displayName\":\"Protected\"}";
        mvc.perform(post("/api/kclaw/instances")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/kclaw/instances")
                        .header(ChannelControlHeaders.TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(authenticatedPost(body))
                .andExpect(status().isCreated());
    }

    @Test
    void rejectsBlankDisplayNameAsBadRequest() throws Exception {
        mvc.perform(authenticatedPost("{\"displayName\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticatedPost(
            String body) {
        return post("/api/kclaw/instances")
                .header(ChannelControlHeaders.TOKEN_HEADER, TOKEN)
                .header(ChannelControlHeaders.REQUEST_HEADER, "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
