package ai.kompile.cli.main.chat.enforcer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostFeedbackDecisionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesWarnVerdict() {
        PostFeedbackDecision decision = PostFeedbackDecision.parse(mapper, """
                {"status":"WARN","score":0.62,"findings":["Tests were not run"],"evidence":["No test output"],"next_actions":["Run tests"],"correction_prompt":"Run the relevant tests.","reasoning":"Missing verification."}
                """);

        assertEquals(PostFeedbackDecision.Status.WARN, decision.getStatus());
        assertEquals(0.62, decision.getScore(), 0.001);
        assertEquals("Tests were not run", decision.getFindings().get(0));
        assertEquals("Run the relevant tests.", decision.getCorrectionPrompt());
    }

    @Test
    void invalidJsonFailsClosed() {
        PostFeedbackDecision decision = PostFeedbackDecision.parse(mapper, "looks fine");

        assertEquals(PostFeedbackDecision.Status.FAIL, decision.getStatus());
        assertFalse(decision.getFindings().isEmpty());
        assertTrue(decision.toMarkdown().contains("Post Feedback: FAIL"));
    }
}
