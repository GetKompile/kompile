/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.project.LocalSubprocessWatchdog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubprocessWatchdogToolTest {

    @TempDir
    Path projectRoot;

    private ObjectMapper mapper;
    private ToolContext context;
    private SubprocessWatchdogTool tool;

    @BeforeEach
    void setUp() {
        clearAdmissionProperties();
        mapper = new ObjectMapper();
        PermissionService permissions = new PermissionService();
        permissions.setUserOverride("subprocess_watchdog", PermissionService.PermissionLevel.ALLOW);
        context = new ToolContext("watchdog-tool-test",
                AgentConfig.builder("watchdog-tool-test")
                        .enabledTools(Set.of("subprocess_watchdog"))
                        .build(),
                permissions, projectRoot, new ToolRegistry(mapper));
        tool = new SubprocessWatchdogTool(mapper);
    }

    @AfterEach
    void tearDown() {
        clearAdmissionProperties();
    }

    @Test
    void schemaAndConfigUpdateExposeCapacityAdmission() throws Exception {
        assertTrue(tool.parameterSchema().path("properties").path("config")
                .path("description").asText().contains("admissionMode"));

        ObjectNode update = mapper.createObjectNode().put("action", "config_update");
        update.putObject("config")
                .put("admissionMode", "fail-fast")
                .put("admissionTimeoutMs", 1234)
                .put("admissionPollIntervalMs", 25)
                .put("admissionMinAvailableRamMb", 4096)
                .put("admissionMaxRamUsedFraction", 0.8)
                .put("admissionMinAvailableGpuMb", 8192)
                .put("admissionMaxGpuUsedFraction", 0.7);

        ToolResult updated = tool.execute(update, context);
        assertFalse(updated.isError(), updated.getOutput());
        assertTrue(updated.getOutput().contains("\"admissionMode\" : \"fail\""), updated.getOutput());
        assertTrue(updated.getOutput().contains("\"admissionTimeoutMs\" : 1234"), updated.getOutput());
        assertTrue(updated.getOutput().contains("\"admissionMinAvailableGpuMb\" : 8192"),
                updated.getOutput());

        ToolResult config = tool.execute(
                mapper.createObjectNode().put("action", "config_get"), context);
        assertFalse(config.isError(), config.getOutput());
        assertTrue(config.getOutput().contains("\"admissionMaxRamUsedFraction\" : 0.8"),
                config.getOutput());

        ObjectNode invalid = mapper.createObjectNode().put("action", "config_update");
        invalid.putObject("config").put("admissionMode", "eventually-maybe");
        ToolResult rejected = tool.execute(invalid, context);
        assertTrue(rejected.isError());
        assertTrue(rejected.getOutput().contains("expected wait|fail|off"), rejected.getOutput());
    }

    @Test
    void manualCheckIncludesTheAdmissionDecision() throws Exception {
        ObjectNode update = mapper.createObjectNode().put("action", "config_update");
        update.putObject("config").put("admissionMode", "off");
        assertFalse(tool.execute(update, context).isError());

        ToolResult checked = tool.execute(
                mapper.createObjectNode().put("action", "check"), context);

        assertFalse(checked.isError(), checked.getOutput());
        assertTrue(checked.getOutput().contains("\"capacityAdmission\""), checked.getOutput());
        assertTrue(checked.getOutput().contains("\"wouldAdmit\" : true"), checked.getOutput());
    }

    private static void clearAdmissionProperties() {
        System.clearProperty(LocalSubprocessWatchdog.ENABLED_PROPERTY);
        System.clearProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY);
        System.clearProperty(LocalSubprocessWatchdog.ADMISSION_TIMEOUT_MS_PROPERTY);
        System.clearProperty(LocalSubprocessWatchdog.ADMISSION_POLL_INTERVAL_MS_PROPERTY);
        System.clearProperty(LocalSubprocessWatchdog.ADMISSION_MIN_AVAILABLE_RAM_MB_PROPERTY);
        System.clearProperty(LocalSubprocessWatchdog.ADMISSION_MAX_RAM_USED_FRACTION_PROPERTY);
        System.clearProperty(LocalSubprocessWatchdog.ADMISSION_MIN_AVAILABLE_GPU_MB_PROPERTY);
        System.clearProperty(LocalSubprocessWatchdog.ADMISSION_MAX_GPU_USED_FRACTION_PROPERTY);
    }
}
