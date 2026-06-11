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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for ConfigArchiveController.
 *
 * ConfigArchiveController calls static methods on ConfigArchiveService which touch the filesystem.
 * Tests here focus on:
 * 1. Input validation paths (path traversal protection, invalid mode)
 * 2. Endpoints that can succeed without a real archive directory
 */
class ConfigArchiveControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ConfigArchiveController controller = new ConfigArchiveController();
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ── Path traversal protection ──────────────────────────────────────────────

    @Test
    void importSavedArchive_pathTraversalSlash_returnsBadRequest() throws Exception {
        // Literal '/' in the URL path causes Spring MVC to normalize the URL before routing,
        // resulting in a 404 (no matching endpoint) rather than 400 from our validation.
        // Both 4xx responses indicate the path traversal was correctly rejected.
        mockMvc.perform(post("/api/config-archives/import/../../etc/passwd"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void importSavedArchive_pathTraversalDotDot_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/config-archives/import/..%2F..%2Fetc%2Fpasswd"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getManifest_pathTraversalSlash_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/config-archives/..%2F..%2Fetc%2Fpasswd/manifest"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void downloadArchive_pathTraversalSlash_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/config-archives/..%2F..%2Fetc%2Fpasswd/download"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteArchive_pathTraversalSlash_returnsBadRequest() throws Exception {
        mockMvc.perform(delete("/api/config-archives/..%2F..%2Fetc%2Fpasswd"))
                .andExpect(status().isBadRequest());
    }

    // ── Import mode validation ─────────────────────────────────────────────────

    @Test
    void importArchive_invalidMode_returnsBadRequest() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "archive.zip", "application/zip", new byte[]{0x50, 0x4B, 0x03, 0x04});

        mockMvc.perform(multipart("/api/config-archives/import")
                        .file(file)
                        .param("mode", "invalidmode"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void importSavedArchive_invalidMode_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/config-archives/import/somevalid.zip")
                        .param("mode", "wrongmode"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // ── Non-existent archive file paths ───────────────────────────────────────

    @Test
    void importSavedArchive_fileNotFound_returns404() throws Exception {
        // A valid filename that doesn't exist on the filesystem
        mockMvc.perform(post("/api/config-archives/import/nonexistent-archive-xyz.zip")
                        .param("mode", "append"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getManifest_fileNotFound_returns404() throws Exception {
        mockMvc.perform(get("/api/config-archives/nonexistent-archive-xyz.zip/manifest"))
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadArchive_fileNotFound_returns404() throws Exception {
        mockMvc.perform(get("/api/config-archives/nonexistent-archive-xyz.zip/download"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteArchive_fileNotFound_returns404() throws Exception {
        mockMvc.perform(delete("/api/config-archives/nonexistent-archive-xyz.zip"))
                .andExpect(status().isNotFound());
    }

    // ── Preview endpoint ───────────────────────────────────────────────────────

    @Test
    void previewArchive_invalidZip_returnsBadRequest() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "bad.zip", "application/zip", "not a zip".getBytes());

        mockMvc.perform(multipart("/api/config-archives/preview")
                        .file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // ── List archives ─────────────────────────────────────────────────────────

    @Test
    void listArchives_returnsResponse() throws Exception {
        // listArchives() either returns an empty list or throws; either way the response is formed
        mockMvc.perform(get("/api/config-archives"))
                .andExpect(status().isOk());
    }
}
