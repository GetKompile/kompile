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

import ai.kompile.app.services.agent.ChatInsightsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ChatInsightsControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REPORT = "/api/agents/chat/insights";
    private static final String CONFIG = REPORT + "/config";

    private final ChatInsightsService insights = mock(ChatInsightsService.class);
    private final MockMvc mvc = standaloneSetup(new ChatInsightsController(insights)).build();

    @Test
    void aReportIsTheServicesAnswer() throws Exception {
        when(insights.report("crawl", "failed crawls", "/work")).thenReturn(MAPPER.createObjectNode()
                .put("menu", "insights").put("topic", "crawl").put("available", true).put("headline", "Crawls: 2"));
        when(insights.report(null, null, null)).thenReturn(MAPPER.createObjectNode().put("topic", "overview"));

        mvc.perform(get(REPORT).param("topic", "crawl").param("question", "failed crawls")
                        .param("workingDirectory", "/work"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topic").value("crawl"))
                .andExpect(jsonPath("$.headline").value("Crawls: 2"));
        // Without parameters the service answers the overview for the chat's own project.
        mvc.perform(get(REPORT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topic").value("overview"));
    }

    @Test
    void anInvalidRequestIsABadRequestAndAMissingHarnessIsUnavailable() throws Exception {
        when(insights.report(eq("crawls"), any(), any()))
                .thenThrow(new IllegalArgumentException("Unknown insights topic 'crawls'. Topics: crawl, graph"));
        when(insights.report(eq("x"), any(), any())).thenThrow(new IllegalArgumentException());
        when(insights.report(eq("graph"), any(), any()))
                .thenThrow(new IllegalStateException("Kompile CLI harness is unavailable"));

        mvc.perform(get(REPORT).param("topic", "crawls"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value("Unknown insights topic 'crawls'. Topics: crawl, graph"));
        mvc.perform(get(REPORT).param("topic", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid insights request"));
        mvc.perform(get(REPORT).param("topic", "graph"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(status().reason("Kompile CLI harness is unavailable"));
    }

    @Test
    void settingsAreReadAndSavedThroughTheService() throws Exception {
        ObjectNode view = MAPPER.createObjectNode().put("file", "/home/u/.kompile/config/insights.json");
        view.putObject("settings").put("maxRows", 3);
        when(insights.settings()).thenReturn(view);
        when(insights.saveSettings(any())).thenReturn(view);

        mvc.perform(get(CONFIG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.maxRows").value(3));
        mvc.perform(put(CONFIG).contentType(MediaType.APPLICATION_JSON).content("{\"maxRows\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.file").value("/home/u/.kompile/config/insights.json"));
        verify(insights).saveSettings(MAPPER.readTree("{\"maxRows\":3}"));
        // A missing body reaches the service, which says what the settings must be.
        mvc.perform(put(CONFIG)).andExpect(status().isOk());
        verify(insights).saveSettings(null);
    }

    @Test
    void aSettingThatIsInvalidOrCannotBeSavedIsReported() throws Exception {
        when(insights.saveSettings(any()))
                .thenThrow(new IllegalArgumentException("maxRows must be a positive whole number"))
                .thenThrow(new IOException("Read-only file system"));

        mvc.perform(put(CONFIG).contentType(MediaType.APPLICATION_JSON).content("{\"maxRows\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value("maxRows must be a positive whole number"));
        mvc.perform(put(CONFIG).contentType(MediaType.APPLICATION_JSON).content("{\"maxRows\":3}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value("Could not save insights settings: Read-only file system"));
    }
}
