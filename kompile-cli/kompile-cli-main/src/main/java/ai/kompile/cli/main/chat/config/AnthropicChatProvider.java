package ai.kompile.cli.main.chat.config;

public final class AnthropicChatProvider extends StaticChatProvider {
    public AnthropicChatProvider() {
        super("anthropic", "Anthropic", "https://api.anthropic.com", "ANTHROPIC_API_KEY");
    }

    @Override
    public ModelDiscovery.Strategy modelDiscoveryStrategy() {
        return ProviderModelDiscoveryStrategies.anthropic();
    }
}
