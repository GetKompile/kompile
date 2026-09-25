package ai.kompile.cli.main.chat.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Explicitly opted-in, read-only catalog diagnostics; never prints credential contents. */
@EnabledIfSystemProperty(named = "kompile.test.openai.discovery.live", matches = "true")
class OpenAiModelDiscoveryLiveTest {
    @Test
    void reportApiKeyCatalogStatus() throws Exception {
        ChatConfig config = new ChatConfig("openai", null, null, null);
        config.setAuthenticationMethod("api-key");
        var result = SetupWizard.modelDiscovery("openai", null, config);
        System.out.println("OpenAI API-key catalog: status=" + result.status()
                + ", models=" + result.models().size() + ", detail=" + result.message());
        if (ModelCatalogSelection.authenticationBlocked(result)) {
            org.junit.jupiter.api.Assertions.assertFalse(
                    ModelCatalogSelection.authenticationNotice(result, "openai").isBlank());
            org.junit.jupiter.api.Assertions.assertEquals(ModelCatalogSelection.SelectionDecision.UNKNOWN,
                    ModelCatalogSelection.decisionFor(result, "openai", "1"));
        }
    }
}
