/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth;

import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow;
import ai.kompile.cli.main.auth.oauth.OAuthProviderRegistry;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Chat-style numbered wizards for managed provider credentials. */
final class AuthWizard implements AutoCloseable {
    private static final List<String> API_KEY_PROVIDERS = List.of(
            "openai",
            "anthropic",
            "gemini",
            "openrouter",
            "xai",
            "deepseek",
            "groq",
            "radius");

    private final Prompter prompter;

    private AuthWizard(Prompter prompter) {
        this.prompter = prompter;
    }

    static AuthWizard open() throws IOException {
        return new AuthWizard(new TerminalPrompter());
    }

    static AuthWizard using(Prompter prompter) {
        if (prompter == null) {
            throw new IllegalArgumentException("prompter must not be null");
        }
        return new AuthWizard(prompter);
    }

    LoginRequest promptForLogin(
            OAuthProviderRegistry registry,
            CredentialStore store) throws IOException {
        prompter.header("Kompile Auth Login", "Add and select a provider credential");

        LinkedHashMap<String, String> providers = loginProviders(registry);
        List<String> providerIds = new ArrayList<>(providers.keySet());
        List<String> providerLabels = new ArrayList<>(providers.values());
        providerIds.add("__other__");
        providerLabels.add("Other provider — API key");

        int providerIndex = prompter.select("Select Provider:", providerLabels);
        if (providerIndex < 0) {
            return null;
        }
        String providerId = providerIds.get(providerIndex);
        if ("__other__".equals(providerId)) {
            providerId = prompter.text("Provider id:", null);
            if (providerId == null || providerId.isBlank()) {
                return null;
            }
            providerId = providerId.trim().toLowerCase(Locale.ROOT);
        }

        List<CredentialStore.CredentialInfo> existing = store.list(providerId);
        String suggestedName = existing.isEmpty()
                ? CredentialStore.DEFAULT_CREDENTIAL_NAME
                : "account-" + (existing.size() + 1);
        String credentialName;
        while (true) {
            credentialName = prompter.text("Credential name:", suggestedName);
            if (credentialName == null || credentialName.isBlank()) {
                return null;
            }
            String candidate = credentialName.trim().toLowerCase(Locale.ROOT);
            boolean alreadyExists = existing.stream()
                    .anyMatch(info -> info.credentialName().equals(candidate));
            if (!alreadyExists || prompter.confirm(
                    "Replace existing credential '" + candidate + "'?", false)) {
                credentialName = candidate;
                break;
            }
            suggestedName = "account-" + (existing.size() + 1);
        }

        List<LoginKind> kinds = new ArrayList<>();
        List<String> kindLabels = new ArrayList<>();
        if (registry.find(providerId).isPresent()) {
            kinds.add(LoginKind.OAUTH);
            kindLabels.add("OAuth / subscription sign-in");
        }
        if (!registry.isOAuthOnly(providerId)) {
            kinds.add(LoginKind.API_KEY);
            kindLabels.add("Paste an API key");
            kinds.add(LoginKind.ENVIRONMENT);
            kindLabels.add("Reference an environment variable");
        }
        if (kinds.isEmpty()) {
            prompter.message("No supported login method is available for " + providerId + ".");
            return null;
        }

        int kindIndex = kinds.size() == 1 ? 0 : prompter.select("Select Login Method:", kindLabels);
        if (kindIndex < 0) {
            return null;
        }
        LoginKind kind = kinds.get(kindIndex);
        String storedValue = null;
        String oauthMethod = null;

        if (kind == LoginKind.API_KEY) {
            storedValue = prompter.secret("API key for " + providerId + ":");
            if (storedValue == null || storedValue.isBlank()) {
                prompter.message("API key must not be blank.");
                return null;
            }
        } else if (kind == LoginKind.ENVIRONMENT) {
            String environmentName = prompter.text(
                    "Environment variable:",
                    defaultEnvironmentName(providerId));
            if (environmentName == null
                    || !environmentName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                prompter.message("Environment variable names must use letters, numbers, and underscores.");
                return null;
            }
            storedValue = "$" + environmentName;
        } else {
            OAuthProviderFlow flow = registry.require(providerId);
            List<OAuthProviderFlow.LoginMethod> methods = flow.loginMethods();
            if (methods.size() <= 1) {
                oauthMethod = methods.isEmpty() ? flow.defaultLoginMethod() : methods.get(0).id();
            } else {
                List<String> methodLabels = methods.stream()
                        .map(method -> method.label() + " (" + method.id() + ")")
                        .toList();
                int methodIndex = prompter.select("Select OAuth Method:", methodLabels);
                if (methodIndex < 0) {
                    return null;
                }
                oauthMethod = methods.get(methodIndex).id();
            }
        }

        boolean activate = existing.isEmpty()
                || prompter.confirm("Use this credential now for " + providerId + "?", true);
        return new LoginRequest(
                providerId,
                credentialName,
                kind,
                storedValue,
                oauthMethod,
                activate);
    }

