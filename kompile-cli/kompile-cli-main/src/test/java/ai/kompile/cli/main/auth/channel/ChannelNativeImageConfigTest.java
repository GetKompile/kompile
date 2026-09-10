package ai.kompile.cli.main.auth.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelNativeImageConfigTest {

    @Test
    void registersPicocliCommandsAndChannelWireRecords() throws Exception {
        String resource = "/META-INF/native-image/ai.kompile/kompile-cli/reflect-config.json";
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input, "missing native-image reflection configuration");
            JsonNode config = new ObjectMapper().readTree(input);
            Set<String> registered = new HashSet<>();
            config.forEach(entry -> registered.add(entry.path("name").asText()));

            Set<String> required = Set.of(
                    "ai.kompile.cli.main.auth.channel.ChannelAuthCommand",
                    "ai.kompile.cli.main.auth.channel.ChannelAuthCommand$Connect",
                    "ai.kompile.cli.main.auth.channel.ChannelAuthCommand$AuthStatus",
                    "ai.kompile.cli.main.auth.channel.ChannelAuthCommand$Login",
                    "ai.kompile.cli.main.auth.channel.ChannelAuthCommand$Rotate",
                    "ai.kompile.cli.main.auth.channel.ChannelAuthCommand$Run",
                    "ai.kompile.cli.main.auth.channel.ChannelAuthCommand$WebLogin",
                    "ai.kompile.cli.main.auth.channel.ChannelAuthCommand$Telegram$Pair",
                    "ai.kompile.cli.main.auth.channel.ChannelAuthCommand$Telegram$Approve",
                    "ai.kompile.cli.main.auth.source.AuthSourceCommand",
                    "ai.kompile.cli.main.auth.source.AuthSourceCommand$OAuthSettings",
                    "ai.kompile.cli.main.auth.source.AuthSourceCommand$OAuthSetup",
                    "ai.kompile.cli.main.auth.source.AuthSourceCommand$ValidateOAuth",
                    "ai.kompile.cli.main.auth.source.AuthSourceCommand$OAuthHealth",
                    "ai.kompile.cli.main.auth.source.AuthSourceCommand$ResetOAuth",
                    "ai.kompile.cli.main.auth.source.AuthSourceCommand$Ingest",
                    "ai.kompile.cli.main.auth.source.AuthSourceCommand$Connect",
                    "ai.kompile.channel.api.ChannelProviderAuthView",
                    "ai.kompile.channel.api.ChannelProviderAuthView$Mode",
                    "ai.kompile.channel.api.ChannelProviderDescriptor",
                    "ai.kompile.channel.api.ChannelProviderDescriptor$Field",
                    "ai.kompile.channel.api.ChannelChatEngine",
                    "ai.kompile.channel.api.ChannelEngineDescriptor",
                    "ai.kompile.channel.api.ChannelBrowserLoginView",
                    "ai.kompile.channel.api.ChannelConnectionRequest",
                    "ai.kompile.channel.api.ChannelConnectionUpdate",
                    "ai.kompile.channel.api.ChannelConnectionView",
                    "ai.kompile.channel.api.ChannelCredentialView",
                    "ai.kompile.channel.api.ChannelTestRequest",
                    "ai.kompile.channel.api.ChannelTestResult",
                    "ai.kompile.channel.api.TelegramPairingStartView",
                    "ai.kompile.channel.api.TelegramPairingView",
                    "ai.kompile.channel.api.TelegramDiagnosticsView",
                    "ai.kompile.channel.api.TelegramWebhookInfoView");
            Set<String> missing = new HashSet<>(required);
            missing.removeAll(registered);
            assertTrue(missing.isEmpty(), () -> "Missing channel native reflection metadata: " + missing);
        }
    }
}
