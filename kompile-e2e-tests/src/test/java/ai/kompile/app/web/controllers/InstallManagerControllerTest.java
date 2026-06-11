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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for InstallManagerController. No Spring context or mocks needed
 * since the controller has no injected dependencies.
 */
class InstallManagerControllerTest {

    private InstallManagerController controller;

    @BeforeEach
    void setUp() {
        controller = new InstallManagerController();
    }

    // ─── getTools ─────────────────────────────────────────────────────────────

    @Test
    void getTools_returnsOkWithFourKnownTools() {
        ResponseEntity<List<Map<String, Object>>> resp = controller.getTools();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> body = resp.getBody();
        assertThat(body).hasSize(4);
        List<String> ids = body.stream().map(m -> (String) m.get("id")).toList();
        assertThat(ids).containsExactlyInAnyOrder("graalvm", "maven", "python", "cmake");
    }

    @Test
    void getTools_eachToolHasRequiredFields() {
        ResponseEntity<List<Map<String, Object>>> resp = controller.getTools();

        for (Map<String, Object> tool : resp.getBody()) {
            assertThat(tool).containsKey("id");
            assertThat(tool).containsKey("name");
            assertThat(tool).containsKey("description");
            assertThat(tool).containsKey("installed");
            assertThat(tool).containsKey("path");
        }
    }

    @Test
    void getTools_installedFieldIsBoolean() {
        ResponseEntity<List<Map<String, Object>>> resp = controller.getTools();

        for (Map<String, Object> tool : resp.getBody()) {
            assertThat(tool.get("installed")).isInstanceOf(Boolean.class);
        }
    }

    // ─── installTool ──────────────────────────────────────────────────────────

    @Test
    void installTool_knownTool_returnsAccepted() {
        ResponseEntity<Map<String, Object>> resp = controller.installTool("graalvm");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody().get("toolId")).isEqualTo("graalvm");
        assertThat(resp.getBody().get("status")).isEqualTo("accepted");
    }

    @Test
    void installTool_caseInsensitive_returnsAccepted() {
        ResponseEntity<Map<String, Object>> resp = controller.installTool("MAVEN");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody().get("toolId")).isEqualTo("maven");
    }

    @Test
    void installTool_unknownTool_returnsBadRequest() {
        ResponseEntity<Map<String, Object>> resp = controller.installTool("unknown-tool");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("status")).isEqualTo("error");
    }

    @Test
    void installTool_python_returnsAccepted() {
        ResponseEntity<Map<String, Object>> resp = controller.installTool("python");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void installTool_cmake_returnsAccepted() {
        ResponseEntity<Map<String, Object>> resp = controller.installTool("cmake");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    // ─── uninstallTool ────────────────────────────────────────────────────────

    @Test
    void uninstallTool_knownTool_returnsAccepted() {
        ResponseEntity<Map<String, Object>> resp = controller.uninstallTool("maven");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody().get("status")).isEqualTo("accepted");
    }

    @Test
    void uninstallTool_unknownTool_returnsBadRequest() {
        ResponseEntity<Map<String, Object>> resp = controller.uninstallTool("nonexistent");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void uninstallTool_caseInsensitive_returnsAccepted() {
        ResponseEntity<Map<String, Object>> resp = controller.uninstallTool("GRAALVM");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody().get("toolId")).isEqualTo("graalvm");
    }
}
