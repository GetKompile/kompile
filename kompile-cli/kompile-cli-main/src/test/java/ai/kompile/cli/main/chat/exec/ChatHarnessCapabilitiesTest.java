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
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.main.chat.config.ChatConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatHarnessCapabilitiesTest {

    @TempDir
    Path tempDir;

    @Test
    void reportsHarnessPersonasRolesAndProviderLimitsWithoutSecrets() throws Exception {
        ChatConfig config = new ChatConfig(
                "custom", "do-not-render", "test-model", "https://models.example/v1");
        config.setChatMode("standard");
        config.setDefaultAgent("crawler");
        config.setContextWindowTokens(8_192);
        config.setMaxOutputTokens(1_024);
        config.setDefaultMemory(true);
        config.setDefaultRag(false);

        ChatHarnessCapabilities.Report report =
                ChatHarnessCapabilities.inspect(tempDir, config);
        String json = ChatHarnessCapabilities.toJson(report);

        assertTrue(report.available(), report.status());
        assertEquals("kompile-cli-main", report.engine());
        assertEquals("custom", report.provider());
        assertEquals("test-model", report.model());
        assertEquals(8_192, report.contextWindow());
        assertEquals(1_024, report.maxOutputTokens());
        assertTrue(report.inputBudgetTokens() > 0);
        assertTrue(report.attachmentsSupported());
        assertTrue(report.personas().stream().anyMatch(persona ->
                persona.name().equals("crawler") && persona.defaultPersona()));
        assertTrue(report.personas().stream().anyMatch(persona ->
                persona.name().equals("coder") && persona.selectorType().equals("agent")));
        assertTrue(report.personas().stream().anyMatch(persona ->
                persona.name().startsWith("role:") && persona.selectorType().equals("role")));
        assertFalse(json.contains("do-not-render"), json);
        var parsed = new ObjectMapper().readTree(json);
        assertEquals("kompile-cli-main", parsed.path("engine").asText());
        assertTrue(parsed.path("attachmentsSupported").asBoolean());
        assertTrue(parsed.path("personas").isArray());
    }

    @Test
    void reportsAttachmentSupportFromTheActualWireProtocol() {
        ChatConfig local = new ChatConfig(
                "kompile-local", null, "local-model", "http://127.0.0.1:8090/v1");
        local.setChatMode("standard");
        local.setContextWindowTokens(8_192);
        local.setMaxOutputTokens(1_024);

        ChatHarnessCapabilities.Report report =
                ChatHarnessCapabilities.inspect(tempDir, local);

        assertTrue(report.available(), report.status());
        assertFalse(report.attachmentsSupported(),
                "Kompile serving rejects structured attachments and must not advertise them");
    }

    @Test
    void explicitGlobalScopeIsNotShadowedByProjectConfig() throws Exception {
        String oldHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());
            Path project = java.nio.file.Files.createDirectories(tempDir.resolve("project"));
            ChatConfig global = new ChatConfig("custom", "secret", "global-model", "https://example.test/v1");
            global.setChatMode("standard");
            global.setContextWindowTokens(8192);
            global.save(ChatConfig.Scope.GLOBAL, project);
            ChatConfig local = new ChatConfig("custom", "secret", "project-model", "https://example.test/v1");
            local.setChatMode("standard");
            local.setContextWindowTokens(8192);
            local.save(ChatConfig.Scope.PROJECT, project);
            assertEquals("global-model", ChatHarnessCapabilities.inspect(project, true).model());
            assertEquals("project-model", ChatHarnessCapabilities.inspect(project, false).model());
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }

    @Test
    void refusesRecursiveKompileServerConfiguration() {
        ChatConfig config = new ChatConfig(
                "kompile", null, null, "http://localhost:8081");
        config.setChatMode("standard");

        ChatHarnessCapabilities.Report report =
                ChatHarnessCapabilities.inspect(tempDir, config);

        assertFalse(report.available());
        assertTrue(report.status().contains("recursively"), report.status());
        assertFalse(report.attachmentsSupported());
        assertTrue(report.personas().stream().noneMatch(
                ChatHarnessCapabilities.Persona::available));
    }
}
