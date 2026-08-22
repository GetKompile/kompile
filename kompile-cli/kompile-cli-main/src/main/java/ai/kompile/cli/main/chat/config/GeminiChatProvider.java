package ai.kompile.cli.main.chat.config;

public final class GeminiChatProvider extends StaticChatProvider {
    public GeminiChatProvider() {
        super("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
                "GOOGLE_API_KEY");
    }

    @Override
    public ModelDiscovery.Strategy modelDiscoveryStrategy() {
        return ProviderModelDiscoveryStrategies.gemini();
    }
}
