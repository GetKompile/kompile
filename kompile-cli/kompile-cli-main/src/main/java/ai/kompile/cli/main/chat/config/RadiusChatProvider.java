package ai.kompile.cli.main.chat.config;

public final class RadiusChatProvider extends StaticChatProvider {
    public RadiusChatProvider() {
        super("radius", "Radius", "https://radius.pi.dev", "RADIUS_API_KEY");
    }

    @Override
    public ModelDiscovery.Strategy modelDiscoveryStrategy() {
        return ProviderModelDiscoveryStrategies.radius();
    }
}
