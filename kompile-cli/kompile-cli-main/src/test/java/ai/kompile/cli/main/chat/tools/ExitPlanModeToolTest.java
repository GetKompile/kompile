package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExitPlanModeToolTest {

    private ExitPlanModeTool tool;
    private ToolContext context;
    private ObjectMapper om;

    @BeforeEach
    void setUp() {
        tool = new ExitPlanModeTool();
        om = new ObjectMapper();

        AgentConfig agent = AgentConfig.builder("planner")
                .enabledTools(Set.of("*"))
                .build();
        PermissionService perms = new PermissionService();
        perms.setUserOverride("exit_plan_mode", PermissionService.PermissionLevel.ALLOW);
        ToolRegistry registry = new ToolRegistry(om);
        context = new ToolContext("test-session", agent, perms, Paths.get("."), registry);
    }

    @Test
    void testIdAndDescription() {
        assertEquals("exit_plan_mode", tool.id());
        assertNotNull(tool.description());
        assertFalse(tool.description().isEmpty());
    }

    @Test
    void testParameterSchemaRequiresPlanSummary() {
        JsonNode schema = tool.parameterSchema();
        assertTrue(schema.has("properties"));
        assertTrue(schema.path("properties").has("plan_summary"));
        assertEquals("string", schema.path("properties").path("plan_summary").path("type").asText());

        JsonNode required = schema.path("required");
        assertTrue(required.isArray());
        boolean found = false;
        for (JsonNode r : required) {
            if ("plan_summary".equals(r.asText())) found = true;
        }
        assertTrue(found, "plan_summary should be required");
    }

    @Test
    void testInitialStateNotApproved() {
        assertFalse(tool.isPlanApproved());
    }

    @Test
    void testExecuteSetsApproved() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("plan_summary", "Create 3 files and update the POM");

        ToolResult result = tool.execute(params, context);

        assertFalse(result.isError());
        assertTrue(result.getOutput().contains("Plan submitted"));
        assertTrue(tool.isPlanApproved());
    }

    @Test
    void testResetClearsApproval() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("plan_summary", "Some plan");
        tool.execute(params, context);
        assertTrue(tool.isPlanApproved());

        tool.reset();
        assertFalse(tool.isPlanApproved());
    }

    @Test
    void testExecuteWithEmptySummary() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("plan_summary", "");

        ToolResult result = tool.execute(params, context);

        // Still succeeds — empty summary is allowed
        assertFalse(result.isError());
        assertTrue(tool.isPlanApproved());
    }

    @Test
    void testMultipleExecutions() throws Exception {
        // First call
        ObjectNode params1 = om.createObjectNode();
        params1.put("plan_summary", "Plan A");
        tool.execute(params1, context);
        assertTrue(tool.isPlanApproved());

        // Reset and re-execute
        tool.reset();
        assertFalse(tool.isPlanApproved());

        ObjectNode params2 = om.createObjectNode();
        params2.put("plan_summary", "Plan B");
        tool.execute(params2, context);
        assertTrue(tool.isPlanApproved());
    }

    @Test
    void testPermissionKey() {
        assertEquals("exit_plan_mode", tool.permissionKey());
    }
}
