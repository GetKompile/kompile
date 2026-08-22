package ai.kompile.cli.main.chat.config;

public final class GitHubCopilotChatProvider extends StaticChatProvider {
    public GitHubCopilotChatProvider() {
        super("github-copilot", "GitHub Copilot", "https://api.individual.githubcopilot.com",
                "COPILOT_GITHUB_TOKEN");
    }

    @Override
    public boolean supportsApiKey() {
        return false;
    }

    @Override
    public ModelDiscovery.Strategy modelDiscoveryStrategy() {
        return ProviderModelDiscoveryStrategies.githubCopilot();
    }
}
