/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.app.web.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SpaEntryRouteTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SpaForwardController()).build();

    @Test
    void rootStillServesTheBrowserApp() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void plainChatPathsRedirectToTheBrowserRoute() throws Exception {
        for (String path : new String[]{"/chat", "/chat/"}) {
            mvc.perform(get(path))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("/#/chat"));
        }
    }

    @Test
    void redirectRespectsDeploymentContextPath() throws Exception {
        mvc.perform(get("/kompile/chat").contextPath("/kompile"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/kompile/#/chat"));
    }

    @Test
    void missingApiAndStaticPathsAreNotMaskedByTheSpa() throws Exception {
        for (String path : new String[]{"/api/missing", "/missing.js"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }
}
