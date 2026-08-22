package ai.kompile.cli.main.chat.config;

public final class OpenRouterChatProvider extends StaticChatProvider {
    public OpenRouterChatProvider() {
        super("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY");
    }

    @Override
    public ModelDiscovery.Strategy modelDiscoveryStrategy() {
        return ProviderModelDiscoveryStrategies.openRouter();
    }
}