    SwitchRequest promptForSwitch(CredentialStore store) throws IOException {
        prompter.header("Kompile Auth Switch", "Choose the active credential for a provider");
        List<CredentialStore.CredentialInfo> all = store.list();
        if (all.isEmpty()) {
            prompter.message("No stored credentials. Run 'kompile auth login' first.");
            return null;
        }

        List<String> providers = all.stream()
                .map(CredentialStore.CredentialInfo::providerId)
                .distinct()
                .toList();
        int providerIndex = providers.size() == 1
                ? 0
                : prompter.select("Select Provider:", providers);
        if (providerIndex < 0) {
            return null;
        }
        String providerId = providers.get(providerIndex);
        List<CredentialStore.CredentialInfo> credentials = store.list(providerId);
        List<String> labels = credentials.stream()
                .map(AuthWizard::credentialLabel)
                .toList();
        int credentialIndex = credentials.size() == 1
                ? 0
                : prompter.select("Select Credential:", labels);
        if (credentialIndex < 0) {
            return null;
        }
        return new SwitchRequest(providerId, credentials.get(credentialIndex).credentialName());
    }

    LogoutRequest promptForLogout(CredentialStore store) throws IOException {
        prompter.header("Kompile Auth Logout", "Remove credentials from one or more providers");
        List<CredentialStore.CredentialInfo> all = store.list();
        if (all.isEmpty()) {
            prompter.message("No stored credentials.");
            return null;
        }

        List<LogoutRequest> requests = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (CredentialStore.CredentialInfo info : all) {
            requests.add(new LogoutRequest(
                    LogoutScope.CREDENTIAL,
                    info.providerId(),
                    info.credentialName()));
            labels.add("Remove " + info.providerId() + " / " + credentialLabel(info));
        }
        List<String> providers = all.stream()
                .map(CredentialStore.CredentialInfo::providerId)
                .distinct()
                .toList();
        for (String providerId : providers) {
            int count = (int) all.stream()
                    .filter(info -> info.providerId().equals(providerId))
                    .count();
            if (count > 1) {
                requests.add(new LogoutRequest(LogoutScope.PROVIDER, providerId, null));
                labels.add("Remove all " + count + " credentials for " + providerId);
            }
        }
        if (all.size() > 1 || providers.size() > 1) {
            requests.add(new LogoutRequest(LogoutScope.ALL, null, null));
            labels.add("Remove every stored credential from every provider");
        }

        int selected = prompter.select("Select Logout Scope:", labels);
        if (selected < 0) {
            return null;
        }
        LogoutRequest request = requests.get(selected);
        if (!prompter.confirm("Remove the selected credential data?", false)) {
            return null;
        }
        return request;
    }

    OAuthProviderFlow.Interaction oauthInteraction() {
        return new OAuthProviderFlow.Interaction() {
            @Override
            public void info(String message) {
                prompter.message(message);
            }

            @Override
            public void authorizationUrl(URI url, String instructions) {
                prompter.message(instructions);
                prompter.message(url.toString());
            }

            @Override
            public void deviceCode(
                    String userCode,
                    URI verificationUri,
                    Integer intervalSeconds,
                    Integer expiresInSeconds) {
                prompter.message("Open " + verificationUri);
                prompter.message("Enter device code: " + userCode);
            }

            @Override
            public String prompt(String message) throws IOException {
                try {
                    return prompter.text(message, null);
                } catch (RuntimeException e) {
                    throw new IOException("Could not read OAuth input", e);
                }
            }
        };
    }

    private static LinkedHashMap<String, String> loginProviders(OAuthProviderRegistry registry) {
        LinkedHashMap<String, String> providers = new LinkedHashMap<>();
        for (String providerId : API_KEY_PROVIDERS) {
            providers.put(providerId, providerLabel(providerId));
        }
        registry.flows().stream()
                .sorted(java.util.Comparator.comparing(OAuthProviderFlow::providerId))
                .forEach(flow -> providers.putIfAbsent(
                        flow.providerId(),
                        flow.displayName() + " — OAuth"));
        return providers;
    }

    private static String providerLabel(String providerId) {
        return switch (providerId) {
            case "openai" -> "OpenAI — API key";
            case "anthropic" -> "Anthropic — API key or OAuth";
            case "gemini" -> "Google Gemini — API key";
            case "openrouter" -> "OpenRouter — API key or OAuth";
            case "xai" -> "xAI — API key or OAuth";
            case "deepseek" -> "DeepSeek — API key";
            case "groq" -> "Groq — API key";
            case "radius" -> "Radius — API key or OAuth";
            default -> providerId;
        };
    }

    private static String credentialLabel(CredentialStore.CredentialInfo info) {
        return info.credentialName() + " — " + info.type() + (info.active() ? " (active)" : "");
    }

