package ai.kompile.cli.main.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DashboardCommandsTest {
    @Test void dashboardDiscoveryExplicitlySurfacesTimelineAndLegacyAlias() {
        assertTrue(DashboardCommands.DISCOVERY.contains("/timeline"));
        assertTrue(DashboardCommands.DISCOVERY.contains("/dashboard-timeline"));
        assertEquals(ChatCommandCatalog.WebSupport.NOT_YET_SUPPORTED, ChatCommandCatalog.webSupport("timeline"));
    }

    @Test void bareDashboardIsSummaryNotRefresh() {
        var request = DashboardCommands.parse("/dashboard", "");
        assertEquals("summary", request.topic());
        assertNull(request.control());
        assertEquals("this session", request.question());
        assertEquals("overview", request.insightsArguments(new ObjectMapper()).path("topic").asText());
    }

    @Test void explicitControlsAndAliasesRemainControls() {
        for (String control : new String[]{"refresh", "reload", "show", "on", "hide", "off", "status"}) {
            assertEquals(control, DashboardCommands.parse("/dashboard", control).control());
        }
    }

    @Test void exactSelectorIdsAndTimeWindowArePreserved() {
        var request = DashboardCommands.parse("/dashboard-tools", "session:Chat-AbC tool:ReadFile call:CALL-aB offset:7 last 2 hours");
        assertEquals("Chat-AbC", request.sessionId());
        assertEquals("tokens session:Chat-AbC tool:ReadFile call:CALL-aB offset:7 last 2 hours", request.question());
        assertFalse(request.question().contains("this session"));
    }

    @Test void toolsJudgeAndTokensDefaultToCurrentSession() {
        for (String topic : new String[]{"tools", "judge", "tokens"}) {
            assertTrue(DashboardCommands.parse("/dashboard-" + topic, "last 2 hours").question().contains("this session"));
        }
    }

    @Test void conflictingSessionsAndUnknownSelectorsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> DashboardCommands.parse("/dashboard-judge", "session:A session:B"));
        assertThrows(IllegalArgumentException.class, () -> DashboardCommands.parse("/dashboard-tools", "unknown:X"));
        assertThrows(IllegalArgumentException.class, () -> DashboardCommands.parse("/dashboard-tools", "session:"));
        assertThrows(IllegalArgumentException.class, () -> DashboardCommands.parse("/dashboard-tools", "tool:A tool:B"));
    }

    @Test void offsetIsOnlyForNonnegativeToolTokenRows() {
        for (String topic : new String[]{"summary", "judge", "tests", "crawl", "graph", "tasks", "processes"}) {
            assertThrows(IllegalArgumentException.class, () -> DashboardCommands.parse("/dashboard-" + topic, "offset:2"));
        }
        assertThrows(IllegalArgumentException.class, () -> DashboardCommands.parse("/dashboard-tools", "offset:-1"));
        assertThrows(IllegalArgumentException.class, () -> DashboardCommands.parse("/dashboard-tools", "offset:2147483648"));
        assertEquals("tokens this session offset:0", DashboardCommands.parse("/dashboard-tools", "offset:0").question());
    }

    @Test void nonSessionSourcesDiscloseTheirActualScope() {
        for (String topic : new String[]{"tests", "crawl", "graph"}) {
            var request = DashboardCommands.parse("/dashboard-" + topic, "last 2 hours");
            assertFalse(request.question().contains("this session"));
            assertThrows(IllegalArgumentException.class, () -> DashboardCommands.parse("/dashboard-" + topic, "session:X"));
        }
        assertTrue(DashboardCommands.parse("/dashboard-tests", "").scope().contains("project + global"));
        assertTrue(DashboardCommands.parse("/dashboard-graph", "").scope().contains("current state"));
    }

    @Test void tasksAndProcessesDoNotPretendToSupportTimeOrPaging() {
        assertEquals("MiXeD", DashboardCommands.parse("/dashboard-tasks", "session:MiXeD").sessionId());
        assertThrows(IllegalArgumentException.class, () -> DashboardCommands.parse("/dashboard-tasks", "last 2 hours"));
        assertThrows(IllegalArgumentException.class, () -> DashboardCommands.parse("/dashboard-processes", "session:X"));
    }

    @Test void sourceCoveragePrecedesTruncatedEvidence() {
        var request = DashboardCommands.parse("/dashboard-tools", "");
        String result = DashboardCommands.present(request, "detail\n".repeat(130)
                + "Coverage: 2 unmeasured calls\nToken coverage is usage-journal only; legacy calls are not included.\n");
        assertTrue(result.indexOf("Coverage:") < result.indexOf("detail"));
        assertTrue(result.contains("usage-journal only"));
        assertTrue(result.contains("Dashboard display bounded"));
        assertTrue(result.contains("offset:N for tool-token rows"));
    }

    @Test void characterBoundAndControlSanitizationAreExplicit() {
        var request = DashboardCommands.parse("/dashboard-judge", "");
        String result = DashboardCommands.present(request, "\u001b[31mred\u001b[0m\n" + "X".repeat(20_000));
        assertTrue(result.contains("red"));
        assertFalse(result.contains("\u001b"));
        assertTrue(result.length() < 17_000);
        assertTrue(result.contains("Dashboard display bounded"));
        assertFalse(result.contains("offset:N"));
    }

    @Test void reportCatalogAddsNoWebSupport() {
        for (String topic : DashboardCommands.TOPICS) {
            String name = "dashboard-" + topic;
            assertTrue(ChatCommandCatalog.isBuiltin(name));
            assertEquals(ChatCommandCatalog.WebSupport.NOT_YET_SUPPORTED, ChatCommandCatalog.webSupport(name));
        }
    }
}
