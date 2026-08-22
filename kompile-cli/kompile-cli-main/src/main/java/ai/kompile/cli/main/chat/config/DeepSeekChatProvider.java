package ai.kompile.cli.main.chat.config;

public final class DeepSeekChatProvider extends StaticChatProvider {
    public DeepSeekChatProvider() {
        super("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "DEEPSEEK_API_KEY");
    }

    @Override
    public ModelDiscovery.Strategy modelDiscoveryStrategy() {
        return ProviderModelDiscoveryStrategies.deepSeek();
    }
}