    private static String defaultEnvironmentName(String providerId) {
        return switch (providerId) {
            case "openai" -> "OPENAI_API_KEY";
            case "anthropic" -> "ANTHROPIC_API_KEY";
            case "gemini" -> "GOOGLE_API_KEY";
            case "openrouter" -> "OPENROUTER_API_KEY";
            case "xai" -> "XAI_API_KEY";
            case "github-copilot" -> "COPILOT_GITHUB_TOKEN";
            case "radius" -> "RADIUS_API_KEY";
            case "deepseek" -> "DEEPSEEK_API_KEY";
            case "groq" -> "GROQ_API_KEY";
            default -> null;
        };
    }

    @Override
    public void close() {
        prompter.close();
    }

    enum LoginKind {
        API_KEY,
        ENVIRONMENT,
        OAUTH
    }

    enum LogoutScope {
        CREDENTIAL,
        PROVIDER,
        ALL
    }

    record LoginRequest(
            String providerId,
            String credentialName,
            LoginKind kind,
            String storedValue,
            String oauthMethod,
            boolean activate) {
    }

    record SwitchRequest(String providerId, String credentialName) {
    }

    record LogoutRequest(LogoutScope scope, String providerId, String credentialName) {
    }

    interface Prompter extends AutoCloseable {
        void header(String title, String subtitle);

        int select(String title, List<String> items);

        String text(String label, String defaultValue);

        String secret(String label);

        boolean confirm(String question, boolean defaultYes);

        void message(String message);

        @Override
        default void close() {
        }
    }

    private static final class TerminalPrompter implements Prompter {
        private static final String RESET = "\033[0m";
        private static final String BOLD = "\033[1m";
        private static final String DIM = "\033[2m";
        private static final String CYAN = "\033[36m";
        private static final String GREEN = "\033[32m";
        private static final String YELLOW = "\033[33m";

        private final Terminal terminal;
        private final LineReader reader;

        private TerminalPrompter() throws IOException {
            terminal = TerminalBuilder.builder().system(true).build();
            reader = LineReaderBuilder.builder().terminal(terminal).build();
        }

        @Override
        public void header(String title, String subtitle) {
            System.out.println();
            System.out.println(BOLD + CYAN + "  ╭──────────────────────────────────────╮" + RESET);
            System.out.printf(BOLD + CYAN + "  │  %-36s│%n" + RESET, title);
            System.out.println(BOLD + CYAN + "  ╰──────────────────────────────────────╯" + RESET);
            System.out.println("  " + DIM + subtitle + RESET);
            System.out.println();
        }

        @Override
        public int select(String title, List<String> items) {
            System.out.println(BOLD + "  " + title + RESET);
            System.out.println();
            for (int i = 0; i < items.size(); i++) {
                System.out.printf("  " + CYAN + "%2d" + RESET + "  %s%n", i + 1, items.get(i));
            }
            System.out.println();
            while (true) {
                String input;
                try {
                    input = reader.readLine("  Choice (1-" + items.size()
                            + ", or Ctrl+C to cancel): ");
                } catch (RuntimeException e) {
                    return -1;
                }
                if (input == null) {
                    return -1;
                }
                String trimmed = input.trim();
                if (trimmed.equalsIgnoreCase("q")
                        || trimmed.equalsIgnoreCase("quit")
                        || trimmed.equalsIgnoreCase("cancel")) {
                    return -1;
                }
                try {
                    int selected = Integer.parseInt(trimmed);
                    if (selected >= 1 && selected <= items.size()) {
                        return selected - 1;
                    }
                } catch (NumberFormatException ignored) {
                }
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i).toLowerCase(Locale.ROOT)
                            .contains(trimmed.toLowerCase(Locale.ROOT))) {
                        return i;
                    }
                }
                System.out.println("  " + YELLOW + "Please enter 1-" + items.size()
                        + " or type part of the name." + RESET);
            }
        }

        @Override
        public String text(String label, String defaultValue) {
            String suffix = defaultValue == null || defaultValue.isBlank()
                    ? " "
                    : " [" + defaultValue + "] ";
            try {
                String value = reader.readLine("  " + label + suffix);
                if ((value == null || value.isBlank()) && defaultValue != null) {
                    return defaultValue;
                }
                return value == null ? null : value.trim();
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override
        public String secret(String label) {
            try {
                return reader.readLine("  " + label + " ", '*');
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override
        public boolean confirm(String question, boolean defaultYes) {
            String suffix = defaultYes ? " [Y/n] " : " [y/N] ";
            try {
                String answer = reader.readLine("  " + question + suffix);
                if (answer == null || answer.isBlank()) {
                    return defaultYes;
                }
                return answer.trim().toLowerCase(Locale.ROOT).startsWith("y");
            } catch (RuntimeException e) {
                return false;
            }
        }

        @Override
        public void message(String message) {
            System.out.println("  " + GREEN + message + RESET);
        }

        @Override
        public void close() {
            try {
                terminal.close();
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
