package ai.kompile.kclaw.gateway.integration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChannelProviderCatalogTest {

    private final ChannelProviderCatalog catalog = new ChannelProviderCatalog();

    @Test
    void exposesEveryBuiltInProviderAndNormalizesTypedSettings() {
        assertEquals(
                Set.of("telegram", "slack", "discord", "whatsapp", "email"),
                catalog.providers().stream().map(provider -> provider.id()).collect(Collectors.toSet()));

        Map<String, Object> settings = catalog.normalizeSettings("telegram", Map.of(
                "allowedChatIds", "11, 12",
                "allowAllInbound", "false"));

        assertEquals(List.of(11L, 12L), settings.get("allowedChatIds"));
        assertEquals(false, settings.get("allowAllInbound"));
        assertEquals(false, settings.get("allowHarnessSend"));

        Map<String, Object> restored = catalog.normalizeSettings(
                "telegram", Map.of("allowedChatIds", List.of(42)));
        assertEquals(List.of(42L), restored.get("allowedChatIds"));
    }

    @Test
    void rejectsUnknownFieldsAndMissingRequiredSecrets() {
        assertThrows(IllegalArgumentException.class,
                () -> catalog.normalizeSettings("slack", Map.of("tokne", "wrong")));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.validateSecrets("telegram", Map.of()));
        catalog.validateSecrets("slack", Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> catalog.validateRuntimeSecrets(
                        "slack", Map.of("botToken", "xoxb-test")));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.validateSecretsForUpdate("telegram", Map.of("botToken", "")));
    }

    @Test
    void emailSchemaRequiresConnectionCoordinatesButAppliesSafePolicyDefaults() {
        Map<String, Object> settings = catalog.normalizeSettings("email", Map.of(
                "imapHost", "imap.example.test",
                "smtpHost", "smtp.example.test",
                "username", "bot@example.test",
                "fromAddress", "bot@example.test",
                "trustedAuthenticationServer", "mx.example.test"));

        assertEquals(993, settings.get("imapPort"));
        assertEquals(587, settings.get("smtpPort"));
        assertEquals(false, settings.get("allowAllInbound"));
        assertEquals(List.of(), settings.get("allowedSenders"));
    }
}
