package ai.kompile.cli.main.chat.config;

import java.util.Locale;
import java.util.Set;

/** Request-local provider/auth selection shared by native chat and MCP delegation. */
public final class SubagentChatConfig {
    private SubagentChatConfig() {}

    public static ChatConfig configure(ChatConfig parent, String provider, String model,
                                       String thinking, String authenticationMethod) {
        if (parent == null) {
            throw new IllegalStateException("No Kompile chat configuration. Run kompile chat --setup in this project.");
        }
        ChatConfig child = parent.copy();
        String selected = present(provider) ? provider.trim().toLowerCase(Locale.ROOT) : child.getProvider();
        if (present(provider) && !provider.equalsIgnoreCase(parent.getProvider())
                && ChatProviderRegistry.find(selected) == null) {
            throw new IllegalArgumentException("Unknown provider '" + provider
                    + "'. Use resolve_model action=providers to select a registered provider.");
        }
        if (present(model)) {
            String prefix = selected + "/";
            child.setModel(model.startsWith(prefix) ? model.substring(prefix.length()) : model);
        }
        // Inherited settings already name an intentional route. Only a model override
        // requests auto-routing; a workflow's bound provider is always supplied explicitly.
        if (!present(provider) && present(model)
                && !"custom".equalsIgnoreCase(selected)
                && !child.isKompileLocalServing() && !child.isOpenCodeNative()) {
            // Explicit custom/local catalogs own their model ids. A familiar family
            // spelling is not permission to send their prompts/credentials elsewhere.
            selected = ModelRouting.resolve(child.getModel(), selected).provider();
        }
        if (selected != null && !selected.equalsIgnoreCase(child.getProvider())) {
            child.setProvider(selected);
            child.setApiKey(null);
            child.setBaseUrl(null);
            child.setCredentialName(null);
            child.setAuthenticationMethod(ChatConfig.authenticationMethodAfterProviderSwitch(selected));
            child.setThinking(null);
            child.setFastMode(false);
            child.setUltracode(false);
            child.setContextWindowTokens(0);
            child.setMaxOutputTokens(0);
        }
        if (present(authenticationMethod)) {
            String method = authenticationMethod.trim().toLowerCase(Locale.ROOT);
            if (!Set.of("oauth", "native", "api-key").contains(method)) {
                throw new IllegalArgumentException("authentication_method must be oauth, native, or api-key; "
                        + "use resolve_model to inspect the provider's auth route.");
            }
            if (!method.equalsIgnoreCase(child.getAuthenticationMethod())) {
                child.setApiKey(null);
                child.setCredentialName(null);
            }
            child.setAuthenticationMethod(method);
        }
        if (present(thinking)) child.setThinking(thinking);
        return child;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
