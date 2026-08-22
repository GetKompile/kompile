package ai.kompile.cli.main.chat.config;

public final class OllamaChatProvider extends StaticChatProvider {
    public OllamaChatProvider() {
        super("ollama", "Ollama", "http://localhost:11434/v1", null);
    }

    @Override
    public boolean localOnly() {
        return true;
    }

    @Override
    public boolean supportsApiKey() {
        return false;
    }

    @Override
    public ModelDiscovery.Strategy modelDiscoveryStrategy() {
        return ProviderModelDiscoveryStrategies.ollama();
    }
}
