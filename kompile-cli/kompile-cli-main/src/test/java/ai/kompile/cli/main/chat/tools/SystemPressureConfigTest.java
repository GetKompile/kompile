package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SystemPressureConfigTest {
    @TempDir Path dir;

    @Test void defaultsAreOptInAndCpuUtilizationDoesNotKillOrdinaryBuilds() {
        var settings = SystemPressureConfig.settings(SystemPressureConfig.defaults());
        assertFalse(settings.enabled());
        assertEquals(0, settings.thresholds().get("cpu"));
        assertEquals(0, settings.thresholds().get("gpuUtilization"));
        assertEquals(90, settings.thresholds().get("ram"));
    }

    @Test void userAndProjectScalarsAndResourceThresholdsInheritIndependently() throws Exception {
        Path user = dir.resolve("user.json");
        Path project = dir.resolve("project.json");
        Files.writeString(user, "{\"enabled\":true,\"thresholds\":{\"cpu\":85,\"ram\":91}}");
        Files.writeString(project, "{\"breachCount\":4,\"thresholds\":{\"ram\":93}}");
        var settings = SystemPressureConfig.settings(SystemPressureConfig.load(user, project));
        assertTrue(settings.enabled());
        assertEquals(4, settings.breachCount());
        assertEquals(85, settings.thresholds().get("cpu"));
        assertEquals(93, settings.thresholds().get("ram"));
        assertEquals(95, settings.thresholds().get("gpuMemory"));
    }

    @Test void invalidTypesRangesAndUnknownSettingsRejected() throws Exception {
        for (String patch : new String[]{"{\"enabled\":1}", "{\"intervalMs\":1}", "{\"breachCount\":1.5}",
                "{\"cooldownMs\":0}", "{\"momentumAlpha\":0}", "{\"projectionSeconds\":-1}",
                "{\"minMomentumPercentPerSecond\":0}", "{\"thresholds\":{\"cpu\":101}}",
                "{\"thresholds\":{\"cpu\":\"90\"}}", "{\"unknown\":5}", "{\"thresholds\":{\"other\":5}}"}) {
            var config = SystemPressureConfig.defaults();
            var parsed = JsonUtils.standardMapper().readTree(patch);
            assertThrows(IllegalArgumentException.class, () -> {
                SystemPressureConfig.merge(config, parsed);
                SystemPressureConfig.settings(config);
            }, patch);
        }
    }

    @Test void enablingWithoutAnyThresholdFails() {
        var config = SystemPressureConfig.defaults();
        config.put("enabled", true);
        for (String resource : SystemPressureConfig.METRICS) ((com.fasterxml.jackson.databind.node.ObjectNode)
                config.get("thresholds")).put(resource, 0);
        assertThrows(IllegalArgumentException.class, () -> SystemPressureConfig.settings(config));
    }

    @Test void chatCommandSavesOnlyOverridesAndInvalidChangeDoesNotWrite() throws Exception {
        Path user = dir.resolve("user/resource-monitor.json");
        Path project = dir.resolve("project");
        Files.createDirectories(project);
        assertTrue(SystemPressureConfig.command(project, "set cpu 85", user).startsWith("Saved"));
        assertTrue(SystemPressureConfig.command(project, "enable", user).startsWith("Saved"));
        var saved = JsonUtils.standardMapper().readTree(SystemPressureConfig.projectFile(project).toFile());
        assertEquals(2, saved.size());
        assertEquals(1, saved.get("thresholds").size());
        assertEquals(85, saved.path("thresholds").path("cpu").asInt());
        assertTrue(SystemPressureConfig.command(project, "set cpu 101", user).startsWith("Resource monitor error"));
        assertEquals(saved, JsonUtils.standardMapper().readTree(SystemPressureConfig.projectFile(project).toFile()));
        assertTrue(SystemPressureConfig.command(project, "disable", user).startsWith("Saved"));
        assertFalse(SystemPressureConfig.settings(SystemPressureConfig.load(user, SystemPressureConfig.projectFile(project))).enabled());
    }

    @Test void resourcePolicyRoutesMonitorBeforeLoadingAdmissionPolicy() throws Exception {
        Path project = dir.resolve("project");
        Files.createDirectories(project.resolve(".kompile"));
        Files.writeString(project.resolve(".kompile/resource-policy.json"), "invalid JSON");
        Path user = dir.resolve("user/resource-policy.json");
        assertTrue(ResourcePolicy.command(project, "monitor set ram 92", user).startsWith("Saved"));
        assertTrue(ResourcePolicy.command(project, "global monitor set gpuMemory 96", user).startsWith("Saved"));
        assertEquals(96, JsonUtils.standardMapper().readTree(user.resolveSibling("resource-monitor.json").toFile())
                .path("thresholds").path("gpuMemory").asInt());
        assertTrue(ResourcePolicy.command(project, "monitor show", user).contains("92"));
        assertTrue(ResourcePolicy.command(project, "monitor help", user).contains("projectionSeconds"));
    }
}
