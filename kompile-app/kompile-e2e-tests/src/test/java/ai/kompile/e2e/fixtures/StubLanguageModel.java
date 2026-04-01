package ai.kompile.e2e.fixtures;

import ai.kompile.core.llm.LanguageModel;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.List;

/**
 * Stub language model that returns canned echo responses.
 * Useful for testing pipelines without requiring an actual LLM API.
 */
public class StubLanguageModel implements LanguageModel {

    @Override
    public String generateResponse(String userQuery, List<String> context) {
        return "StubLLM response to: " + userQuery
                + " | context_docs=" + (context != null ? context.size() : 0);
    }

    @Override
    public ChatResponse generateResponseWithPotentialToolCalls(String userQuery, List<String> context) {
        // Return null since tool call testing isn't needed for basic E2E flows
        return null;
    }
}
