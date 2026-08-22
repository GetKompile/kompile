package ai.kompile.cli.main.chat.config;

public final class XaiChatProvider extends StaticChatProvider {
    public XaiChatProvider() {
        super("xai", "xAI", "https://api.x.ai/v1", "XAI_API_KEY");
    }

    @Override
    public ModelDiscovery.Strategy modelDiscoveryStrategy() {
        return ProviderModelDiscoveryStrategies.xai();
    }
}
