package ai.kompile.cli.main.chat.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void supervisedRequiresApprovalForCrawlMutations() {
        AgentRunController controller = new AgentRunController(
                AgentRunController.Mode.SUPERVISED, 3, 8);
        ObjectNode start = mapper.createObjectNode().put("operation", "start");

        assertFalse(controller.beforeTool("crawl_control", start).allowed());
        assertFalse(controller.beforeTool("mcp__kompile__crawl_control", start).allowed());
        controller.approveOnce();
        assertTrue(controller.beforeTool("crawl_control", start).allowed());
        assertTrue(controller.beforeTool("crawl_control",
                mapper.createObjectNode().put("operation", "status")).allowed());

        assertFalse(controller.beforeTool("crawl_documents", mapper.createObjectNode()).allowed());
        controller.approveOnce();
        assertTrue(controller.beforeTool("crawl_documents", mapper.createObjectNode()).allowed());
        assertTrue(controller.beforeTool("crawl_discover", mapper.createObjectNode()).allowed());
    }

    @Test
    void pauseResumeAndSingleStepAreSafePointControls() {
        AgentRunController controller = new AgentRunController(
                AgentRunController.Mode.SINGLE_STEP, 3, 4);
        assertEquals(AgentRunController.State.PAUSED, controller.state());
        assertFalse(controller.beforeStep(1));

        controller.stepOnce();
        assertTrue(controller.beforeStep(1));
        controller.afterStep();
        assertEquals(AgentRunController.State.PAUSED, controller.state());

        controller.resume();
        assertTrue(controller.beforeStep(2));
        controller.stop();
        assertFalse(controller.beforeStep(3));
    }

    @Test
    void budgetsStopTheRun() {
        AgentRunController controller = new AgentRunController(
                AgentRunController.Mode.AUTO, 1, 1);
        assertTrue(controller.beforeStep(1));
        controller.afterStep();
        assertEquals(AgentRunController.State.STOPPED, controller.state());
        assertFalse(controller.beforeTool("crawl_control",
                mapper.createObjectNode().put("operation", "status")).allowed());
    }
}
