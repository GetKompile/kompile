package ai.kompile.cli.main.chat.config;

public final class GroqChatProvider extends StaticChatProvider {
    public GroqChatProvider() {
        super("groq", "Groq", "https://api.groq.com/openai/v1", "GROQ_API_KEY");
    }

    @Override
    public ModelDiscovery.Strategy modelDiscoveryStrategy() {
        return ProviderModelDiscoveryStrategies.groq();
    }
}
