package ai.kompile.cli.main.chat.config;

public final class OpenAiChatProvider extends StaticChatProvider {
    public OpenAiChatProvider() {
        super("openai", "OpenAI", "https://api.openai.com/v1", "OPENAI_API_KEY");
    }

    @Override
    public ModelDiscovery.Strategy modelDiscoveryStrategy() {
        return ProviderModelDiscoveryStrategies.openAi();
    }
}
